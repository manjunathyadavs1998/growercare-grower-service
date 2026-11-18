-- =====================================================================================================
-- grower-service · COMMON · V7 · audit of grower contact reads (SPEC-GRW-07 D2)
-- Every decryption of a phone number for a sender is recorded: which client, which grower, when. Never the number.
-- =====================================================================================================
CREATE TABLE dir.grower_contact_read (
    read_id    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    grower_id  bigint      NOT NULL,
    client_id  varchar(64) NOT NULL,
    read_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX grower_contact_read_grower_ix ON dir.grower_contact_read (grower_id, read_at DESC);

-- append-only for the runtime role (explicit REVOKE: default privileges grant more)
GRANT SELECT, INSERT ON dir.grower_contact_read TO ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON dir.grower_contact_read FROM ${app_role};
