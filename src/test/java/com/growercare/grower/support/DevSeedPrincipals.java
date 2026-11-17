package com.growercare.grower.support;

import com.growercare.security.EffectiveGrant;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PiiClearance;
import com.growercare.security.ScopeKind;
import com.growercare.security.UserType;
import com.growercare.testsupport.TestJwts;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The dev-seed users as identity-access-service resolves them, limited to the grower permissions (IAM V7/V8,
 * SPEC-GRW-01 D7, SPEC-GRW-03 D7) plus the grants' real scopes (IAM V1000). Same users as case-service's fixture.
 */
public final class DevSeedPrincipals {

    private DevSeedPrincipals() { }

    static final Set<String> ALL_GROWER = Set.of("grower:consent", "grower:create", "grower:read", "grower:update");
    static final Set<String> CREATE_READ = Set.of("grower:consent", "grower:create", "grower:read");

    public static final GcPrincipal GROWER_IN1 = grower(1, TestJwts.GROWER_IN1, "IN");
    public static final GcPrincipal GROWER_IN2 = grower(2, TestJwts.GROWER_IN2, "IN");
    public static final GcPrincipal GROWER_BR1 = grower(3, TestJwts.GROWER_BR1, "BR");
    public static final GcPrincipal CARE_IN = staff(101, TestJwts.CARE_IN, "IN",
            grant("CARE_AGENT", 30, ScopeKind.COUNTRY, PiiClearance.MASKED, ALL_GROWER, Set.of("IN"), Set.of(), Set.of()));
    /** ASSIGNED in Pune with BU CP: for growers this means "Pune", BU ignored (SPEC-GRW-01 R3). */
    public static final GcPrincipal AGRO_IN = staff(102, TestJwts.AGRO_IN, "IN",
            grant("FIELD_AGRONOMIST", 30, ScopeKind.ASSIGNED, PiiClearance.FULL, ALL_GROWER, Set.of(), Set.of(1002L), Set.of("CP")));
    public static final GcPrincipal TM_IN = staff(103, TestJwts.TM_IN, "IN",
            grant("TERRITORY_MANAGER", 40, ScopeKind.TERRITORY, PiiClearance.FULL, ALL_GROWER, Set.of(), Set.of(1001L, 1002L, 1003L), Set.of()));
    /** COUNTRY_ADMIN: user and grant administration, no grower permissions (GRW-E10). */
    public static final GcPrincipal ADMIN_IN = staff(104, TestJwts.ADMIN_IN, "IN",
            grant("COUNTRY_ADMIN", 60, ScopeKind.COUNTRY, PiiClearance.MASKED,
                    Set.of("user:read", "user:manage", "grant:read", "grant:create", "grant:revoke"), Set.of("IN"), Set.of(), Set.of()));
    public static final GcPrincipal CARE_BR = staff(105, TestJwts.CARE_BR, "BR",
            grant("CARE_AGENT", 30, ScopeKind.COUNTRY, PiiClearance.MASKED, ALL_GROWER, Set.of("BR"), Set.of(), Set.of()));
    public static final GcPrincipal RETAILER_IN = partner(201, TestJwts.RETAILER_IN, 501L);
    public static final GcPrincipal RETAILER2_IN = partner(202, TestJwts.RETAILER2_IN, 502L);

    public static List<GcPrincipal> all() {
        return List.of(GROWER_IN1, GROWER_IN2, GROWER_BR1, CARE_IN, AGRO_IN, TM_IN, ADMIN_IN, CARE_BR, RETAILER_IN, RETAILER2_IN);
    }

    /** Test-only GROWER accounts with no grower yet (self-registration rows). Subject encodes the user id. */
    private static final String TEST_GROWER_PREFIX = "6f1c0a10-0900-4a00-8000-";

    public static GcPrincipal testGrower(long userId) {
        return grower(userId, UUID.fromString(TEST_GROWER_PREFIX + "%012d".formatted(userId)), "IN");
    }

    /** What the stubbed IAM resolves: the dev seed, plus any {@link #testGrower} subject. */
    public static java.util.Optional<GcPrincipal> resolve(UUID subject) {
        var known = all().stream().filter(p -> p.subject().equals(subject)).findFirst();
        if (known.isPresent() || !subject.toString().startsWith(TEST_GROWER_PREFIX)) {
            return known;
        }
        return java.util.Optional.of(testGrower(Long.parseLong(subject.toString().substring(TEST_GROWER_PREFIX.length()))));
    }

    private static EffectiveGrant grant(String role, int level, ScopeKind kind, PiiClearance pii, Set<String> perms,
                                        Set<String> countries, Set<Long> territories, Set<String> bus) {
        return new EffectiveGrant(level * 1000L, role, level, kind, pii, perms, countries, territories, bus, null);
    }

    private static GcPrincipal grower(long userId, UUID subject, String country) {
        return new GcPrincipal(userId, subject, UserType.GROWER, country, null, List.of(
                grant("GROWER", 10, ScopeKind.OWN, PiiClearance.FULL, ALL_GROWER, Set.of(country), Set.of(), Set.of())));
    }

    private static GcPrincipal staff(long userId, UUID subject, String country, EffectiveGrant grant) {
        return new GcPrincipal(userId, subject, UserType.STAFF, country, null, List.of(grant));
    }

    private static GcPrincipal partner(long userId, UUID subject, long org) {
        return new GcPrincipal(userId, subject, UserType.PARTNER, "IN", org, List.of(
                grant("RETAILER_USER", 20, ScopeKind.ORG, PiiClearance.MASKED, CREATE_READ, Set.of("IN"), Set.of(), Set.of())));
    }
}
