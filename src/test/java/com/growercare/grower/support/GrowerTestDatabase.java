package com.growercare.grower.support;

import com.growercare.shard.Country;
import com.growercare.shard.CountryRegistry;
import com.growercare.shard.ShardId;
import com.growercare.shard.ShardRouter;
import com.growercare.shard.migration.ShardedFlywayMigrator;
import com.growercare.testsupport.PostgresTestDatabase;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The grower database for integration tests, kept <b>light</b>: the common schema + dev seed, but only the shard
 * schemas the tests use (6 instead of 128), migrated <b>once per JVM</b>.
 */
public final class GrowerTestDatabase {

    /** Shard DDL is serialised by an advisory lock anyway; low parallelism keeps laptop Docker VMs healthy. */
    public static final int MIGRATION_PARALLELISM = 2;

    /** Same registry as the ref.country seed (IN index 46, BR index 8). */
    public static final ShardRouter ROUTER = new ShardRouter(new CountryRegistry(List.of(
            new Country("IN", 46, 71, "APAC", ZoneId.of("Asia/Kolkata")),
            new Country("BR", 8, 31, "LATAM", ZoneId.of("America/Sao_Paulo")))));

    /** Dev-seed growers (db/grower/dev-seed): id → shard, from SPEC-GRW-01 D1 with the dev HMAC key. */
    public static final Map<String, ShardId> SEED_SHARDS = Map.of(
            "G1", new ShardId("IN", 39), "G2", new ShardId("IN", 30),
            "G3", new ShardId("IN", 40), "G4", new ShardId("BR", 41));

    /** Shards that schema tests name literally. */
    public static final Set<ShardId> FIXED_SHARDS = Set.of(new ShardId("IN", 5), new ShardId("BR", 5));

    public static final Set<ShardId> TEST_SHARDS = Stream.concat(FIXED_SHARDS.stream(), SEED_SHARDS.values().stream())
            .collect(Collectors.toUnmodifiableSet());

    private static boolean migrated;

    private GrowerTestDatabase() { }

    public static synchronized PostgresTestDatabase migrated() {
        var db = PostgresTestDatabase.forService("gc_grower");
        if (!migrated) {
            migrator(db).migrate();
            // Keep ref.shard consistent with the light database (only schemas that exist). Owner role; test-only.
            var keep = TEST_SHARDS.stream().map(ShardId::schemaName).toList();
            JdbcClient.create(db.ownerDataSource())
                    .sql("DELETE FROM ref.shard WHERE schema_name NOT IN (:keep)").param("keep", keep).update();
            migrated = true;
        }
        return db;
    }

    public static ShardedFlywayMigrator migrator(PostgresTestDatabase db) {
        return new ShardedFlywayMigrator(db.ownerDataSource(), new ShardedFlywayMigrator.Settings(
                List.of("classpath:db/grower/common", "classpath:db/grower/dev-seed"),
                List.of("classpath:db/grower/shard", "classpath:db/grower/shard-dev-seed"),
                Map.of("app_role", db.appUser(), "cdc_role", PostgresTestDatabase.CDC_ROLE),
                MIGRATION_PARALLELISM,
                TEST_SHARDS::contains));
    }
}
