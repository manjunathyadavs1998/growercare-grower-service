-- =====================================================================================================
-- grower-service · SHARD TEMPLATE · V5 · farms (SPEC-GRW-02 R220)
-- A farm lives in its grower's shard (D1); its id comes from this shard's next_id(), so it is globally unique.
-- =====================================================================================================
SELECT pg_advisory_xact_lock(7240001);
SET LOCAL lock_timeout = '3s';

CREATE TABLE ${shard_schema}.farm (
    farm_id     bigint        NOT NULL DEFAULT ${shard_schema}.next_id(),
    grower_id   bigint        NOT NULL,
    name        varchar(120)  NOT NULL,
    area_ha     numeric(10,2) NOT NULL,
    crops       varchar(16)[] NOT NULL,
    latitude    numeric(9,6),
    longitude   numeric(9,6),
    status      varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    version     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT farm_pk PRIMARY KEY (farm_id),
    CONSTRAINT farm_grower_fk FOREIGN KEY (grower_id) REFERENCES ${shard_schema}.grower (grower_id),
    CONSTRAINT farm_grower_uq UNIQUE (farm_id, grower_id),
    CONSTRAINT farm_name_check CHECK (btrim(name) <> ''),
    CONSTRAINT farm_area_check CHECK (area_ha > 0 AND area_ha <= 100000),
    CONSTRAINT farm_crops_check CHECK (cardinality(crops) BETWEEN 1 AND 5),
    CONSTRAINT farm_latitude_check CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT farm_longitude_check CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT farm_point_check CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CONSTRAINT farm_status_check CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT farm_version_check CHECK (version >= 0)
);
CREATE INDEX farm_grower_ix ON ${shard_schema}.farm (grower_id, created_at);

-- R201: idempotent creation; the key is per grower (a retry carries the same grower id, so the same shard).
CREATE TABLE ${shard_schema}.farm_idempotency (
    grower_id            bigint      NOT NULL,
    idempotency_key      uuid        NOT NULL,
    requested_by_user_id bigint      NOT NULL,
    request_hash         bytea       NOT NULL,
    farm_id              bigint      NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT farm_idempotency_pk PRIMARY KEY (grower_id, idempotency_key),
    CONSTRAINT farm_idempotency_farm_fk FOREIGN KEY (farm_id, grower_id) REFERENCES ${shard_schema}.farm (farm_id, grower_id),
    CONSTRAINT farm_idempotency_hash_check CHECK (octet_length(request_hash) = 32)
);
CREATE INDEX farm_idempotency_created_ix ON ${shard_schema}.farm_idempotency (created_at);

-- Runtime role (D5: no hard delete). Explicit REVOKEs: the databases grant full DML on new tables by default.
GRANT SELECT, INSERT, UPDATE ON ${shard_schema}.farm TO ${app_role};
REVOKE DELETE, TRUNCATE ON ${shard_schema}.farm FROM ${app_role};
GRANT SELECT, INSERT, DELETE ON ${shard_schema}.farm_idempotency TO ${app_role};   -- retention job deletes old keys
REVOKE UPDATE, TRUNCATE ON ${shard_schema}.farm_idempotency FROM ${app_role};
