-- =====================================================================================================
-- DEV/TEST ONLY · SHARD TEMPLATE (gc.migration.dev-seed=true): the four growers of the SPEC-GRW-01 §2 fixture.
-- Applied to every shard; each row is kept only in the shard it belongs to (filter on country + shard number),
-- so the template never names a schema. Ids are encoded for the D1 shard of each phone (IdCodec,
-- 2026-11-01T00:00Z, sequence 1-4). HMACs and ciphertexts use the DEV keys in .env.example (key id dev-1).
-- Phones: G1 +919876543210 · G2 +919822337788 · G3 +919845055432 · G4 +5516991234455 (GRW-E96 checks all of this).
-- =====================================================================================================
SELECT pg_advisory_xact_lock(7240001);

WITH dev_grower AS (
SELECT * FROM (VALUES
    ('IN', 39, 110165911145454593, 1002, 1::bigint,    'Ramesh Patil',                  '9c1fd28b1659c0e76e881c2f67b3d46f4c6d0ec8b4ad63cf71a478e1f80fada5',
        'f6268deae49a0cc48a1da114899981e9a5ff71e70463c8e474c59c413512dee4a90d456596cf37c214', '3210', 'mr', 501::bigint, 'ACTIVE',          'SELF',     NULL::bigint, NULL::bigint),
    ('IN', 30, 110165911145445378, 1003, 2::bigint,    'Sunita Rao',                    '0e51d8ac07189a1eebdb9c057b4a32593d272c75f624850d8dbe81f278eaed3c',
        '8ca27136b9e38045af7ebcaa9970552a1ad3f44fb51c6571c82bcb63af09c6ab5d60bb0f0c564059a3', '7788', 'mr', 502::bigint, 'ACTIVE',          'SELF',     NULL::bigint, NULL::bigint),
    ('IN', 40, 110165911145455619, 1005, NULL::bigint, 'Lakshmi (assisted onboarding)', '6752a1f363c58ae8ada7f9809ed57a7d35a64e3241d07dfef7ea164939820d7b',
        '1ccfedc0d72d097e423bb6d6bea8b386cfd6c1ad0a089edec18ffc1dfffd2203e2d9cd577a8a9205c0', '5432', 'kn', NULL::bigint, 'PENDING_CONSENT', 'ASSISTED', 202::bigint, 502::bigint),
    ('BR', 41, 110165911142966276, 2002, 3::bigint,    'Joao Silva',                    '585e5ab1ed1b18e93ee25888f65e56422a0454288732edf2257e0528a70f2bb6',
        '0d47ef6f04b446f8db0a716a203fd4314593f14a88e2421d6d0d3ba1e30e4a3f78f19d1fe1059d788d1a', '4455', 'pt', NULL::bigint, 'ACTIVE',          'SELF',     NULL::bigint, NULL::bigint)
) AS g (country_code, shard_no, grower_id, territory_id, user_id, display_name, phone_hmac, phone_enc, phone_last4,
        preferred_language, preferred_partner_org_id, status, onboarding_channel, onboarded_by_user_id, onboarded_via_org_id)
WHERE g.country_code = '${country_code}' AND g.shard_no = ${shard_ix}
),
-- G3 was onboarded by retailer2.in (IAM user 202) on behalf of partner org 502.
seeded AS (
INSERT INTO ${shard_schema}.grower (grower_id, country_code, territory_id, user_id, display_name, phone_hmac, phone_enc,
                                    phone_key_id, phone_last4, preferred_language, preferred_partner_org_id, status,
                                    onboarding_channel, onboarded_by_user_id, onboarded_via_org_id, created_at, updated_at)
SELECT grower_id, country_code, territory_id, user_id, display_name, decode(phone_hmac, 'hex'), decode(phone_enc, 'hex'),
       'dev-1', phone_last4, preferred_language, preferred_partner_org_id, status,
       onboarding_channel, onboarded_by_user_id, onboarded_via_org_id, TIMESTAMPTZ '2026-11-01T00:00:00Z', TIMESTAMPTZ '2026-11-01T00:00:00Z'
  FROM dev_grower
RETURNING phone_hmac, grower_id
)
INSERT INTO ${shard_schema}.phone_directory (phone_hmac, grower_id)
SELECT phone_hmac, grower_id FROM seeded;
