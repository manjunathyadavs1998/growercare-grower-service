package com.growercare.grower.application.port;

import com.growercare.grower.domain.ConsentRules.Channel;
import com.growercare.grower.domain.ConsentRules.Purpose;
import com.growercare.grower.domain.ConsentRules.VerificationStatus;
import com.growercare.grower.domain.PhoneProtector;
import com.growercare.shard.ShardId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Phone verifications and consent records in the grower's shard (SPEC-GRW-03 §4). */
public interface ConsentRepository {

    /** The locked grower row: what verification needs (status, version, the sealed phone). */
    record LockedGrower(String status, int version, PhoneProtector.Sealed phone) { }

    record RecentStarts(int started, int exhausted) { }

    record Verification(UUID verificationId, byte[] codeHmac, Instant expiresAt, int attempts, VerificationStatus status) { }

    record Consent(Purpose purpose, boolean granted, String noticeVersion, String language, Channel channel,
                   long recordedByUserId, Instant recordedAt) { }

    /** SELECT … FOR UPDATE: serialises starts and activation per grower. */
    Optional<LockedGrower> lockGrower(ShardId shard, long growerId);

    RecentStarts recentStarts(ShardId shard, long growerId, Instant since);

    void insertVerification(ShardId shard, long growerId, UUID verificationId, String purpose, byte[] codeHmac,
                            Instant expiresAt, Instant now);

    /** SELECT … FOR UPDATE, only when the verification belongs to this grower (R110). */
    Optional<Verification> lockVerification(ShardId shard, long growerId, UUID verificationId);

    void updateVerification(ShardId shard, UUID verificationId, int attempts, VerificationStatus status, Instant now);

    /** @return the new version, or empty when the grower is no longer PENDING_CONSENT */
    Optional<Integer> activate(ShardId shard, long growerId, Instant now);

    void insertConsent(ShardId shard, long growerId, Consent consent);

    List<Consent> currentConsents(ShardId shard, long growerId);
}
