-- grower-service: phone verification and consent (SPEC-GRW-03). {shard} = validated shard schema.
-- Statement ids: grower-consent.<name>

-- Serialises verification starts and activation per grower (R103 counts, R105 status change).
-- name: lockGrower
SELECT g.status, g.version, g.phone_enc, g.phone_key_id
  FROM {shard}.grower g
 WHERE g.grower_id = :growerId
   FOR UPDATE

-- R103: verifications started in the window, and whether one was exhausted in it.
-- name: recentStarts
SELECT count(*) FILTER (WHERE v.created_at > :since) AS started,
       count(*) FILTER (WHERE v.status = 'EXHAUSTED' AND v.updated_at > :since) AS exhausted
  FROM {shard}.grower_verification v
 WHERE v.grower_id = :growerId
   AND (v.created_at > :since OR v.updated_at > :since)

-- name: insertVerification
INSERT INTO {shard}.grower_verification (verification_id, grower_id, purpose, code_hmac, expires_at, created_at, updated_at)
VALUES (:verificationId, :growerId, :purpose, :codeHmac, :expiresAt, :now, :now)

-- name: lockVerification
SELECT v.verification_id, v.code_hmac, v.expires_at, v.attempts, v.status
  FROM {shard}.grower_verification v
 WHERE v.verification_id = :verificationId AND v.grower_id = :growerId
   FOR UPDATE

-- name: updateVerification
UPDATE {shard}.grower_verification
   SET attempts = :attempts, status = :status, updated_at = :now
 WHERE verification_id = :verificationId

-- R105: PENDING_CONSENT → ACTIVE, version + 1 (the grower row is locked by lockGrower).
-- name: activate
UPDATE {shard}.grower g
   SET status = 'ACTIVE', version = g.version + 1, updated_at = :now
 WHERE g.grower_id = :growerId AND g.status = 'PENDING_CONSENT'
RETURNING g.version

-- name: insertConsent
INSERT INTO {shard}.grower_consent (consent_id, grower_id, purpose, granted, notice_version, language, channel,
                                    recorded_by_user_id, recorded_at)
VALUES (:consentId, :growerId, :purpose, :granted, :noticeVersion, :language, :channel, :recordedByUserId, :now)

-- R120: the latest row per purpose.
-- name: currentConsents
SELECT DISTINCT ON (c.purpose) c.purpose, c.granted, c.notice_version, c.language, c.channel, c.recorded_by_user_id,
       c.recorded_at
  FROM {shard}.grower_consent c
 WHERE c.grower_id = :growerId
 ORDER BY c.purpose, c.recorded_at DESC, c.consent_id
