package com.growercare.grower.application.port;

import com.growercare.grower.domain.FarmRecord;
import com.growercare.grower.domain.Point;
import com.growercare.shard.ShardId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Farms in the grower's shard (SPEC-GRW-02 §4). */
public interface FarmRepository {

    /** The editable fields of a farm. */
    record FarmRow(String name, BigDecimal areaHa, List<String> crops, Point location) { }

    record IdempotencyRecord(long requestedByUserId, byte[] requestHash, long farmId) { }

    Optional<FarmRecord> find(ShardId shard, long growerId, long farmId);

    List<FarmRecord> list(ShardId shard, long growerId, boolean includeArchived);

    /** SELECT … FOR UPDATE on the grower row; empty when the grower is gone. */
    Optional<String> lockGrowerStatus(ShardId shard, long growerId);

    int countActive(ShardId shard, long growerId);

    long insert(ShardId shard, long growerId, FarmRow row);

    /** @return the new version, or empty when the version is stale or the farm is archived */
    Optional<Integer> update(ShardId shard, long growerId, long farmId, int expectedVersion, FarmRow row);

    Optional<Integer> archive(ShardId shard, long growerId, long farmId, int expectedVersion);

    Optional<IdempotencyRecord> findIdempotency(ShardId shard, long growerId, UUID key);

    /** @return false when another request already used the key */
    boolean insertIdempotency(ShardId shard, long growerId, UUID key, long userId, byte[] hash, long farmId);
}
