-- =====================================================================================================
-- DEV/TEST ONLY · SHARD TEMPLATE: index the seed growers for their partners (SPEC-GRW-06 E307). On a fresh database
-- shard V6 runs before the seed growers exist (V1000), so the backfill is repeated here; idempotent either way.
-- =====================================================================================================
SELECT pg_advisory_xact_lock(7240001);

INSERT INTO dir.partner_grower (partner_org_id, grower_id, country_code, relation, created_at)
SELECT g.onboarded_via_org_id, g.grower_id, g.country_code, 'ONBOARDED', g.created_at
  FROM ${shard_schema}.grower g
 WHERE g.onboarded_via_org_id IS NOT NULL
UNION ALL
SELECT g.preferred_partner_org_id, g.grower_id, g.country_code, 'PREFERRED', g.created_at
  FROM ${shard_schema}.grower g
 WHERE g.preferred_partner_org_id IS NOT NULL
ON CONFLICT (partner_org_id, grower_id, relation) DO NOTHING;
