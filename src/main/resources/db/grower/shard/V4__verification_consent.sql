-- =====================================================================================================
-- grower-service · SHARD TEMPLATE · V4 · phone verification (OTP) and consent records (SPEC-GRW-03 R130)
-- Both live in the grower's shard and are only reached through the grower id (which routes).
-- =====================================================================================================
SELECT pg_advisory_xact_lock(7240001);
SET LOCAL lock_timeout = '3s';

-- D3/D4: the code only as HMAC-SHA-256 (phone HMAC key, "otp:" ‖ verification id ‖ code); 5 minutes, 5 attempts.
CREATE TABLE ${shard_schema}.grower_verification (
    verification_id uuid         NOT NULL,
    grower_id       bigint       NOT NULL,
    purpose         varchar(16)  NOT NULL,
    code_hmac       bytea        NOT NULL,
    expires_at      timestamptz  NOT NULL,
    attempts        smallint     NOT NULL DEFAULT 0,
    status          varchar(16)  NOT NULL DEFAULT 'OPEN',
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT grower_verification_pk PRIMARY KEY (verification_id),
    CONSTRAINT grower_verification_grower_fk FOREIGN KEY (grower_id) REFERENCES ${shard_schema}.grower (grower_id),
    CONSTRAINT grower_verification_purpose_check CHECK (purpose IN ('ACTIVATION')),
    CONSTRAINT grower_verification_hmac_check CHECK (octet_length(code_hmac) = 32),
    CONSTRAINT grower_verification_attempts_check CHECK (attempts BETWEEN 0 AND 5),
    CONSTRAINT grower_verification_status_check CHECK (status IN ('OPEN', 'CONSUMED', 'EXHAUSTED'))
);
-- R103: verifications of a grower in the last 15 minutes.
CREATE INDEX grower_verification_recent_ix ON ${shard_schema}.grower_verification (grower_id, created_at);

-- D5: append-only; the current consent is the latest row per purpose.
CREATE TABLE ${shard_schema}.grower_consent (
    consent_id          uuid         NOT NULL,
    grower_id           bigint       NOT NULL,
    purpose             varchar(32)  NOT NULL,
    granted             boolean      NOT NULL,
    notice_version      varchar(32)  NOT NULL,
    language            varchar(8)   NOT NULL,
    channel             varchar(8)   NOT NULL,
    recorded_by_user_id bigint       NOT NULL,
    recorded_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT grower_consent_pk PRIMARY KEY (consent_id),
    CONSTRAINT grower_consent_grower_fk FOREIGN KEY (grower_id) REFERENCES ${shard_schema}.grower (grower_id),
    CONSTRAINT grower_consent_purpose_check CHECK (purpose IN ('SERVICE', 'DATA_SHARING_RETAILER', 'MARKETING')),
    CONSTRAINT grower_consent_channel_check CHECK (channel IN ('SELF', 'ASSISTED')),
    CONSTRAINT grower_consent_language_check CHECK (language ~ '^[a-z]{2}(-[A-Z]{2})?$')
);
CREATE INDEX grower_consent_current_ix ON ${shard_schema}.grower_consent (grower_id, purpose, recorded_at DESC);

-- Runtime role: verifications are updated (attempts, status) but never deleted; consents are append-only.
-- Explicit REVOKEs: the databases grant full DML on new tables by default.
GRANT SELECT, INSERT, UPDATE ON ${shard_schema}.grower_verification TO ${app_role};
REVOKE DELETE, TRUNCATE ON ${shard_schema}.grower_verification FROM ${app_role};
GRANT SELECT, INSERT ON ${shard_schema}.grower_consent TO ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON ${shard_schema}.grower_consent FROM ${app_role};
