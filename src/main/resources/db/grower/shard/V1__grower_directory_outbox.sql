-- =====================================================================================================
-- grower-service · SHARD TEMPLATE · V1 · ID function, growers, phone directory, idempotency, outbox
-- Runs once per schema in ref.shard (ShardedFlywayMigrator). Placeholders:
--   ${shard_schema} e.g. in_s27 · ${country_code} IN · ${country_ix} 46 · ${shard_ix} 27 · ${app_role}
-- A grower and its directory row share a shard: shard = unsigned(first 8 bytes of phone_hmac) mod 64 (SPEC-GRW-01 D1).
-- =====================================================================================================
-- Serialise shard DDL: every shard references the shared ref.* tables, and concurrent FK DDL on them deadlocks.
-- Transaction-scoped, released at commit. Required first statement of every shard migration.
SELECT pg_advisory_xact_lock(7240001);

GRANT USAGE ON SCHEMA ${shard_schema} TO ${app_role};

-- ------------------------------------------------------------------ IDs (doc 04 §3; same layout as case_id)
CREATE SEQUENCE ${shard_schema}.id_seq;

CREATE FUNCTION ${shard_schema}.next_id() RETURNS bigint
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    our_epoch  CONSTANT bigint := 1767225600000;   -- 2026-01-01T00:00:00Z — equals IdCodec.EPOCH_MS
    country_ix CONSTANT bigint := ${country_ix};
    shard_ix   CONSTANT bigint := ${shard_ix};
    now_ms     bigint;
    seq        bigint;
BEGIN
    seq    := nextval('${shard_schema}.id_seq') % 1024;
    now_ms := floor(extract(epoch FROM clock_timestamp()) * 1000);
    RETURN ((now_ms - our_epoch) << 22) | (country_ix << 16) | (shard_ix << 10) | seq;
END $$;

