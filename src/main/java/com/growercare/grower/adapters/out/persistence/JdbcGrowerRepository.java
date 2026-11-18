package com.growercare.grower.adapters.out.persistence;

import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.grower.application.port.GrowerRepository;
import com.growercare.grower.domain.GrowerRecord;
import com.growercare.shard.ShardId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link GrowerRepository} over the shard schemas and {@code dir} with named SQL ({@code sql/postgres/grower*.sql}). */
@Repository
class JdbcGrowerRepository implements GrowerRepository {

    private static final RowMapper<GrowerRecord> GROWER = JdbcGrowerRepository::grower;

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcGrowerRepository(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    @Override
    public Optional<GrowerRecord> findById(ShardId shard, long growerId) {
        return jdbc.sql(sql.render("grower.findById", shard.schemaName())).param("growerId", growerId).query(GROWER).optional();
    }

    @Override
    public java.util.List<GrowerRecord> findByIds(ShardId shard, java.util.Collection<Long> growerIds) {
        if (growerIds.isEmpty()) {
            return java.util.List.of();
        }
        return jdbc.sql(sql.render("grower.findByIds", shard.schemaName()))
                .param("growerIds", growerIds.toArray(Long[]::new)).query(GROWER).list();
    }

    @Override
    public Optional<Replica> findReplica(ShardId shard, long growerId) {
        return jdbc.sql(sql.render("grower.findReplica", shard.schemaName())).param("growerId", growerId)
                .query((rs, n) -> new Replica(grower(rs, n), rs.getBytes("phone_hmac"))).optional();
    }

    @Override
    public Optional<Integer> updateProfile(ShardId shard, long growerId, int expectedVersion, ProfileRow row) {
        return jdbc.sql(sql.render("grower.updateProfile", shard.schemaName()))
                .param("territoryId", row.territoryId())
                .param("displayName", row.displayName())
                .param("preferredLanguage", row.preferredLanguage())
                .param("preferredPartnerOrgId", row.preferredPartnerOrgId())
                .param("growerId", growerId)
                .param("expectedVersion", expectedVersion)
                .query(Integer.class).optional();
    }

    @Override
    public Optional<GrowerRecord> findByPhone(ShardId shard, byte[] phoneHmac) {
        return jdbc.sql(sql.render("grower.findByPhone", shard.schemaName())).param("phoneHmac", phoneHmac).query(GROWER).optional();
    }

    /** The only unique key besides the generated id is {@code grower_user_uq}: a second grower for the same user (R12). */
    @Override
    public long insert(ShardId shard, NewGrowerRow row) {
        try {
            return insertRow(shard, row);
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(DuplicateException.Kind.USER, e);
        }
    }

    private long insertRow(ShardId shard, NewGrowerRow row) {
        return jdbc.sql(sql.render("grower.insert", shard.schemaName()))
                .param("territoryId", row.territoryId())
                .param("userId", row.userId())
                .param("displayName", row.displayName())
                .param("phoneHmac", row.phoneHmac())
                .param("phoneEnc", row.phoneEnc())
                .param("phoneKeyId", row.phoneKeyId())
                .param("phoneLast4", row.phoneLast4())
                .param("preferredLanguage", row.preferredLanguage())
                .param("preferredPartnerOrgId", row.preferredPartnerOrgId())
                .param("status", row.status())
                .param("onboardingChannel", row.onboardingChannel())
                .param("onboardedByUserId", row.onboardedByUserId())
                .param("onboardedViaOrgId", row.onboardedViaOrgId())
                .query(Long.class).single();
    }

    @Override
    public void insertDirectory(ShardId shard, byte[] phoneHmac, long growerId) {
        try {
            jdbc.sql(sql.render("grower.insertDirectory", shard.schemaName()))
                    .param("phoneHmac", phoneHmac).param("growerId", growerId).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(DuplicateException.Kind.PHONE, e);
        }
    }

    @Override
    public boolean userLinked(long userId) {
        return Boolean.TRUE.equals(jdbc.sql(sql.get("grower-dir.userLinked")).param("userId", userId).query(Boolean.class).single());
    }

    @Override
    public void linkUser(long userId, long growerId, String countryCode) {
        try {
            jdbc.sql(sql.get("grower-dir.linkUser"))
                    .param("userId", userId).param("growerId", growerId).param("countryCode", countryCode).update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(DuplicateException.Kind.USER, e);
        }
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotency(UUID key) {
        return jdbc.sql(sql.get("grower-dir.findIdempotency")).param("key", key)
                .query((rs, n) -> new IdempotencyRecord(rs.getObject("client_request_id", UUID.class),
                        rs.getLong("requested_by_user_id"), rs.getBytes("request_hash"), rs.getLong("grower_id")))
                .optional();
    }

    @Override
    public boolean insertIdempotency(UUID key, long userId, byte[] hash, long growerId) {
        return jdbc.sql(sql.get("grower-dir.insertIdempotency"))
                .param("key", key).param("userId", userId).param("hash", hash).param("growerId", growerId)
                .query(UUID.class).optional().isPresent();
    }

    @Override
    public Optional<Long> growerOfUser(long userId) {
        return jdbc.sql(sql.get("grower-dir.growerOfUser")).param("userId", userId).query(Long.class).optional();
    }

    @Override
    public void indexPartner(long partnerOrgId, long growerId, String countryCode) {
        jdbc.sql(sql.get("grower-dir.indexPartner")).param("partnerOrgId", partnerOrgId).param("growerId", growerId)
                .param("countryCode", countryCode).update();
    }

    @Override
    public void unindexPartner(long partnerOrgId, long growerId) {
        jdbc.sql(sql.get("grower-dir.unindexPartner")).param("partnerOrgId", partnerOrgId).param("growerId", growerId).update();
    }

    @Override
    public java.util.List<PartnerGrower> partnerGrowers(long partnerOrgId, PartnerGrower after, int limit) {
        return jdbc.sql(sql.get("grower-dir.partnerGrowers"))
                .param("partnerOrgId", partnerOrgId)
                .param("afterCreatedAt", after == null ? null : java.time.OffsetDateTime.ofInstant(after.createdAt(), java.time.ZoneOffset.UTC),
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("afterGrowerId", after == null ? null : after.growerId(), java.sql.Types.BIGINT)
                .param("limit", limit)
                .query((rs, n) -> new PartnerGrower(rs.getLong("grower_id"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static GrowerRecord grower(ResultSet rs, int row) throws SQLException {
        return new GrowerRecord(
                rs.getLong("grower_id"),
                rs.getString("country_code"),
                rs.getLong("territory_id"),
                rs.getObject("user_id", Long.class),
                rs.getString("display_name"),
                rs.getString("phone_last4"),
                rs.getString("preferred_language"),
                rs.getObject("preferred_partner_org_id", Long.class),
                rs.getString("status"),
                rs.getString("onboarding_channel"),
                rs.getObject("onboarded_by_user_id", Long.class),
                rs.getObject("onboarded_via_org_id", Long.class),
                rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    @Override
    public java.util.Optional<SealedContact> contact(com.growercare.shard.ShardId shard, long growerId) {
        return jdbc.sql(sql.render("grower.contact", shard.schemaName())).param("growerId", growerId)
                .query((rs, n) -> new SealedContact(rs.getBytes("phone_enc"), rs.getString("phone_key_id"),
                        rs.getString("preferred_language"), rs.getString("status")))
                .optional();
    }

    @Override
    public void auditContactRead(long growerId, String clientId) {
        jdbc.sql(sql.get("grower-dir.auditContactRead")).param("growerId", growerId).param("clientId", clientId).update();
    }
}
