package com.growercare.grower.config;

import com.growercare.shard.CountryRegistry;
import com.growercare.shard.CountryRegistryLoader;
import com.growercare.shard.ShardRouter;
import com.growercare.shard.migration.ShardedFlywayMigrator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Schema bootstrap and routing for the sharded grower database (SPEC-GRW-01 §4).
 * <ol>
 *   <li>{@link ShardedFlywayMigrator} runs first (when enabled) with a short-lived owner-role pool.</li>
 *   <li>{@link CountryRegistry} is then loaded from {@code ref.country} and backs the {@link ShardRouter}.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MigrationProperties.class)
public class GrowerSchemaConfig {

    @Bean
    @ConditionalOnProperty(prefix = "gc.migration", name = "enabled", havingValue = "true")
    ShardedFlywayMigrator.Report growerSchemaMigration(DataSourceProperties dataSource, MigrationProperties props) {
        try (var owner = ownerDataSource(dataSource, props)) {
            return new ShardedFlywayMigrator(owner, settings(props)).migrate();
        }
    }

    /** Locations and placeholders; the dev seed adds a common and a shard location (local and tests only). */
    static ShardedFlywayMigrator.Settings settings(MigrationProperties props) {
        var common = new ArrayList<>(List.of("classpath:db/grower/common"));
        var shard = new ArrayList<>(List.of("classpath:db/grower/shard"));
        if (props.devSeed()) {
            common.add("classpath:db/grower/dev-seed");
            shard.add("classpath:db/grower/shard-dev-seed");
        }
        return new ShardedFlywayMigrator.Settings(
                common, shard,
                Map.of("app_role", props.appRole(), "cdc_role", props.cdcRole()),
                props.parallelism(),
                ShardedFlywayMigrator.Settings.ALL_SHARDS,
                props.devSeed());   // SPEC-PLAT-04 R63: out-of-order only with the dev seed (never in production)
    }

    @Bean
    CountryRegistry countryRegistry(DataSource dataSource, ObjectProvider<ShardedFlywayMigrator.Report> migration) {
        migration.getIfAvailable();   // ordering: migrate before reading reference data
        return CountryRegistryLoader.load(JdbcClient.create(dataSource));
    }

    @Bean
    ShardRouter shardRouter(CountryRegistry countryRegistry) {
        return new ShardRouter(countryRegistry);
    }

    private static HikariDataSource ownerDataSource(DataSourceProperties dataSource, MigrationProperties props) {
        var config = new HikariConfig();
        config.setPoolName("grower-migration-owner");
        config.setJdbcUrl(dataSource.determineUrl());
        config.setUsername(props.ownerUser());
        config.setPassword(props.ownerPassword());
        // Each shard's Flyway run holds ~2 connections while it waits on the shard DDL advisory lock; a pool of
        // parallelism + 1 deadlocks (skill sharded-migration, lessons). 3 per worker leaves headroom.
        config.setMaximumPoolSize(props.parallelism() * 3 + 1);
        config.setConnectionTimeout(Duration.ofSeconds(60).toMillis());
        return new HikariDataSource(config);
    }
}