-- ------------------------------------------------------------------ growers (GRW-1, R50, R52)
CREATE TABLE ${shard_schema}.grower (
    grower_id                bigint       NOT NULL DEFAULT ${shard_schema}.next_id(),
    country_code             char(2)      NOT NULL DEFAULT '${country_code}',
    territory_id             bigint       NOT NULL,
    user_id                  bigint,                               -- IAM user; NULL until an assisted grower signs in
    display_name             varchar(120) NOT NULL,
    phone_hmac               bytea        NOT NULL,                -- D3: blind index (HMAC-SHA-256)
    phone_enc                bytea        NOT NULL,                -- D3: AES-256-GCM nonce ‖ ciphertext ‖ tag
    phone_key_id             varchar(32)  NOT NULL,
    phone_last4              char(4)      NOT NULL,
    preferred_language       varchar(8)   NOT NULL,
    preferred_partner_org_id bigint,
    status                   varchar(16)  NOT NULL,
    onboarding_channel       varchar(8)   NOT NULL,
    onboarded_by_user_id     bigint,
    onboarded_via_org_id     bigint,
    version                  integer      NOT NULL DEFAULT 0,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT grower_pk PRIMARY KEY (grower_id),
    CONSTRAINT grower_user_uq UNIQUE (user_id),
    CONSTRAINT grower_country_check CHECK (country_code = '${country_code}'),
    CONSTRAINT grower_territory_fk FOREIGN KEY (territory_id, country_code) REFERENCES ref.territory (territory_id, country_code),
    CONSTRAINT grower_partner_fk FOREIGN KEY (preferred_partner_org_id, country_code) REFERENCES ref.partner_org (partner_org_id, country_code),
    CONSTRAINT grower_via_org_fk FOREIGN KEY (onboarded_via_org_id, country_code) REFERENCES ref.partner_org (partner_org_id, country_code),
    CONSTRAINT grower_display_name_check CHECK (btrim(display_name) <> ''),
    CONSTRAINT grower_phone_hmac_check CHECK (octet_length(phone_hmac) = 32),
    CONSTRAINT grower_phone_last4_check CHECK (phone_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT grower_language_check CHECK (preferred_language ~ '^[a-z]{2}(-[A-Z]{2})?$'),
    CONSTRAINT grower_status_check CHECK (status IN ('ACTIVE', 'PENDING_CONSENT', 'INACTIVE')),
    CONSTRAINT grower_version_check CHECK (version >= 0),
    -- SELF: the grower registered themself (a user, nobody onboarding). ASSISTED: someone onboarded them (R10/R11).
    CONSTRAINT grower_onboarding_check CHECK (
        (onboarding_channel = 'SELF' AND user_id IS NOT NULL AND onboarded_by_user_id IS NULL AND onboarded_via_org_id IS NULL)
        OR (onboarding_channel = 'ASSISTED' AND onboarded_by_user_id IS NOT NULL))
);
-- Scope filters (R2/R3): by territory, by preferred partner, by onboarding org.
CREATE INDEX grower_territory_ix ON ${shard_schema}.grower (territory_id, grower_id);
CREATE INDEX grower_partner_ix ON ${shard_schema}.grower (preferred_partner_org_id) WHERE preferred_partner_org_id IS NOT NULL;
CREATE INDEX grower_via_org_ix ON ${shard_schema}.grower (onboarded_via_org_id) WHERE onboarded_via_org_id IS NOT NULL;

-- ------------------------------------------------------------------ phone directory (R13, R30, R51)
CREATE TABLE ${shard_schema}.phone_directory (
    phone_hmac bytea  NOT NULL,
    grower_id  bigint NOT NULL,
    CONSTRAINT phone_directory_pk PRIMARY KEY (phone_hmac),
    CONSTRAINT phone_directory_grower_fk FOREIGN KEY (grower_id) REFERENCES ${shard_schema}.grower (grower_id),
    CONSTRAINT phone_directory_hmac_check CHECK (octet_length(phone_hmac) = 32)
);

-- ------------------------------------------------------------------ idempotent registration (R16/R17)
-- A retried request carries the same phone, so it lands in the same shard as the first attempt.
CREATE TABLE ${shard_schema}.grower_idempotency (
    client_request_id    uuid        PRIMARY KEY,
    requested_by_user_id bigint      NOT NULL,
    request_hash         bytea       NOT NULL CHECK (octet_length(request_hash) = 32),   -- SHA-256 of the canonical request
    grower_id            bigint      NOT NULL REFERENCES ${shard_schema}.grower (grower_id),
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX grower_idempotency_created_ix ON ${shard_schema}.grower_idempotency (created_at);

-- ------------------------------------------------------------------ outbox (ADR-012), ids only (no PII)
CREATE TABLE ${shard_schema}.outbox (
    event_id       uuid         PRIMARY KEY,
    aggregate_type varchar(64)  NOT NULL,
    aggregate_id   varchar(64)  NOT NULL,
    event_type     varchar(128) NOT NULL,
    payload        jsonb        NOT NULL,
    occurred_at    timestamptz  NOT NULL DEFAULT now(),
    trace_id       varchar(32)
);
CREATE INDEX outbox_occurred_ix ON ${shard_schema}.outbox (occurred_at);

-- ------------------------------------------------------------------ runtime role (R54): DML, no hard deletes
-- The databases grant the app role full DML on new tables by default (ALTER DEFAULT PRIVILEGES), so the
-- restriction must be an explicit REVOKE; a GRANT alone would not remove DELETE.
GRANT SELECT, INSERT, UPDATE ON ${shard_schema}.grower, ${shard_schema}.phone_directory TO ${app_role};
REVOKE DELETE, TRUNCATE ON ${shard_schema}.grower, ${shard_schema}.phone_directory FROM ${app_role};
GRANT SELECT, INSERT, DELETE ON ${shard_schema}.grower_idempotency TO ${app_role};   -- retention job deletes old keys
GRANT SELECT, INSERT, DELETE ON ${shard_schema}.outbox TO ${app_role};               -- housekeeping (deletes not streamed)
-- Idempotency records and events are append-only: no UPDATE, no TRUNCATE.
REVOKE UPDATE, TRUNCATE ON ${shard_schema}.grower_idempotency, ${shard_schema}.outbox FROM ${app_role};
GRANT USAGE ON SEQUENCE ${shard_schema}.id_seq TO ${app_role};
GRANT EXECUTE ON FUNCTION ${shard_schema}.next_id() TO ${app_role};
