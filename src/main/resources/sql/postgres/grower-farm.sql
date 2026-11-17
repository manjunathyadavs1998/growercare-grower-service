-- grower-service: farms (SPEC-GRW-02). {shard} = validated shard schema. Statement ids: grower-farm.<name>

-- name: findFarm
SELECT f.farm_id, f.grower_id, f.name, f.area_ha, f.crops, f.latitude, f.longitude, f.status, f.version, f.created_at, f.updated_at
  FROM {shard}.farm f
 WHERE f.farm_id = :farmId AND f.grower_id = :growerId

-- R205: by creation; archived only on request.
-- name: listFarms
SELECT f.farm_id, f.grower_id, f.name, f.area_ha, f.crops, f.latitude, f.longitude, f.status, f.version, f.created_at, f.updated_at
  FROM {shard}.farm f
 WHERE f.grower_id = :growerId AND (:includeArchived OR f.status = 'ACTIVE')
 ORDER BY f.created_at, f.farm_id

-- R203 under the grower row lock (serialises concurrent creations for one grower).
-- name: lockGrowerStatus
SELECT g.status FROM {shard}.grower g WHERE g.grower_id = :growerId FOR UPDATE

-- name: countActive
SELECT count(*) FROM {shard}.farm f WHERE f.grower_id = :growerId AND f.status = 'ACTIVE'

-- name: insertFarm
INSERT INTO {shard}.farm (grower_id, name, area_ha, crops, latitude, longitude)
VALUES (:growerId, :name, :areaHa, :crops::varchar(16)[], :latitude, :longitude)
RETURNING farm_id

-- R208: optimistic lock; 0 rows ⇒ stale version.
-- name: updateFarm
UPDATE {shard}.farm f
   SET name = :name, area_ha = :areaHa, crops = :crops::varchar(16)[], latitude = :latitude, longitude = :longitude,
       version = f.version + 1, updated_at = now()
 WHERE f.farm_id = :farmId AND f.grower_id = :growerId AND f.version = :expectedVersion AND f.status = 'ACTIVE'
RETURNING f.version

-- name: archiveFarm
UPDATE {shard}.farm f
   SET status = 'ARCHIVED', version = f.version + 1, updated_at = now()
 WHERE f.farm_id = :farmId AND f.grower_id = :growerId AND f.version = :expectedVersion AND f.status = 'ACTIVE'
RETURNING f.version

-- name: findIdempotency
SELECT i.requested_by_user_id, i.request_hash, i.farm_id
  FROM {shard}.farm_idempotency i
 WHERE i.grower_id = :growerId AND i.idempotency_key = :key

-- name: insertIdempotency
INSERT INTO {shard}.farm_idempotency (grower_id, idempotency_key, requested_by_user_id, request_hash, farm_id)
VALUES (:growerId, :key, :userId, :hash, :farmId)
ON CONFLICT (grower_id, idempotency_key) DO NOTHING
RETURNING farm_id
