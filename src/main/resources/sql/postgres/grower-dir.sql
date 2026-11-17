-- Cross-shard directory (schema dir, SPEC-GRW-01 D8). Statement ids: grower-dir.<name>

-- name: userLinked
SELECT EXISTS (SELECT 1 FROM dir.grower_user_link WHERE user_id = :userId)

-- R12: the primary key on user_id rejects a second self-registration of the same user, also under concurrency.
-- name: linkUser
INSERT INTO dir.grower_user_link (user_id, grower_id, country_code) VALUES (:userId, :growerId, :countryCode)

-- D9: idempotency keys are global per country (the shard depends on the phone, which a reused key may change).
-- name: findIdempotency
SELECT client_request_id, requested_by_user_id, request_hash, grower_id
  FROM dir.grower_idempotency
 WHERE client_request_id = :key

-- name: insertIdempotency
INSERT INTO dir.grower_idempotency (client_request_id, requested_by_user_id, request_hash, grower_id)
VALUES (:key, :userId, :hash, :growerId)
ON CONFLICT (client_request_id) DO NOTHING
RETURNING client_request_id
