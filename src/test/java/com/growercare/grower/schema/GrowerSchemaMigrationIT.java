package com.growercare.grower.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.grower.domain.GrowerPlacement;
import com.growercare.grower.domain.PhoneNumber;
import com.growercare.grower.domain.PhonePlan;
import com.growercare.grower.domain.PhoneProtector;
import com.growercare.grower.support.GrowerTestDatabase;
import com.growercare.shard.IdCodec;
import com.growercare.testsupport.PostgresTestDatabase;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SPEC-GRW-01 R50–R57 (examples GRW-E90…E97): the gc_grower schema. */
class GrowerSchemaMigrationIT {

    /** Dev keys from .env.example (dev seed only). */
    static final PhoneProtector DEV_KEYS = PhoneProtector.fromBase64(
            "ZGV2LW9ubHktcGhvbmUtaG1hYy1rZXktMzItYnl0ZXM=", "ZGV2LW9ubHktcGhvbmUtZW5jcnlwdGlvbi1rZXktMzI=", "dev-1");

    private static PostgresTestDatabase db;
    private static JdbcClient app;
    private static JdbcClient owner;

    @BeforeAll
    static void migrate() {
        db = GrowerTestDatabase.migrated();
        app = JdbcClient.create(db.appDataSource());
        owner = JdbcClient.create(db.ownerDataSource());
    }

