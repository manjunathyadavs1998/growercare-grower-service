-- =====================================================================================================
-- grower-service · common · V6 · notice texts and the partner → grower index (SPEC-GRW-06 D2, D3, R307)
-- Expand only: nullable columns and a new table. Existing growers are indexed by shard V6 (and the dev seed's V1003).
-- =====================================================================================================

-- D2: the text the grower agrees to, next to its version. Written and approved by legal per country and language.
ALTER TABLE ref.consent_notice ADD COLUMN title varchar(200);
ALTER TABLE ref.consent_notice ADD COLUMN body text;
ALTER TABLE ref.consent_notice ADD CONSTRAINT consent_notice_body_check CHECK (body IS NULL OR char_length(body) <= 8000);

-- D3: which growers a partner onboarded or is preferred by, without scanning 64 shards per page.
CREATE TABLE dir.partner_grower (
    partner_org_id bigint      NOT NULL,
    grower_id      bigint      NOT NULL,      -- decodes to the grower's shard (no cross-shard FK)
    country_code   char(2)     NOT NULL,
    relation       varchar(16) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT partner_grower_pk PRIMARY KEY (partner_org_id, grower_id, relation),
    CONSTRAINT partner_grower_relation_check CHECK (relation IN ('ONBOARDED', 'PREFERRED'))
);
CREATE INDEX partner_grower_page_ix ON dir.partner_grower (partner_org_id, created_at DESC, grower_id DESC);

-- Runtime role: rows follow registrations and preferred-partner changes (DELETE for the latter); never UPDATE.
GRANT SELECT, INSERT, DELETE ON dir.partner_grower TO ${app_role};
REVOKE UPDATE, TRUNCATE ON dir.partner_grower FROM ${app_role};
