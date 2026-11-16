-- =====================================================================================================
-- grower-service · SHARD TEMPLATE · V2 · stream this shard's outbox through Debezium (SPEC-PLAT-04 R52, R53; GRW-R55)
-- =====================================================================================================
-- Serialise shard DDL (required first statement of every shard migration); ALTER PUBLICATION also locks the
-- shared publication, so concurrent shards would otherwise contend.
SELECT pg_advisory_xact_lock(7240001);

ALTER PUBLICATION gc_outbox ADD TABLE ${shard_schema}.outbox;

GRANT USAGE ON SCHEMA ${shard_schema} TO ${cdc_role};
GRANT SELECT ON ${shard_schema}.outbox TO ${cdc_role};
