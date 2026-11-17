-- =====================================================================================================
-- grower-service · SHARD TEMPLATE · V6 · backfill dir.partner_grower from this shard's growers (SPEC-GRW-06 R307)
-- Idempotent: rows that already exist are kept. From here on, registrations and preferred-partner changes keep the
-- index current in their own transactions (R306).
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
