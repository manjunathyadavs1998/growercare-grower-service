package com.growercare.grower.application.port;

import com.growercare.grower.domain.GrowerRecord;
import com.growercare.shard.ShardId;
import java.util.Optional;
import java.util.UUID;

/** Grower persistence on the shard schemas and the cross-shard user link (SPEC-GRW-01 §4). */
public interface GrowerRepository {

    /** What the registration writes (phone only as HMAC, ciphertext and last 4: D3). */
    record NewGrowerRow(long territoryId, Long userId, String displayName, byte[] phoneHmac, byte[] phoneEnc, String phoneKeyId,
                        String phoneLast4, String preferredLanguage, Long preferredPartnerOrgId, String status,
                        String onboardingChannel, Long onboardedByUserId, Long onboardedViaOrgId) { }

    record IdempotencyRecord(UUID key, long requestedByUserId, byte[] requestHash, long growerId) { }

    /** Thrown when a unique key rejects the write; {@link Kind} says which one (R12, R13). */
    final class DuplicateException extends RuntimeException {
        public enum Kind { PHONE, USER }

        private final Kind kind;

        public DuplicateException(Kind kind, Throwable cause) {
            super("duplicate " + kind, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    Optional<GrowerRecord> findById(ShardId shard, long growerId);

    /** R42: the record plus its phone HMAC, for the internal replica view only. */
    record Replica(GrowerRecord grower, byte[] phoneHmac) { }

    Optional<Replica> findReplica(ShardId shard, long growerId);

    /** The editable fields after an update (R23). */
    record ProfileRow(long territoryId, String displayName, String preferredLanguage, Long preferredPartnerOrgId) { }

    /** Optimistic lock: empty when {@code expectedVersion} is no longer current (R24); otherwise the new version. */
    Optional<Integer> updateProfile(ShardId shard, long growerId, int expectedVersion, ProfileRow row);

    Optional<GrowerRecord> findByPhone(ShardId shard, byte[] phoneHmac);

    long insert(ShardId shard, NewGrowerRow row);

    /** @throws DuplicateException (PHONE) when the phone is already in the directory */
    void insertDirectory(ShardId shard, byte[] phoneHmac, long growerId);

    boolean userLinked(long userId);

    /** @throws DuplicateException (USER) when the user already has a grower */
    void linkUser(long userId, long growerId, String countryCode);

    /** D9: keys live outside the shards (a reused key may carry another phone, hence another shard). */
    Optional<IdempotencyRecord> findIdempotency(UUID key);

    /** @return false when another request already used the key (the caller rolls back and replays) */
    boolean insertIdempotency(UUID key, long userId, byte[] hash, long growerId);

    /** SPEC-GRW-06 R300: the grower linked to a GROWER user. */
    Optional<Long> growerOfUser(long userId);

    /** SPEC-GRW-06 D3: who a partner is for a grower. */
    enum PartnerRelation { ONBOARDED, PREFERRED }

    void indexPartner(long partnerOrgId, long growerId, String countryCode, PartnerRelation relation);

    void unindexPartner(long partnerOrgId, long growerId, PartnerRelation relation);

    /** A partner index row: newest first; the keyset of the next page is (createdAt, growerId) of the last row. */
    record PartnerGrower(long growerId, java.time.Instant createdAt) { }

    java.util.List<PartnerGrower> partnerGrowers(long partnerOrgId, PartnerGrower after, int limit);
}
