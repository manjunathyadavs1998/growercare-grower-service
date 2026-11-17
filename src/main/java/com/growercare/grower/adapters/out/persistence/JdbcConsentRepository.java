package com.growercare.grower.adapters.out.persistence;

import com.growercare.common.sql.NamedSqlRegistry;
import com.growercare.grower.application.port.ConsentRepository;
import com.growercare.grower.domain.ConsentRules.Channel;
import com.growercare.grower.domain.ConsentRules.Purpose;
import com.growercare.grower.domain.ConsentRules.VerificationStatus;
import com.growercare.grower.domain.PhoneProtector;
import com.growercare.shard.ShardId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ConsentRepository} with named SQL ({@code sql/postgres/grower-consent.sql}). */
@Repository
class JdbcConsentRepository implements ConsentRepository {

    private final JdbcClient jdbc;
    private final NamedSqlRegistry sql;

    JdbcConsentRepository(JdbcClient jdbc, NamedSqlRegistry sql) {
        this.jdbc = jdbc;
        this.sql = sql;
    }

    private String statement(String name, ShardId shard) {
        return sql.render("grower-consent." + name, shard.schemaName());
    }

    @Override
    public Optional<LockedGrower> lockGrower(ShardId shard, long growerId) {
        return jdbc.sql(statement("lockGrower", shard)).param("growerId", growerId)
                .query((rs, n) -> new LockedGrower(rs.getString("status"), rs.getInt("version"),
                        new PhoneProtector.Sealed(rs.getBytes("phone_enc"), rs.getString("phone_key_id"))))
                .optional();
    }

    @Override
    public RecentStarts recentStarts(ShardId shard, long growerId, Instant since) {
        return jdbc.sql(statement("recentStarts", shard)).param("growerId", growerId).param("since", Timestamp.from(since))
                .query((rs, n) -> new RecentStarts(rs.getInt("started"), rs.getInt("exhausted"))).single();
    }

    @Override
    public void insertVerification(ShardId shard, long growerId, UUID verificationId, String purpose, byte[] codeHmac,
                                   Instant expiresAt, Instant now) {
        jdbc.sql(statement("insertVerification", shard))
                .param("verificationId", verificationId).param("growerId", growerId).param("purpose", purpose)
                .param("codeHmac", codeHmac).param("expiresAt", Timestamp.from(expiresAt)).param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public Optional<Verification> lockVerification(ShardId shard, long growerId, UUID verificationId) {
        return jdbc.sql(statement("lockVerification", shard)).param("verificationId", verificationId).param("growerId", growerId)
                .query((rs, n) -> new Verification(rs.getObject("verification_id", UUID.class), rs.getBytes("code_hmac"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getInt("attempts"),
                        VerificationStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    @Override
    public void updateVerification(ShardId shard, UUID verificationId, int attempts, VerificationStatus status, Instant now) {
        jdbc.sql(statement("updateVerification", shard))
                .param("verificationId", verificationId).param("attempts", attempts).param("status", status.name())
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public Optional<Integer> activate(ShardId shard, long growerId, Instant now) {
        return jdbc.sql(statement("activate", shard)).param("growerId", growerId).param("now", Timestamp.from(now))
                .query(Integer.class).optional();
    }

    @Override
    public void insertConsent(ShardId shard, long growerId, Consent c) {
        jdbc.sql(statement("insertConsent", shard))
                .param("consentId", UUID.randomUUID()).param("growerId", growerId).param("purpose", c.purpose().name())
                .param("granted", c.granted()).param("noticeVersion", c.noticeVersion()).param("language", c.language())
                .param("channel", c.channel().name()).param("recordedByUserId", c.recordedByUserId())
                .param("now", Timestamp.from(c.recordedAt()))
                .update();
    }

    @Override
    public List<Consent> currentConsents(ShardId shard, long growerId) {
        return jdbc.sql(statement("currentConsents", shard)).param("growerId", growerId)
                .query((rs, n) -> new Consent(Purpose.valueOf(rs.getString("purpose")), rs.getBoolean("granted"),
                        rs.getString("notice_version"), rs.getString("language"), Channel.valueOf(rs.getString("channel")),
                        rs.getLong("recorded_by_user_id"), rs.getTimestamp("recorded_at").toInstant()))
                .list();
    }
}
