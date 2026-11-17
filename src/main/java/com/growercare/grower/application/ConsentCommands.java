package com.growercare.grower.application;

import com.growercare.common.error.BusinessRuleException;
import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.PermissionDeniedException;
import com.growercare.common.error.ValidationException;
import com.growercare.common.web.TraceIds;
import com.growercare.grower.application.port.ConsentRepository;
import com.growercare.grower.application.port.ConsentRepository.Consent;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.application.port.MessageSender;
import com.growercare.grower.application.port.ReferenceData;
import com.growercare.grower.domain.ConsentRules;
import com.growercare.grower.domain.ConsentRules.Channel;
import com.growercare.grower.domain.ConsentRules.Purpose;
import com.growercare.grower.domain.ConsentRules.VerificationStatus;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.grower.domain.GrowerRecord;
import com.growercare.grower.domain.PhoneProtector;
import com.growercare.outbox.OutboxWriter;
import com.growercare.security.GcPrincipal;
import com.growercare.security.RequiresPermission;
import com.growercare.security.UserType;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Phone verification (OTP) and consent: SPEC-GRW-03 R101–R124. */
@Service
public class ConsentCommands {

    /** R101: what a started verification returns. */
    public record Started(UUID verificationId, String purpose, Instant expiresAt, String phoneLast4) { }

    /** R105: one consent decision of a confirmation. */
    public record ConsentChoice(String purpose, Boolean granted) { }

    public record Confirmation(String code, String noticeVersion, String language, List<ConsentChoice> consents) {
        /** The code is a secret: never printed. */
        @Override
        public String toString() {
            return "Confirmation[noticeVersion=%s, language=%s, consents=%s]".formatted(noticeVersion, language, consents);
        }
    }

    private final GrowerRepository growers;
    private final ConsentRepository consents;
    private final ReferenceData ref;
    private final GrowerAccess access;
    private final PhoneProtector phones;
    private final MessageSender sender;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final ShardRouter router;
    private final Clock clock;

    public ConsentCommands(GrowerRepository growers, ConsentRepository consents, ReferenceData ref, GrowerAccess access,
                           PhoneProtector phones, MessageSender sender, OutboxWriter outbox, TransactionTemplate tx,
                           ShardRouter router, Clock clock) {
        this.growers = growers;
        this.consents = consents;
        this.ref = ref;
        this.access = access;
        this.phones = phones;
        this.sender = sender;
        this.outbox = outbox;
        this.tx = tx;
        this.router = router;
        this.clock = clock;
    }

    // =========================================================================================== start (R101–R104, R112)

    @RequiresPermission(GrowerAccess.CONSENT)
    public Started start(long growerId, String purpose) {
        if (!ConsentRules.ACTIVATION.equals(purpose)) {
            throw ValidationException.of("purpose", "must be " + ConsentRules.ACTIVATION);
        }
        var principal = CurrentPrincipal.require();
        var shard = router.forId(growerId).orElseThrow(() -> new NotFoundException("Grower"));
        var grower = inScope(principal, shard, growerId, GrowerAccess.CONSENT);
        requirePending(grower.status());

        var now = clock.instant();
        var language = ref.currentNotice(grower.countryCode(), Purpose.SERVICE.name(), grower.preferredLanguage(), now).isPresent()
                ? grower.preferredLanguage() : ConsentRules.FALLBACK_LANGUAGE;                    // R112
        var verificationId = UUID.randomUUID();
        var code = phones.newOtpCode();
        var expiresAt = now.plus(ConsentRules.CODE_TTL);

        tx.executeWithoutResult(status -> {
            var locked = consents.lockGrower(shard, growerId).orElseThrow(() -> new NotFoundException("Grower"));
            requirePending(locked.status());                                                       // re-checked under the lock
            var recent = consents.recentStarts(shard, growerId, now.minus(ConsentRules.RATE_WINDOW));
            if (recent.started() >= ConsentRules.MAX_STARTS_PER_WINDOW || recent.exhausted() > 0) {
                throw new BusinessRuleException("grower.otp-rate-limited", "Too many verification codes; try again later");
            }
            consents.insertVerification(shard, growerId, verificationId, ConsentRules.ACTIVATION,
                    phones.otpHmac(verificationId, code), expiresAt, now);
            // R104: inside the transaction, so a refused send leaves no verification behind
            sender.sendOtp(phones.decrypt(locked.phone()), language, code, (int) ConsentRules.CODE_TTL.toMinutes(), verificationId);
        });
        return new Started(verificationId, ConsentRules.ACTIVATION, expiresAt, grower.phoneLast4());
    }

