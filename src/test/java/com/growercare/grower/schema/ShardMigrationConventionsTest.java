package com.growercare.grower.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Static rules every shard-template migration must follow (skill: sharded-migration). */
class ShardMigrationConventionsTest {

    private static final Pattern FIRST_STATEMENT = Pattern.compile("^\\s*SELECT pg_advisory_xact_lock\\(7240001\\);", Pattern.MULTILINE);
    private static final Pattern HARDCODED_SCHEMA = Pattern.compile("\\b(in|br)_s\\d{2}\\b");

    private final Resource[] migrations = load();

    @Test
    void everyShardMigrationFirstTakesTheShardDdlLock() throws IOException {
        assertThat(migrations).isNotEmpty();
        for (var migration : migrations) {
            var sql = withoutComments(migration.getContentAsString(StandardCharsets.UTF_8));
            var matcher = FIRST_STATEMENT.matcher(sql);
            assertThat(matcher.find() && sql.substring(0, matcher.start()).isBlank())
                    .as("%s must start with SELECT pg_advisory_xact_lock(7240001);", migration.getFilename())
                    .isTrue();
        }
    }

    @Test
    void shardMigrationsUsePlaceholdersNeverConcreteSchemaNames() throws IOException {
        for (var migration : migrations) {
            var sql = withoutComments(migration.getContentAsString(StandardCharsets.UTF_8));
            assertThat(HARDCODED_SCHEMA.matcher(sql).find())
                    .as("%s hard-codes a shard schema; use ${shard_schema}", migration.getFilename())
                    .isFalse();
        }
    }

    private static String withoutComments(String sql) {
        return Arrays.stream(sql.split("\\R"))
                .map(line -> line.replaceFirst("--.*$", ""))
                .reduce("", (a, b) -> a + b + "\n");
    }

    private static Resource[] load() {
        try {
            return new PathMatchingResourcePatternResolver().getResources("classpath:db/grower/shard/V*.sql");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
