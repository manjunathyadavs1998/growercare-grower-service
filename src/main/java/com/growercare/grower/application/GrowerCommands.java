package com.growercare.grower.application;

import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.ConflictException;
import com.growercare.common.error.ErrorCode;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.ValidationException;
import com.growercare.common.web.TraceIds;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.application.port.GrowerRepository.DuplicateException;
import com.growercare.grower.application.port.GrowerRepository.NewGrowerRow;
import com.growercare.grower.application.port.ReferenceData;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.grower.domain.GrowerPlacement;
import com.growercare.grower.domain.GrowerRecord;
import com.growercare.grower.domain.GrowerRules;
import com.growercare.grower.domain.NewGrowerCommand;
import com.growercare.grower.domain.PhoneNumber;
import com.growercare.grower.domain.PhoneProtector;
import com.growercare.outbox.OutboxWriter;
import com.growercare.security.RequiresPermission;
import com.growercare.security.UserType;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Grower write use cases: register (SPEC-GRW-01 R10–R18). */
@Service
public class GrowerCommands {

    /** Outcome of {@link #register}: a new grower, or the original on an idempotent replay (R16). */
    public record RegisterResult(GrowerRecord record, boolean replayed) { }

    private final ReferenceData ref;
    private final GrowerRepository growers;
    private final GrowerAccess access;
    private final PhoneProtector phones;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final ShardRouter router;

    public GrowerCommands(ReferenceData ref, GrowerRepository growers, GrowerAccess access, PhoneProtector phones,
                          OutboxWriter outbox, TransactionTemplate tx, ShardRouter router) {
        this.ref = ref;
        this.growers = growers;
        this.access = access;
        this.phones = phones;
        this.outbox = outbox;
        this.tx = tx;
        this.router = router;
    }

    @RequiresPermission(GrowerAccess.CREATE)
    public RegisterResult register(NewGrowerCommand cmd) {
        var principal = CurrentPrincipal.require();
        boolean self = principal.userType() == UserType.GROWER;

        // R16/R17 — idempotency first (D9: the key is global, so a replay is recognised before anything else runs;
        // the insert below closes the race)
        var hash = GrowerRules.requestHash(cmd, principal.userId());
        var previous = growers.findIdempotency(cmd.idempotencyKey());
        if (previous.isPresent()) {
            return replay(previous.get(), hash);
        }

        // R15 — request rules that need no reference data
        var name = cmd.displayName().strip();
        if (name.isEmpty() || name.length() > GrowerRules.MAX_NAME) {
            throw ValidationException.of("displayName", "must be 1-" + GrowerRules.MAX_NAME + " characters");
        }
        if (!GrowerRules.isSupportedLanguage(cmd.preferredLanguage())) {
            throw ValidationException.of("preferredLanguage", "must be an ISO 639-1 language, e.g. mr or pt-BR");
        }

        // D4 / R14 — the country comes from the territory, and the caller must be allowed to register there (404 otherwise)
        var territory = ref.territory(cmd.territoryId()).filter(ReferenceData.Territory::active)
                .orElseThrow(() -> new NotFoundException("Territory"));
        var country = territory.countryCode();
        Long viaOrg = principal.userType() == UserType.PARTNER ? principal.partnerOrgId() : null;
        var candidate = new GrowerAccess.Attributes(country, territory.territoryId(), self ? principal.userId() : null,
                cmd.preferredPartnerOrgId(), viaOrg);
        if (!access.permitsCreate(principal, candidate)) {
            throw new NotFoundException("Territory");
        }
        if (cmd.preferredPartnerOrgId() != null
                && ref.partner(cmd.preferredPartnerOrgId()).filter(p -> p.activeIn(country)).isEmpty()) {
            throw new BusinessRuleException("grower.partner-invalid", "The preferred partner is not an active partner in " + country);
        }

        // R15 / D3 — the phone must be a number of the territory's country
        var plan = ref.phonePlan(country).orElseThrow(() -> new NotFoundException("Territory"));
        var phone = PhoneNumber.parse(cmd.phone(), plan)
                .orElseThrow(() -> ValidationException.of("phone", "must be a valid phone number of " + country));

        // R12 — one grower per user (fast path; the user-link key closes the race)
        if (self && growers.userLinked(principal.userId())) {
            throw duplicate(DuplicateException.Kind.USER);
        }

        // D1 / R18 — the phone decides the shard; the id then comes from that shard
        var hmac = phones.hmac(phone);
        var shard = new ShardId(country, GrowerPlacement.shardNo(hmac));

        var sealed = phones.encrypt(phone);
        var row = new NewGrowerRow(territory.territoryId(), self ? principal.userId() : null, name, hmac, sealed.ciphertext(),
                sealed.keyId(), phone.lastFour(), cmd.preferredLanguage(), cmd.preferredPartnerOrgId(),
                self ? "ACTIVE" : "PENDING_CONSENT", self ? "SELF" : "ASSISTED",
                self ? null : principal.userId(), viaOrg);
        try {
            Long growerId = tx.execute(status -> {
                long id = growers.insert(shard, row);
                growers.insertDirectory(shard, hmac, id);
                if (self) {
                    growers.linkUser(principal.userId(), id, country);
                }
                if (!growers.insertIdempotency(cmd.idempotencyKey(), principal.userId(), hash, id)) {
                    status.setRollbackOnly();          // another request with the same key won the race
                    return null;
                }
                outbox.append(shard.schemaName() + ".outbox", registered(id, country, row), TraceIds.currentOrNull());
                return id;
            });
            if (growerId == null) {
                return replay(growers.findIdempotency(cmd.idempotencyKey()).orElseThrow(), hash);
            }
            return new RegisterResult(growers.findById(shard, growerId).orElseThrow(), false);
        } catch (DuplicateException e) {
            // A concurrent retry of the same request loses on the phone key before reaching the idempotency key.
            var retried = growers.findIdempotency(cmd.idempotencyKey());
            if (retried.isPresent()) {
                return replay(retried.get(), hash);
            }
            throw duplicate(e.kind());
        }
    }

    /** R16/R17: same key + same request → the original grower (its shard decoded from its id); other request → 409. */
    private RegisterResult replay(GrowerRepository.IdempotencyRecord previous, byte[] hash) {
        if (!Arrays.equals(previous.requestHash(), hash)) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was already used for a different request");
        }
        var shard = router.forId(previous.growerId()).orElseThrow();
        return new RegisterResult(growers.findById(shard, previous.growerId()).orElseThrow(), true);
    }

    /** R12/R13: one 409 code for duplicates; the message never names the other grower (no enumeration). */
    private static ConflictException duplicate(DuplicateException.Kind kind) {
        return new ConflictException(ErrorCode.DUPLICATE, kind == DuplicateException.Kind.PHONE
                ? "This phone number is already registered"
                : "This account already has a grower registration");
    }

    /** {@code grower.registered}: ids and codes only, never PII (§4). */
    private static OutboxWriter.Event registered(long growerId, String country, NewGrowerRow row) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("growerId", Long.toString(growerId));
        payload.put("countryCode", country);
        payload.put("territoryId", Long.toString(row.territoryId()));
        if (row.userId() != null) {
            payload.put("userId", Long.toString(row.userId()));
        }
        payload.put("status", row.status());
        payload.put("onboardingChannel", row.onboardingChannel());
        return new OutboxWriter.Event("grower", Long.toString(growerId), "grower.registered", payload);
    }
}
