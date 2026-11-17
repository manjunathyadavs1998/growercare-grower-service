package com.growercare.grower.application;

import com.growercare.common.error.NotFoundException;
import com.growercare.common.error.ValidationException;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.application.port.ReferenceData;
import com.growercare.grower.domain.GrowerAccess;
import com.growercare.grower.domain.GrowerPlacement;
import com.growercare.grower.domain.GrowerRecord;
import com.growercare.grower.domain.PhoneNumber;
import com.growercare.grower.domain.PhoneProtector;
import com.growercare.security.RequiresPermission;
import com.growercare.security.web.CurrentPrincipal;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import java.util.List;
import org.springframework.stereotype.Service;

/** Grower read use cases: by id (SPEC-GRW-01 R20/R21) and by phone (R30–R32). */
@Service
public class GrowerQueries {

    private final ReferenceData ref;
    private final GrowerRepository growers;
    private final GrowerAccess access;
    private final PhoneProtector phones;
    private final ShardRouter router;

    public GrowerQueries(ReferenceData ref, GrowerRepository growers, GrowerAccess access, PhoneProtector phones, ShardRouter router) {
        this.ref = ref;
        this.growers = growers;
        this.access = access;
        this.phones = phones;
        this.router = router;
    }

    /** R20/R21: routed by decoding the id; unknown, foreign-country and out-of-scope ids are the same 404. */
    @RequiresPermission(GrowerAccess.READ)
    public GrowerRecord get(long growerId) {
        var record = router.forId(growerId)
                .flatMap(shard -> growers.findById(shard, growerId))
                .orElseThrow(() -> new NotFoundException("Grower"));
        access.require(CurrentPrincipal.require(), GrowerAccess.READ, record.access());
        return record;
    }

    /** SPEC-GRW-06 R300/R301: the caller's own grower; 404 for non-growers and growers not registered yet. */
    @RequiresPermission(GrowerAccess.READ)
    public GrowerRecord me() {
        var principal = CurrentPrincipal.require();
        if (principal.userType() != com.growercare.security.UserType.GROWER) {
            throw new NotFoundException("Grower");
        }
        var growerId = growers.growerOfUser(principal.userId()).orElseThrow(() -> new NotFoundException("Grower"));
        return get(growerId);
    }

    /** One page of a partner's growers, and the position of the next page (null when this is the last). */
    public record PartnerPage(java.util.List<GrowerRecord> items, GrowerRepository.PartnerGrower next) { }

    public static final int PARTNER_PAGE = 20;

    /**
     * SPEC-GRW-06 R304/R305: the growers the caller's partner org onboarded or is preferred by, newest first. The org
     * comes from the principal, never from the request; each grower is still checked against the caller's scope.
     */
    @RequiresPermission(GrowerAccess.READ)
    public PartnerPage partnerGrowers(GrowerRepository.PartnerGrower after) {
        var principal = CurrentPrincipal.require();
        if (principal.userType() != com.growercare.security.UserType.PARTNER || principal.partnerOrgId() == null) {
            throw new com.growercare.common.error.PermissionDeniedException(GrowerAccess.READ);
        }
        var rows = growers.partnerGrowers(principal.partnerOrgId(), after, PARTNER_PAGE + 1);
        var page = rows.size() > PARTNER_PAGE ? rows.subList(0, PARTNER_PAGE) : rows;
        // one query per shard (a page spans up to 20 of the 64 shards), then back into the index order
        java.util.Map<com.growercare.shard.ShardId, java.util.List<Long>> byShard = page.stream()
                .map(GrowerRepository.PartnerGrower::growerId)
                .flatMap(id -> router.forId(id).map(shard -> java.util.Map.entry(shard, id)).stream())
                .collect(java.util.stream.Collectors.groupingBy(java.util.Map.Entry::getKey, java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(java.util.Map.Entry::getValue, java.util.stream.Collectors.toList())));
        java.util.Map<Long, GrowerRecord> found = byShard.entrySet().stream()
                .flatMap(e -> growers.findByIds(e.getKey(), e.getValue()).stream())
                .collect(java.util.stream.Collectors.toMap(GrowerRecord::growerId, g -> g));
        var items = page.stream()
                .map(r -> found.get(r.growerId()))
                .filter(java.util.Objects::nonNull)
                .filter(g -> access.permits(principal, GrowerAccess.READ, g.access()))
                .toList();
        return new PartnerPage(items, rows.size() > PARTNER_PAGE ? page.getLast() : null);
    }

    /**
     * R42: the replica view for allow-listed service clients (the route is restricted to them by gc-security; there is
     * no end user, so no grower scope applies). Unknown ids and ids of unknown countries are 404.
     */
    public GrowerRepository.Replica replica(long growerId) {
        return router.forId(growerId)
                .flatMap(shard -> growers.findReplica(shard, growerId))
                .orElseThrow(() -> new NotFoundException("Grower"));
    }

    /**
     * R30–R32: phone → HMAC → the one shard that can hold it. The country only selects the phone plan and the shard;
     * a grower outside the caller's scope is reported exactly like an unregistered number (empty list).
     */
    @RequiresPermission(GrowerAccess.READ)
    public List<GrowerRecord> findByPhone(String rawPhone, String countryCode) {
        var plan = ref.phonePlan(countryCode).orElse(null);
        if (plan == null) {
            return List.of();
        }
        var phone = PhoneNumber.parse(rawPhone, plan)
                .orElseThrow(() -> ValidationException.of("phone", "must be a valid phone number of " + countryCode));
        var hmac = phones.hmac(phone);
        var principal = CurrentPrincipal.require();
        return growers.findByPhone(new ShardId(countryCode, GrowerPlacement.shardNo(hmac)), hmac)
                .filter(g -> access.permits(principal, GrowerAccess.READ, g.access()))
                .map(List::of)
                .orElse(List.of());
    }
}
