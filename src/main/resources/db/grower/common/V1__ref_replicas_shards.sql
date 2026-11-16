-- =====================================================================================================
-- grower-service · common · V1 · Read-only replicas, phone plans, shard map (SPEC-GRW-01 §4)
-- Owners: geography → control-plane · partner_org → identity-access. Seeded for now; synced by events later.
-- The runtime role only reads ref.* (enforced at the end).
-- Placeholders: ${app_role}
-- =====================================================================================================
GRANT USAGE ON SCHEMA ref TO ${app_role};

CREATE TABLE ref.region (
    region_code varchar(8)  PRIMARY KEY CHECK (region_code ~ '^[A-Z]{2,8}$'),
    name        varchar(80) NOT NULL
);

-- country_ix/case_no_cc/time_zone feed the shared CountryRegistry and the shard-migration placeholders.
-- calling_code + national_pattern = the country's phone plan (SPEC-GRW-01 D3; convention 4: data, not code).
CREATE TABLE ref.country (
    country_code     char(2)     PRIMARY KEY CHECK (country_code ~ '^[A-Z]{2}$'),
    region_code      varchar(8)  NOT NULL REFERENCES ref.region (region_code),
    name             varchar(80) NOT NULL,
    country_ix       smallint    NOT NULL UNIQUE CHECK (country_ix BETWEEN 0 AND 63),
    case_no_cc       smallint    NOT NULL UNIQUE CHECK (case_no_cc BETWEEN 10 AND 99),
    time_zone        varchar(64) NOT NULL,
    default_locale   varchar(16) NOT NULL,
    calling_code     varchar(3)  NOT NULL CHECK (calling_code ~ '^[1-9][0-9]{0,2}$'),
    national_pattern varchar(64) NOT NULL,
    active           boolean     NOT NULL DEFAULT true
);

CREATE TABLE ref.territory (
    territory_id        bigint       PRIMARY KEY,
    country_code        char(2)      NOT NULL REFERENCES ref.country (country_code),
    parent_territory_id bigint,
    territory_code      varchar(32)  NOT NULL,
    name                varchar(120) NOT NULL,
    territory_level     varchar(16)  NOT NULL CHECK (territory_level IN ('STATE', 'DISTRICT', 'SALES_AREA')),
    active              boolean      NOT NULL DEFAULT true,
    CONSTRAINT ref_territory_code_uq UNIQUE (country_code, territory_code),
    CONSTRAINT ref_territory_id_country_uq UNIQUE (territory_id, country_code),
    CONSTRAINT ref_territory_parent_fk FOREIGN KEY (parent_territory_id, country_code)
        REFERENCES ref.territory (territory_id, country_code),
    CONSTRAINT ref_territory_not_own_parent CHECK (parent_territory_id IS DISTINCT FROM territory_id)
);
CREATE INDEX ref_territory_parent_ix ON ref.territory (parent_territory_id);

CREATE TABLE ref.partner_org (
    partner_org_id        bigint       PRIMARY KEY,
    country_code          char(2)      NOT NULL REFERENCES ref.country (country_code),
    org_type              varchar(16)  NOT NULL CHECK (org_type IN ('DISTRIBUTOR', 'RETAILER')),
    parent_partner_org_id bigint       REFERENCES ref.partner_org (partner_org_id),
    legal_name            varchar(200) NOT NULL,
    status                varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT ref_partner_id_country_uq UNIQUE (partner_org_id, country_code)
);

-- Shard map: one row per logical shard schema. cluster_key names the physical cluster (one locally).
CREATE TABLE ref.shard (
    country_code char(2)     NOT NULL REFERENCES ref.country (country_code),
    shard_no     smallint    NOT NULL CHECK (shard_no BETWEEN 0 AND 63),
    schema_name  varchar(8)  NOT NULL UNIQUE CHECK (schema_name ~ '^[a-z]{2}_s[0-9]{2}$'),
    cluster_key  varchar(32) NOT NULL DEFAULT 'local',
    PRIMARY KEY (country_code, shard_no),
    CONSTRAINT shard_schema_matches CHECK (schema_name = lower(country_code) || '_s' || lpad(shard_no::text, 2, '0'))
);

-- ---------------------------------------------------------------- reference data (same master as case-service V3)
INSERT INTO ref.region (region_code, name) VALUES
    ('NA', 'North America'), ('LATAM', 'Latin America'), ('EAME', 'Europe, Africa, Middle East'), ('APAC', 'Asia Pacific');

INSERT INTO ref.country (country_code, region_code, name, country_ix, case_no_cc, time_zone, default_locale,
                         calling_code, national_pattern, active) VALUES
    ('IN', 'APAC',  'India',  46, 71, 'Asia/Kolkata',      'en-IN', '91', '[6-9][0-9]{9}',     true),
    ('BR', 'LATAM', 'Brazil',  8, 31, 'America/Sao_Paulo', 'pt-BR', '55', '[1-9][0-9]{9,10}',  true);

INSERT INTO ref.territory (territory_id, country_code, parent_territory_id, territory_code, name, territory_level) VALUES
    (1001, 'IN', NULL, 'MH',        'Maharashtra',     'STATE'),
    (1002, 'IN', 1001, 'MH-PUNE',   'Pune',            'DISTRICT'),
    (1003, 'IN', 1001, 'MH-NASHIK', 'Nashik',          'DISTRICT'),
    (1004, 'IN', NULL, 'KA',        'Karnataka',       'STATE'),
    (1005, 'IN', 1004, 'KA-BLR-R',  'Bengaluru Rural', 'DISTRICT'),
    (2001, 'BR', NULL, 'SP',        'São Paulo',       'STATE'),
    (2002, 'BR', 2001, 'SP-RP',     'Ribeirão Preto',  'DISTRICT'),
    (2003, 'BR', NULL, 'MT',        'Mato Grosso',     'STATE');

-- 64 logical shards per active country; the next migration run builds their schemas.
INSERT INTO ref.shard (country_code, shard_no, schema_name)
SELECT c.country_code, s, lower(c.country_code) || '_s' || lpad(s::text, 2, '0')
  FROM ref.country c CROSS JOIN generate_series(0, 63) AS s;

-- Runtime role: read-only on every ref table (masters change only through migrations / replication jobs).
GRANT SELECT ON ALL TABLES IN SCHEMA ref TO ${app_role};
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA ref FROM ${app_role};
