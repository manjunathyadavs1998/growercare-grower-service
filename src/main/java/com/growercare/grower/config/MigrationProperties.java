package com.growercare.grower.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code gc.migration.*}: sharded schema migrations, executed with the schema-owner role (never the runtime role).
 *
 * @param enabled       run migrations at startup (false in prod services; a pre-deploy Job runs them)
 * @param ownerUser     owner role used for DDL
 * @param ownerPassword owner password (from the environment / Secrets Manager)
 * @param appRole       runtime role that receives DML grants (placeholder {@code app_role})
 * @param cdcRole       Debezium login role that may read the outbox tables only (placeholder {@code cdc_role})
 * @param devSeed       also apply {@code db/grower/dev-seed} (local and tests only)
 * @param parallelism   shard schemas migrated concurrently
 */
@ConfigurationProperties("gc.migration")
public record MigrationProperties(
        boolean enabled,
        String ownerUser,
        String ownerPassword,
        String appRole,
        String cdcRole,
        boolean devSeed,
        int parallelism) {

    public MigrationProperties {
        if (cdcRole == null || cdcRole.isBlank()) {
            cdcRole = "gc_cdc";
        }
        if (parallelism <= 0) {
            parallelism = 4;
        }
    }
}
