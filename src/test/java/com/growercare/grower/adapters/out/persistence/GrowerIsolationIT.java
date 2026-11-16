package com.growercare.grower.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.growercare.grower.domain.GrowerAccess;
import com.growercare.grower.support.DevSeedPrincipals;
import com.growercare.grower.support.GrowerTestDatabase;
import com.growercare.grower.support.SeedGrowers;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PolicyEnforcer;
import com.growercare.security.ScopeFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SPEC-GRW-01 R5 (GRW-E11): for every dev user, the SQL scope filter over the real seeded shards returns exactly the
 * growers the in-memory {@link PolicyEnforcer} permits — the two never disagree. Runs as the app role.
 */
class GrowerIsolationIT {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
    static final GrowerAccess ACCESS = new GrowerAccess(new PolicyEnforcer(CLOCK));
    static final ScopeFilter FILTER = new ScopeFilter(CLOCK);

    private static JdbcClient app;

    @BeforeAll
    static void migrate() {
        app = JdbcClient.create(GrowerTestDatabase.migrated().appDataSource());
    }

    static Stream<Arguments> principals() {
        return DevSeedPrincipals.all().stream().map(p -> Arguments.argumentSet("GRW-E11 " + p.subject(), p));
    }

    @ParameterizedTest
    @MethodSource("principals")
    void sqlFilterAndEnforcerAgree(GcPrincipal principal) {
        var bySql = new HashSet<Long>();
        for (var shard : SeedGrowers.all().stream().map(SeedGrowers.Seed::shard).collect(Collectors.toSet())) {
            var predicate = GrowerAccessScope.predicate(FILTER, principal, GrowerAccess.READ, shard);
            bySql.addAll(app.sql("SELECT g.grower_id FROM %s.grower g WHERE %s".formatted(shard.schemaName(), predicate.sql()))
                    .params(predicate.params()).query(Long.class).list());
        }
        bySql.retainAll(SeedGrowers.all().stream().map(SeedGrowers.Seed::growerId).collect(Collectors.toSet()));   // ignore rows other ITs add

        Set<Long> byEnforcer = SeedGrowers.all().stream()
                .filter(g -> ACCESS.permits(principal, GrowerAccess.READ, g.access()))
                .map(SeedGrowers.Seed::growerId)
                .collect(Collectors.toSet());
        assertThat(bySql).isEqualTo(byEnforcer);
    }
}
