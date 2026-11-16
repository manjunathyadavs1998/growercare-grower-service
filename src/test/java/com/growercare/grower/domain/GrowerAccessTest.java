package com.growercare.grower.domain;

import static com.growercare.grower.support.DevSeedPrincipals.ADMIN_IN;
import static com.growercare.grower.support.DevSeedPrincipals.AGRO_IN;
import static com.growercare.grower.support.DevSeedPrincipals.CARE_BR;
import static com.growercare.grower.support.DevSeedPrincipals.CARE_IN;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_BR1;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_IN1;
import static com.growercare.grower.support.DevSeedPrincipals.GROWER_IN2;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER2_IN;
import static com.growercare.grower.support.DevSeedPrincipals.RETAILER_IN;
import static com.growercare.grower.support.DevSeedPrincipals.TM_IN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.common.error.NotFoundException;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.security.EffectiveGrant;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PolicyEnforcer;
import com.growercare.security.ScopeKind;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-GRW-01 R1–R4 (isolation matrix GRW-E01…E10), unit level: which seed growers each dev user may read. */
class GrowerAccessTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
    static final GrowerAccess ACCESS = new GrowerAccess(new PolicyEnforcer(CLOCK));

    static Stream<Arguments> matrix() {
        return Stream.of(
                argumentSet("GRW-E01 R1 grower.in1", GROWER_IN1, Set.of("G1")),
                argumentSet("GRW-E02 R1 grower.in2", GROWER_IN2, Set.of("G2")),
                argumentSet("GRW-E03 R1 grower.br1", GROWER_BR1, Set.of("G4")),
                argumentSet("GRW-E04 R3 care.in (COUNTRY IN)", CARE_IN, Set.of("G1", "G2", "G3")),
                argumentSet("GRW-E05 R3 care.br", CARE_BR, Set.of("G4")),
                argumentSet("GRW-E06 R3 tm.in (Maharashtra + districts)", TM_IN, Set.of("G1", "G2")),
                argumentSet("GRW-E07 R3 agro.in (ASSIGNED Pune, BU ignored)", AGRO_IN, Set.of("G1")),
                argumentSet("GRW-E08 R2 retailer.in (501)", RETAILER_IN, Set.of("G1")),
                argumentSet("GRW-E09 R2 retailer2.in (502: preferred G2, onboarded G3)", RETAILER2_IN, Set.of("G2", "G3")),
                argumentSet("GRW-E10 R4 admin.in (no grower:read)", ADMIN_IN, Set.of()));
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void visibleGrowers(GcPrincipal principal, Set<String> visible) {
        var actual = SeedGrowers.all().stream()
                .filter(g -> ACCESS.permits(principal, GrowerAccess.READ, g.access()))
                .map(SeedGrowers.Seed::name)
                .collect(Collectors.toSet());
        assertThat(actual).isEqualTo(visible);
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void requireAnswers404OutsideScope(GcPrincipal principal, Set<String> visible) {
        for (var grower : SeedGrowers.all()) {
            if (visible.contains(grower.name())) {
                ACCESS.require(principal, GrowerAccess.READ, grower.access());
            } else {
                assertThatThrownBy(() -> ACCESS.require(principal, GrowerAccess.READ, grower.access()))
                        .as("%s must not see %s", principal.subject(), grower.name())
                        .isInstanceOf(NotFoundException.class);
            }
        }
    }

    static Stream<Arguments> expiredOrGlobal() {
        var expired = new EffectiveGrant(1, "CARE_AGENT", 30, ScopeKind.COUNTRY, CARE_IN.grants().getFirst().piiClearance(),
                Set.of("grower:read"), Set.of("IN"), Set.of(), Set.of(), Instant.parse("2026-11-01T00:00:00Z"));
        var global = new EffectiveGrant(2, "GLOBAL_EXECUTIVE", 80, ScopeKind.GLOBAL, CARE_IN.grants().getFirst().piiClearance(),
                Set.of("grower:read"), Set.of(), Set.of(), Set.of(), null);
        return Stream.of(
                argumentSet("GRW-E10 R4 expired grant", withGrant(expired)),
                argumentSet("GRW-E10 R4 GLOBAL scope sees no rows", withGrant(global)));
    }

    @ParameterizedTest
    @MethodSource("expiredOrGlobal")
    void expiredAndGlobalGrantsSeeNothing(GcPrincipal principal) {
        assertThat(SeedGrowers.all()).noneMatch(g -> ACCESS.permits(principal, GrowerAccess.READ, g.access()));
    }

    static Stream<Arguments> growerView() {
        return Stream.of(
                argumentSet("GRW-R3 ASSIGNED becomes TERRITORY for growers", AGRO_IN, ScopeKind.TERRITORY),
                argumentSet("GRW-R3 COUNTRY stays COUNTRY", CARE_IN, ScopeKind.COUNTRY),
                argumentSet("GRW-R1 OWN stays OWN", GROWER_IN1, ScopeKind.OWN));
    }

    @ParameterizedTest
    @MethodSource("growerView")
    void growerViewMapsScopesAndIgnoresBu(GcPrincipal principal, ScopeKind expected) {
        var grant = GrowerAccess.growerView(principal).grants().getFirst();
        assertThat(grant.scopeKind()).isEqualTo(expected);
        assertThat(grant.buCodes()).isEmpty();   // BU scope does not apply to growers
        assertThat(grant.permissions()).isEqualTo(principal.grants().getFirst().permissions());
    }

    private static GcPrincipal withGrant(EffectiveGrant grant) {
        return new GcPrincipal(CARE_IN.userId(), CARE_IN.subject(), CARE_IN.userType(), "IN", null, java.util.List.of(grant));
    }
}
