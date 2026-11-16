package com.growercare.grower.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.grower.support.GrowerTestDatabase;
import com.growercare.shard.ShardId;
import com.growercare.shard.migration.ShardedFlywayMigrator;
import com.growercare.testsupport.PostgresTestDatabase;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

/** Wiring of the schema bootstrap (SPEC-GRW-01 §4, R57) against the light test database. */
class GrowerSchemaConfigIT {

    private static PostgresTestDatabase db;
    private final GrowerSchemaConfig config = new GrowerSchemaConfig();

    @BeforeAll
    static void migrate() {
        db = GrowerTestDatabase.migrated();
    }

    private static MigrationProperties props(boolean devSeed) {
        return new MigrationProperties(true, db.ownerUser(), db.ownerPassword(), db.appUser(), PostgresTestDatabase.CDC_ROLE,
                devSeed, 2);
    }

    static Stream<Arguments> locations() {
        return Stream.of(
                argumentSet("production: no dev seed, strict order", false,
                        List.of("classpath:db/grower/common"), List.of("classpath:db/grower/shard")),
                argumentSet("local/tests: dev seed in both locations, out of order allowed", true,
                        List.of("classpath:db/grower/common", "classpath:db/grower/dev-seed"),
                        List.of("classpath:db/grower/shard", "classpath:db/grower/shard-dev-seed")));
    }

    @ParameterizedTest
    @MethodSource("locations")
    void settingsAddTheDevSeedOnlyWhenAsked(boolean devSeed, List<String> common, List<String> shard) {
        var settings = GrowerSchemaConfig.settings(props(devSeed));
        assertThat(settings.commonLocations()).isEqualTo(common);
        assertThat(settings.shardLocations()).isEqualTo(shard);
        assertThat(settings.outOfOrder()).isEqualTo(devSeed);
        assertThat(settings.placeholders()).containsEntry("app_role", db.appUser()).containsEntry("cdc_role", PostgresTestDatabase.CDC_ROLE);
    }

    @Test
    void GRW_E97_startupMigrationOnAMigratedDatabaseIsANoOpAndRoutingWorks() {
        var dataSource = new DataSourceProperties();
        dataSource.setUrl(db.jdbcUrl());
        var report = config.growerSchemaMigration(dataSource, props(true));
        assertThat(report.commonMigrations()).isZero();
        assertThat(report.shardMigrations()).isZero();

        var registry = config.countryRegistry(db.appDataSource(), provider(report));
        var router = config.shardRouter(registry);
        // G1 (dev seed): encoded for IN shard 39 (SPEC-GRW-01 D1, GRW-E96)
        assertThat(router.forId(110165911145454593L)).contains(new ShardId("IN", 39));
        assertThat(router.forId(42L)).isEmpty();
    }

    @Test
    void migrationPropertiesFillSafeDefaults() {
        var props = new MigrationProperties(false, null, null, "app", " ", false, 0);
        assertThat(props.cdcRole()).isEqualTo("gc_cdc");
        assertThat(props.parallelism()).isEqualTo(4);
    }

    private static ObjectProvider<ShardedFlywayMigrator.Report> provider(ShardedFlywayMigrator.Report report) {
        return new ObjectProvider<>() {
            @Override
            public ShardedFlywayMigrator.Report getObject() {
                return report;
            }
        };
    }
}