    static byte[] randomHmac() {
        var bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @Test
    void GRW_E97_rerunningMigrationIsANoOp() {
        var second = GrowerTestDatabase.migrator(db).migrate();
        assertThat(second.shardCount()).isEqualTo(GrowerTestDatabase.TEST_SHARDS.size());
        assertThat(second.commonMigrations()).isZero();
        assertThat(second.shardMigrations()).isZero();
    }

    @Nested
    class R50Identifiers {

        static Stream<Arguments> shards() {
            return Stream.of(
                    argumentSet("GRW-E90 IN shard 5", "in_s05", "IN", 1002L, 46),
                    argumentSet("GRW-E90 BR shard 5", "br_s05", "BR", 2002L, 8));
        }

        @ParameterizedTest
        @MethodSource("shards")
        void databaseGeneratedIdsDecodeToCountryAndShard(String schema, String country, long territory, int countryIx) {
            var ids = new ArrayList<Long>();
            for (int i = 0; i < 3; i++) {
                ids.add(insertGrowerWithOnboarder(schema, territory));
            }
            assertThat(ids).isSorted().doesNotHaveDuplicates();
            var decoded = IdCodec.decode(ids.getFirst());
            assertThat(decoded.countryIx()).isEqualTo(countryIx);
            assertThat(decoded.shardNo()).isEqualTo(5);
        }
    }

    /** ASSISTED rows need the onboarding user (R52); used where the row content does not matter. */
    static long insertGrowerWithOnboarder(String schema, long territoryId) {
        return app.sql("""
                INSERT INTO %s.grower (territory_id, display_name, phone_hmac, phone_enc, phone_key_id, phone_last4,
                                       preferred_language, status, onboarding_channel, onboarded_by_user_id)
                VALUES (:territory, 'Test Grower', :hmac, '\\x00'::bytea, 'test', '0000', 'en', 'PENDING_CONSENT', 'ASSISTED', 102)
                RETURNING grower_id""".formatted(schema))
                .param("territory", territoryId).param("hmac", randomHmac())
                .query(Long.class).single();
    }

    @Nested
    class R51R53Uniqueness {

        @Test
        void GRW_E91_phoneIsUniquePerShardDirectory() {
            var hmac = randomHmac();
            long growerId = insertGrowerWithOnboarder("in_s05", 1002);
            app.sql("INSERT INTO in_s05.phone_directory (phone_hmac, grower_id) VALUES (:h, :g)").param("h", hmac).param("g", growerId).update();
            assertThatThrownBy(() -> app.sql("INSERT INTO in_s05.phone_directory (phone_hmac, grower_id) VALUES (:h, :g)")
                    .param("h", hmac).param("g", growerId).update())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("phone_directory_pk");
        }

        @Test
        void GRW_E93_aUserLinksToOneGrowerOnly() {
            assertThatThrownBy(() -> app.sql("INSERT INTO dir.grower_user_link (user_id, grower_id, country_code) VALUES (1, 42, 'IN')").update())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("grower_user_link_pk");
        }
    }

    @Nested
    class R52Integrity {

        static final String BASE = """
                INSERT INTO in_s05.grower (country_code, territory_id, user_id, display_name, phone_hmac, phone_enc, phone_key_id,
                                           phone_last4, preferred_language, preferred_partner_org_id, status, onboarding_channel,
                                           onboarded_by_user_id)
                VALUES (:country, :territory, :userId, 'Test Grower', :hmac, '\\x00'::bytea, 'test', :last4, 'en', :partner,
                        'ACTIVE', :channel, :onboardedBy)""";

        record Row(String country, long territory, Long userId, int hmacBytes, String last4, Long partner, String channel, Long onboardedBy) { }

        static final Row VALID = new Row("IN", 1002, 900L, 32, "0000", null, "SELF", null);

        static Stream<Arguments> violations() {
            return Stream.of(
                    argumentSet("GRW-E92 territory of another country", new Row("IN", 2002, 901L, 32, "0000", null, "SELF", null), "grower_territory_fk"),
                    argumentSet("GRW-E92 partner of another country", new Row("IN", 1002, 902L, 32, "0000", 504L, "SELF", null), "grower_partner_fk"),
                    argumentSet("GRW-E92 country other than the shard's", new Row("BR", 2002, 903L, 32, "0000", null, "SELF", null), "grower_country_check"),
                    argumentSet("GRW-E92 SELF without a user", new Row("IN", 1002, null, 32, "0000", null, "SELF", null), "grower_onboarding_check"),
                    argumentSet("GRW-E92 ASSISTED without the onboarding user", new Row("IN", 1002, null, 32, "0000", null, "ASSISTED", null), "grower_onboarding_check"),
                    argumentSet("GRW-E92 31-byte HMAC", new Row("IN", 1002, 904L, 31, "0000", null, "SELF", null), "grower_phone_hmac_check"),
                    argumentSet("GRW-E92 last 4 not digits", new Row("IN", 1002, 905L, 32, "12a4", null, "SELF", null), "grower_phone_last4_check"));
        }

        @ParameterizedTest
        @MethodSource("violations")
        void rejectedByANamedConstraint(Row row, String constraint) {
            assertThatThrownBy(() -> insert(row)).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining(constraint);
        }

        @Test
        void GRW_E92_theValidRowIsAccepted() {
            insert(VALID);
        }

        private static void insert(Row row) {
            var hmac = new byte[row.hmacBytes()];
            new java.security.SecureRandom().nextBytes(hmac);
            app.sql(BASE).param("country", row.country()).param("territory", row.territory()).param("userId", row.userId())
                    .param("hmac", hmac).param("last4", row.last4()).param("partner", row.partner())
                    .param("channel", row.channel()).param("onboardedBy", row.onboardedBy()).update();
        }
    }

    @Nested
    class R54LeastPrivilege {

        static Stream<Arguments> denied() {
            return Stream.of(
                    argumentSet("GRW-E94 DDL in a shard", "CREATE TABLE in_s05.evil (id int)"),
                    argumentSet("GRW-E94 write reference data", "INSERT INTO ref.territory (territory_id, country_code, territory_code, name, territory_level) VALUES (9999, 'IN', 'X', 'X', 'STATE')"),
                    argumentSet("GRW-E94 delete growers", "DELETE FROM in_s05.grower"),
                    argumentSet("GRW-E94 delete directory rows", "DELETE FROM in_s05.phone_directory"),
                    argumentSet("GRW-E94 delete user links", "DELETE FROM dir.grower_user_link"),
                    argumentSet("GRW-E94 update user links", "UPDATE dir.grower_user_link SET grower_id = 1"));
        }

        @ParameterizedTest
        @MethodSource("denied")
        void appRoleIsDenied(String sql) {
            assertThatThrownBy(() -> app.sql(sql).update())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }
    }

    @Nested
    class R55ChangeDataCapture {

        @Test
        void GRW_E95_everyShardOutboxIsPublishedAndTheCdcRoleReadsOnlyOutboxes() {
            var published = owner.sql("SELECT schemaname FROM pg_publication_tables WHERE pubname = 'gc_outbox' AND tablename = 'outbox'")
                    .query(String.class).set();
            var shards = GrowerTestDatabase.TEST_SHARDS.stream().map(s -> s.schemaName()).toList();
            assertThat(published).containsExactlyInAnyOrderElementsOf(shards);
            var cdc = JdbcClient.create(db.cdcDataSource());
            cdc.sql("SELECT count(*) FROM in_s05.outbox").query(Long.class).single();
            assertThatThrownBy(() -> cdc.sql("SELECT count(*) FROM in_s05.grower").query(Long.class).single())
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
        }
    }

    @Nested
    class R56DevSeed {

        static final PhonePlan IN = new PhonePlan("91", "[6-9][0-9]{9}");
        static final PhonePlan BR = new PhonePlan("55", "[1-9][0-9]{9,10}");

        static Stream<Arguments> seed() {
            return Stream.of(
                    argumentSet("GRW-E96 G1 Ramesh Patil", "+919876543210", IN, "IN", 46),
                    argumentSet("GRW-E96 G2 Sunita Rao", "+919822337788", IN, "IN", 46),
                    argumentSet("GRW-E96 G3 Lakshmi", "+919845055432", IN, "IN", 46),
                    argumentSet("GRW-E96 G4 Joao Silva", "+5516991234455", BR, "BR", 8));
        }

        @ParameterizedTest
        @MethodSource("seed")
        void seedGrowersLiveInTheShardOfTheirPhone(String phone, PhonePlan plan, String country, int countryIx) {
            var number = PhoneNumber.parse(phone, plan).orElseThrow();
            var hmac = DEV_KEYS.hmac(number);
            var schema = country.toLowerCase() + "_s%02d".formatted(GrowerPlacement.shardNo(hmac));
            var row = owner.sql("""
                    SELECT g.grower_id, g.phone_enc, g.phone_key_id, g.phone_last4
                      FROM %1$s.phone_directory d JOIN %1$s.grower g ON g.grower_id = d.grower_id
                     WHERE d.phone_hmac = :hmac""".formatted(schema))
                    .param("hmac", hmac).query().singleRow();
            var decoded = IdCodec.decode((Long) row.get("grower_id"));
            assertThat(decoded.countryIx()).isEqualTo(countryIx);
            assertThat(decoded.shardNo()).isEqualTo(GrowerPlacement.shardNo(hmac));
            assertThat(DEV_KEYS.decrypt(new PhoneProtector.Sealed((byte[]) row.get("phone_enc"), (String) row.get("phone_key_id"))))
                    .isEqualTo(phone);
            assertThat(row.get("phone_last4")).isEqualTo(number.lastFour());
            assertThat(HexFormat.of().formatHex(hmac)).hasSize(64);
        }
    }
}
