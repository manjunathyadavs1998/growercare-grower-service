package com.growercare.grower.domain;

import com.growercare.security.EffectiveGrant;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PolicyEnforcer;
import com.growercare.security.ResourceScope;
import com.growercare.security.ScopeKind;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Who may act on which grower (SPEC-GRW-01 R1–R5), on top of the shared ABAC engine:
 * <ul>
 *   <li>OWN (R1): the grower linked to the user;</li>
 *   <li>ORG (R2): growers whose preferred partner <b>or</b> onboarding partner is the user's org (ACC-11);</li>
 *   <li>geo (R3): territory / country / region of the grant. An ASSIGNED grant counts as its territory, because
 *       growers are not assigned to staff the way cases are, and <b>BU scope does not apply</b> to growers;</li>
 *   <li>GLOBAL, expired grants, missing permission (R4): nothing; out of scope answers 404.</li>
 * </ul>
 * {@link #growerView} is the single place where those two grower-specific differences are applied, so the in-memory
 * check here and the SQL filter ({@code GrowerAccessScope}) see the same grants (R5).
 */
public final class GrowerAccess {

    public static final String CREATE = "grower:create";
    public static final String READ = "grower:read";
    public static final String UPDATE = "grower:update";
    /** Same resource name as every other 404 here, so out-of-scope and unknown growers are indistinguishable (R21). */
    static final String RESOURCE = "Grower";

    /** The access-relevant attributes of one grower. */
    public record Attributes(String countryCode, Long territoryId, Long userId, Long preferredPartnerOrgId, Long onboardedViaOrgId) {
        public Attributes {
            Objects.requireNonNull(countryCode, "countryCode");
        }

        ResourceScope toResourceScope() {
            var partners = Stream.of(preferredPartnerOrgId, onboardedViaOrgId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            return new ResourceScope(countryCode, territoryId, null, userId, partners, null);
        }
    }

    private final PolicyEnforcer enforcer;

    public GrowerAccess(PolicyEnforcer enforcer) {
        this.enforcer = Objects.requireNonNull(enforcer);
    }

    public boolean permits(GcPrincipal principal, String permission, Attributes grower) {
        return enforcer.permits(growerView(principal), permission, grower.toResourceScope());
    }

    /**
     * R14: may the principal register a grower with these attributes? Staff are bound by their grant's geography;
     * growers (OWN) and partners (ORG) carry no geography in their grant, so they register only in their home country.
     */
    public boolean permitsCreate(GcPrincipal principal, Attributes grower) {
        var userType = principal.userType();
        if ((userType == com.growercare.security.UserType.GROWER || userType == com.growercare.security.UserType.PARTNER)
                && !grower.countryCode().equals(principal.homeCountry())) {
            return false;
        }
        return permits(principal, CREATE, grower);
    }

    /** @throws com.growercare.common.error.NotFoundException (404) when the grower is outside the principal's scope */
    public void require(GcPrincipal principal, String permission, Attributes grower) {
        enforcer.require(growerView(principal), permission, grower.toResourceScope(), RESOURCE);
    }

    /** The principal's grants as they apply to growers: ASSIGNED → TERRITORY, BU restriction removed (R3). */
    public static GcPrincipal growerView(GcPrincipal principal) {
        var grants = principal.grants().stream().map(GrowerAccess::forGrowers).toList();
        return new GcPrincipal(principal.userId(), principal.subject(), principal.userType(), principal.homeCountry(),
                principal.partnerOrgId(), grants);
    }

    private static EffectiveGrant forGrowers(EffectiveGrant grant) {
        var kind = grant.scopeKind() == ScopeKind.ASSIGNED ? ScopeKind.TERRITORY : grant.scopeKind();
        return new EffectiveGrant(grant.grantId(), grant.roleCode(), grant.roleLevel(), kind, grant.piiClearance(),
                grant.permissions(), grant.countries(), grant.territories(), Set.of(), grant.validTo());
    }
}