    // ===================================================================================== confirm (R105–R111)

    private enum Outcome { ACTIVATED, WRONG_CODE, LOCKED }

    private record ConfirmResult(Outcome outcome, int attemptsLeft) { }

    @RequiresPermission(GrowerAccess.CONSENT)
    public GrowerRecord confirm(long growerId, UUID verificationId, Confirmation c) {
        if (!ConsentRules.isCode(c.code())) {
            throw ValidationException.of("code", "must be 6 digits");
        }
        if (c.noticeVersion() == null || c.noticeVersion().isBlank()) {
            throw ValidationException.of("noticeVersion", "is required");
        }
        var choices = choices(c.consents());
        var principal = CurrentPrincipal.require();
        var shard = router.forId(growerId).orElseThrow(() -> new NotFoundException("Grower"));
        var grower = inScope(principal, shard, growerId, GrowerAccess.CONSENT);
        var now = clock.instant();
        requireNoticeLanguage(grower.countryCode(), c.language(), now);
        if (verificationId == null) {
            throw new NotFoundException("Grower");                                                 // R110
        }

        var result = tx.execute(status -> {
            var v = consents.lockVerification(shard, growerId, verificationId).orElseThrow(() -> new NotFoundException("Grower"));
            switch (v.status()) {
                case CONSUMED -> throw new BusinessRuleException("grower.otp-used", "This code was already used");
                case EXHAUSTED -> throw new BusinessRuleException("grower.otp-locked", "Too many wrong codes; request a new one later");
                case OPEN -> { }
            }
            if (!now.isBefore(v.expiresAt())) {
                throw new BusinessRuleException("grower.otp-expired", "The code has expired; request a new one");
            }
            // R106/R107: request errors cost no attempt
            if (!Boolean.TRUE.equals(choices.get(Purpose.SERVICE))) {
                throw new BusinessRuleException("grower.service-consent-required", "The SERVICE consent is required to activate");
            }
            for (var purpose : choices.keySet()) {
                requireCurrentNotice(grower.countryCode(), purpose, c.language(), c.noticeVersion(), now);
            }
            if (!phones.otpMatches(verificationId, c.code(), v.codeHmac())) {                    // R108
                int attempts = v.attempts() + 1;
                boolean exhausted = attempts >= ConsentRules.MAX_ATTEMPTS;
                consents.updateVerification(shard, verificationId, attempts,
                        exhausted ? VerificationStatus.EXHAUSTED : VerificationStatus.OPEN, now);
                return new ConfirmResult(exhausted ? Outcome.LOCKED : Outcome.WRONG_CODE, ConsentRules.MAX_ATTEMPTS - attempts);
            }
            consents.lockGrower(shard, growerId).orElseThrow(() -> new NotFoundException("Grower"));
            int version = consents.activate(shard, growerId, now).orElseThrow(() -> notPending(grower.status()));
            consents.updateVerification(shard, verificationId, v.attempts(), VerificationStatus.CONSUMED, now);
            var channel = channel(principal, grower);
            choices.forEach((purpose, granted) -> record(shard, growerId,
                    new Consent(purpose, granted, c.noticeVersion(), c.language(), channel, principal.userId(), now)));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("growerId", Long.toString(growerId));
            payload.put("version", version);
            payload.put("changedFields", List.of("status"));
            outbox.append(shard.schemaName() + ".outbox",
                    new OutboxWriter.Event("grower", Long.toString(growerId), "grower.updated", payload), TraceIds.currentOrNull());
            return new ConfirmResult(Outcome.ACTIVATED, 0);
        });
        return switch (result.outcome()) {
            case ACTIVATED -> growers.findById(shard, growerId).orElseThrow();
            case WRONG_CODE -> throw new OtpRejectedException(result.attemptsLeft());
            case LOCKED -> throw new BusinessRuleException("grower.otp-locked", "Too many wrong codes; request a new one later");
        };
    }

    // ============================================================================= optional consents (R121–R124)

