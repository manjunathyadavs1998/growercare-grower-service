-- =====================================================================================================
-- DEV/TEST ONLY · SHARD TEMPLATE (gc.migration.dev-seed=true): the farms of SPEC-GRW-02 R221. They keep the ids
-- case-service seeds (80011–80014, SPEC-CASE-06 D6) so existing local cases and both services agree (D8).
-- Each row is kept only in its grower's shard (same filter as V1000).
-- =====================================================================================================
SELECT pg_advisory_xact_lock(7240001);

INSERT INTO ${shard_schema}.farm (farm_id, grower_id, name, area_ha, crops, latitude, longitude, created_at, updated_at)
SELECT f.farm_id, f.grower_id, f.name, f.area_ha, f.crops, f.latitude, f.longitude,
       TIMESTAMPTZ '2026-11-01T00:00:00Z', TIMESTAMPTZ '2026-11-01T00:00:00Z'
  FROM (VALUES
        ('IN', 39, 80011::bigint, 110165911145454593, 'Patil grape block A',   2.50, ARRAY['GRAPE']::varchar(16)[],   18.520430,  73.856743),
        ('IN', 30, 80012::bigint, 110165911145445378, 'Rao onion field',       1.20, ARRAY['ONION']::varchar(16)[],   19.997454,  73.789803),
        ('BR', 41, 80013::bigint, 110165911142966276, 'Fazenda Silva soy',   120.00, ARRAY['SOYBEAN']::varchar(16)[], -21.177600, -47.810300),
        ('IN', 40, 80014::bigint, 110165911145455619, 'Lakshmi ragi plot',     0.80, ARRAY['RAGI']::varchar(16)[],    13.284500,  77.607800)
       ) AS f (country_code, shard_no, farm_id, grower_id, name, area_ha, crops, latitude, longitude)
 WHERE f.country_code = '${country_code}' AND f.shard_no = ${shard_ix};
