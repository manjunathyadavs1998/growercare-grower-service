-- =====================================================================================================
-- grower-service · SHARD TEMPLATE · V3 · contract: the per-shard grower_idempotency is replaced by
-- dir.grower_idempotency (common V3, SPEC-GRW-01 D9). No release ever wrote to it.
-- =====================================================================================================
SELECT pg_advisory_xact_lock(7240001);

DROP TABLE ${shard_schema}.grower_idempotency;
