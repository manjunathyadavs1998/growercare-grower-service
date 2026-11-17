-- =====================================================================================================
-- DEV/TEST ONLY · SHARD TEMPLATE (gc.migration.dev-seed=true): SERVICE consent of the active dev growers
-- (SPEC-GRW-03 R132). G1, G2 (IN) and G4 (BR) registered themselves; G3 stays PENDING_CONSENT without consent.
-- Each row is kept only in its own shard (same filter as V1000).
-- =====================================================================================================
SELECT pg_advisory_xact_lock(7240001);

INSERT INTO ${shard_schema}.grower_consent (consent_id, grower_id, purpose, granted, notice_version, language, channel,
                                            recorded_by_user_id, recorded_at)
SELECT c.consent_id, c.grower_id, 'SERVICE', true, c.notice_version, c.language, 'SELF', c.user_id,
       TIMESTAMPTZ '2026-11-01T00:00:00Z'
  FROM (VALUES
        ('IN', 39, '0c000000-0000-4000-8000-000000000001'::uuid, 110165911145454593, 'in-2026-11', 'mr', 1::bigint),
        ('IN', 30, '0c000000-0000-4000-8000-000000000002'::uuid, 110165911145445378, 'in-2026-11', 'mr', 2::bigint),
        ('BR', 41, '0c000000-0000-4000-8000-000000000004'::uuid, 110165911142966276, 'br-2026-11', 'pt', 3::bigint)
       ) AS c (country_code, shard_no, consent_id, grower_id, notice_version, language, user_id)
 WHERE c.country_code = '${country_code}' AND c.shard_no = ${shard_ix};
