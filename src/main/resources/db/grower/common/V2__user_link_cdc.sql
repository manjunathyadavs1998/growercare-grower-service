-- =====================================================================================================
-- grower-service · common · V2 · user → grower link (SPEC-GRW-01 D8, R12), CDC heartbeat and outbox publication
-- =====================================================================================================

-- D8: "one grower per user" needs a lookup by user id, which does not route to a shard. Written in the registering
-- transaction (one cluster per country). Across clusters: insert the link first, clean up orphans in a job.
CREATE SCHEMA IF NOT EXISTS dir;
GRANT USAGE ON SCHEMA dir TO ${app_role};

CREATE TABLE dir.grower_user_link (
    user_id      bigint      NOT NULL,
    grower_id    bigint      NOT NULL,
    country_code char(2)     NOT NULL REFERENCES ref.country (country_code),
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT grower_user_link_pk PRIMARY KEY (user_id),
    CONSTRAINT grower_user_link_grower_uq UNIQUE (grower_id)
);
-- R54: links are written once; never changed or removed by the application. Explicit REVOKE because the databases
-- grant the app role full DML on new tables by default (ALTER DEFAULT PRIVILEGES).
GRANT SELECT, INSERT ON dir.grower_user_link TO ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON dir.grower_user_link FROM ${app_role};

-- SPEC-PLAT-04 R62: Debezium upserts this single row every 10 s so the replication slot advances without events.
CREATE SCHEMA IF NOT EXISTS msg;
CREATE TABLE msg.cdc_heartbeat (
    id      smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    beat_at timestamptz NOT NULL
);
REVOKE ALL ON msg.cdc_heartbeat FROM ${app_role};

-- R52: every shard migration adds its outbox (shard V2). Insert/update only: housekeeping deletes are not streamed.
CREATE PUBLICATION gc_outbox FOR TABLE msg.cdc_heartbeat WITH (publish = 'insert, update');

-- R53: the connector role may touch the heartbeat only (shard V2 grants SELECT on each outbox).
GRANT USAGE ON SCHEMA msg TO ${cdc_role};
GRANT SELECT, INSERT, UPDATE ON msg.cdc_heartbeat TO ${cdc_role};
