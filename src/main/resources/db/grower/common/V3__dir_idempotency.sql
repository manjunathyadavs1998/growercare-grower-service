-- =====================================================================================================
-- grower-service · common · V3 · registration idempotency keys outside the shards (SPEC-GRW-01 D9)
-- Expand step: the per-shard grower_idempotency (shard V1) is dropped by shard V3 (nothing ever wrote it in a release).
-- =====================================================================================================

-- A key reused with another phone would hash to another shard; a single table per country sees every key (R17).
CREATE TABLE dir.grower_idempotency (
    client_request_id    uuid        NOT NULL,
    requested_by_user_id bigint      NOT NULL,
    request_hash         bytea       NOT NULL,
    grower_id            bigint      NOT NULL,      -- decodes to the grower's shard (no cross-shard FK)
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT grower_idempotency_pk PRIMARY KEY (client_request_id),
    CONSTRAINT grower_idempotency_hash_check CHECK (octet_length(request_hash) = 32)
);
CREATE INDEX grower_idempotency_created_ix ON dir.grower_idempotency (created_at);

-- R54: append-only for the runtime role; DELETE only for the retention job. Explicit REVOKE (default privileges).
GRANT SELECT, INSERT, DELETE ON dir.grower_idempotency TO ${app_role};
REVOKE UPDATE, TRUNCATE ON dir.grower_idempotency FROM ${app_role};
