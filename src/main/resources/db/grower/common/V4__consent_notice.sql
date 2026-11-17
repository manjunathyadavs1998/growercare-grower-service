-- =====================================================================================================
-- grower-service · common · V4 · consent notice versions (SPEC-GRW-03 D8)
-- Which notice text (version) is current per country, purpose and language. Rows come from the compliance team's
-- release process (a migration or the reference-data loader); the dev seed adds the local ones (dev-seed V1001).
-- =====================================================================================================

CREATE TABLE ref.consent_notice (
    country_code    char(2)     NOT NULL REFERENCES ref.country (country_code),
    purpose         varchar(32) NOT NULL,
    language        varchar(8)  NOT NULL,
    version         varchar(32) NOT NULL,
    effective_from  timestamptz NOT NULL,
    CONSTRAINT consent_notice_pk PRIMARY KEY (country_code, purpose, language, version),
    CONSTRAINT consent_notice_purpose_check CHECK (purpose IN ('SERVICE', 'DATA_SHARING_RETAILER', 'MARKETING')),
    CONSTRAINT consent_notice_language_check CHECK (language ~ '^[a-z]{2}(-[A-Z]{2})?$')
);
-- The current version: the latest effective_from not in the future (D8).
CREATE INDEX consent_notice_current_ix ON ref.consent_notice (country_code, purpose, language, effective_from DESC);

GRANT SELECT ON ref.consent_notice TO ${app_role};
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ref.consent_notice FROM ${app_role};
