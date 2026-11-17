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

-- SPEC-GRW-06 R300: the grower linked to a GROWER user.
-- name: growerOfUser
SELECT l.grower_id FROM dir.grower_user_link l WHERE l.user_id = :userId

-- SPEC-GRW-06 R306: the partner index follows registrations and preferred-partner changes.
-- name: indexPartner
INSERT INTO dir.partner_grower (partner_org_id, grower_id, country_code, relation)
VALUES (:partnerOrgId, :growerId, :countryCode, :relation)
ON CONFLICT (partner_org_id, grower_id, relation) DO NOTHING

-- name: unindexPartner
DELETE FROM dir.partner_grower WHERE partner_org_id = :partnerOrgId AND grower_id = :growerId AND relation = :relation

-- SPEC-GRW-06 R304: a partner's growers, newest first, one row per grower (it may be both ONBOARDED and PREFERRED).
-- name: partnerGrowers
SELECT p.grower_id, max(p.created_at) AS created_at
  FROM dir.partner_grower p
 WHERE p.partner_org_id = :partnerOrgId
 GROUP BY p.grower_id
HAVING CAST(:afterCreatedAt AS timestamptz) IS NULL
    OR (max(p.created_at), p.grower_id) < (CAST(:afterCreatedAt AS timestamptz), CAST(:afterGrowerId AS bigint))
 ORDER BY max(p.created_at) DESC, p.grower_id DESC
 LIMIT :limit