    @RequiresPermission(GrowerAccess.CONSENT)
    public Consent setConsent(long growerId, String purposeText, Boolean granted, String noticeVersion, String language) {
        var purpose = ConsentRules.purpose(purposeText);
        if (purpose == null) {
            throw ValidationException.of("purpose", "must be one of " + EnumSet.allOf(Purpose.class));
        }
        if (granted == null) {
            throw ValidationException.of("granted", "is required");
        }
        if (noticeVersion == null || noticeVersion.isBlank()) {
            throw ValidationException.of("noticeVersion", "is required");
        }
        var principal = CurrentPrincipal.require();
        var shard = router.forId(growerId).orElseThrow(() -> new NotFoundException("Grower"));
        var grower = inScope(principal, shard, growerId, GrowerAccess.CONSENT);
        var now = clock.instant();
        requireNoticeLanguage(grower.countryCode(), language, now);
        if (purpose == Purpose.SERVICE && !granted) {                                               // R122
            throw new BusinessRuleException("grower.service-consent-required",
                    "The SERVICE consent cannot be withdrawn here; that is an account closure request");
        }
        if (channel(principal, grower) != Channel.SELF) {                                           // R123
            throw new PermissionDeniedException(GrowerAccess.CONSENT);
        }
        if (!"ACTIVE".equals(grower.status())) {                                                    // R124
            throw new BusinessRuleException("grower.not-active", "Consent is first recorded when the grower is activated");
        }
        requireCurrentNotice(grower.countryCode(), purpose, language, noticeVersion, now);
        var consent = new Consent(purpose, granted, noticeVersion, language, Channel.SELF, principal.userId(), now);
        tx.executeWithoutResult(status -> record(shard, growerId, consent));
        return consent;
    }

    // ================================================================================================== helpers

    private GrowerRecord inScope(GcPrincipal principal, ShardId shard, long growerId, String permission) {
        var grower = growers.findById(shard, growerId).orElseThrow(() -> new NotFoundException("Grower"));
        access.require(principal, permission, grower.access());
        return grower;
    }

    private static void requirePending(String status) {
        if (!"PENDING_CONSENT".equals(status)) {
            throw notPending(status);
        }
    }

    private static BusinessRuleException notPending(String status) {
        return "ACTIVE".equals(status)
                ? new BusinessRuleException("grower.already-active", "The grower is already active")
                : new BusinessRuleException("grower.not-verifiable", "This grower cannot be verified");
    }

    /** R111: a language without a notice in the grower's country is a request error. */
    private void requireNoticeLanguage(String country, String language, Instant now) {
        if (language == null || ref.currentNotice(country, Purpose.SERVICE.name(), language, now).isEmpty()) {
            throw ValidationException.of("language", "must be a language with a consent notice in " + country);
        }
    }

    /** D8/R107: the client must have shown the current notice. */
    private void requireCurrentNotice(String country, Purpose purpose, String language, String version, Instant now) {
        var current = ref.currentNotice(country, purpose.name(), language, now);
        if (current.isEmpty() || !current.get().equals(version)) {
            throw new BusinessRuleException("grower.notice-outdated", "The consent notice was updated; show the current version");
        }
    }

    /** R111: known purposes, each once, each with a decision. Insertion order is kept (stable events). */
    private static LinkedHashMap<Purpose, Boolean> choices(List<ConsentChoice> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ValidationException.of("consents", "must list at least the SERVICE consent");
        }
        var choices = new LinkedHashMap<Purpose, Boolean>();
        for (int i = 0; i < raw.size(); i++) {
            var choice = raw.get(i);
            var purpose = choice == null ? null : ConsentRules.purpose(choice.purpose());
            if (purpose == null) {
                throw ValidationException.of("consents[" + i + "].purpose", "must be one of " + EnumSet.allOf(Purpose.class));
            }
            if (choice.granted() == null) {
                throw ValidationException.of("consents[" + i + "].granted", "is required");
            }
            if (choices.put(purpose, choice.granted()) != null) {
                throw ValidationException.of("consents", "lists " + purpose + " more than once");
            }
        }
        return choices;
    }

    /** D5 / PRD D3: SELF when the grower acts on their own record, otherwise ASSISTED. */
    private static Channel channel(GcPrincipal principal, GrowerRecord grower) {
        return principal.userType() == UserType.GROWER && grower.userId() != null && grower.userId() == principal.userId()
                ? Channel.SELF : Channel.ASSISTED;
    }

    /** One consent row and its event (ids and codes only, no PII). Runs inside the caller's transaction. */
    private void record(ShardId shard, long growerId, Consent consent) {
        consents.insertConsent(shard, growerId, consent);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("growerId", Long.toString(growerId));
        payload.put("purpose", consent.purpose().name());
        payload.put("granted", consent.granted());
        payload.put("noticeVersion", consent.noticeVersion());
        payload.put("channel", consent.channel().name());
        outbox.append(shard.schemaName() + ".outbox",
                new OutboxWriter.Event("grower", Long.toString(growerId), "grower.consent.recorded", payload), TraceIds.currentOrNull());
    }
}
