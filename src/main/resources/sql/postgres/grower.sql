-- grower-service shard statements (ADR-013). {shard} = validated shard schema. Statement ids: grower.<name>
-- Every query aliases the grower table as g (GrowerAccessScope columns).

-- name: findById
SELECT g.grower_id, g.country_code, g.territory_id, g.user_id, g.display_name, g.phone_last4, g.preferred_language,
       g.preferred_partner_org_id, g.status, g.onboarding_channel, g.onboarded_by_user_id, g.onboarded_via_org_id,
       g.version, g.created_at, g.updated_at
  FROM {shard}.grower g
 WHERE g.grower_id = :growerId

-- R42: the replica view adds the phone HMAC (never the ciphertext).
-- name: findReplica
SELECT g.grower_id, g.country_code, g.territory_id, g.user_id, g.display_name, g.phone_last4, g.preferred_language,
       g.preferred_partner_org_id, g.status, g.onboarding_channel, g.onboarded_by_user_id, g.onboarded_via_org_id,
       g.version, g.created_at, g.updated_at, g.phone_hmac
  FROM {shard}.grower g
 WHERE g.grower_id = :growerId

-- R23/R24: optimistic lock; 0 rows ⇒ stale version.
-- name: updateProfile
UPDATE {shard}.grower g
   SET territory_id = :territoryId, display_name = :displayName, preferred_language = :preferredLanguage,
       preferred_partner_org_id = :preferredPartnerOrgId, version = g.version + 1, updated_at = now()
 WHERE g.grower_id = :growerId AND g.version = :expectedVersion
RETURNING g.version

-- SPEC-GRW-01 R30: the directory row lives in the grower's shard (D1), so this is one single-shard join.
-- name: findByPhone
SELECT g.grower_id, g.country_code, g.territory_id, g.user_id, g.display_name, g.phone_last4, g.preferred_language,
       g.preferred_partner_org_id, g.status, g.onboarding_channel, g.onboarded_by_user_id, g.onboarded_via_org_id,
       g.version, g.created_at, g.updated_at
  FROM {shard}.phone_directory d
  JOIN {shard}.grower g ON g.grower_id = d.grower_id
 WHERE d.phone_hmac = :phoneHmac

-- name: insert
INSERT INTO {shard}.grower (territory_id, user_id, display_name, phone_hmac, phone_enc, phone_key_id, phone_last4,
                            preferred_language, preferred_partner_org_id, status, onboarding_channel,
                            onboarded_by_user_id, onboarded_via_org_id)
VALUES (:territoryId, :userId, :displayName, :phoneHmac, :phoneEnc, :phoneKeyId, :phoneLast4,
        :preferredLanguage, :preferredPartnerOrgId, :status, :onboardingChannel,
        :onboardedByUserId, :onboardedViaOrgId)
RETURNING grower_id

-- R13: the primary key on phone_hmac rejects a second grower with the same phone, also under concurrency.
-- name: insertDirectory
INSERT INTO {shard}.phone_directory (phone_hmac, grower_id) VALUES (:phoneHmac, :growerId)

-- SPEC-GRW-06 R304: a page of a partner's growers, one query per shard.
-- name: findByIds
SELECT g.grower_id, g.country_code, g.territory_id, g.user_id, g.display_name, g.phone_last4, g.preferred_language,
       g.preferred_partner_org_id, g.status, g.onboarding_channel, g.onboarded_by_user_id, g.onboarded_via_org_id,
       g.version, g.created_at, g.updated_at
  FROM {shard}.grower g
 WHERE g.grower_id = ANY(:growerIds)
