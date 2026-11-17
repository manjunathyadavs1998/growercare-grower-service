-- =====================================================================================================
-- grower-service · common · V5 · crop codes (SPEC-GRW-02 D4)
-- Phase 1 list, maintained here; later replaced by catalog-registry's crop master (labels are crop × pest).
-- =====================================================================================================

CREATE TABLE ref.crop (
    crop_code varchar(16) NOT NULL,
    name      varchar(60) NOT NULL,
    CONSTRAINT crop_pk PRIMARY KEY (crop_code),
    CONSTRAINT crop_code_check CHECK (crop_code ~ '^[A-Z][A-Z_]{1,15}$')
);

INSERT INTO ref.crop (crop_code, name) VALUES
    ('GRAPE', 'Grape'), ('ONION', 'Onion'), ('SOYBEAN', 'Soybean'), ('RAGI', 'Ragi (finger millet)'),
    ('PADDY', 'Paddy (rice)'), ('COTTON', 'Cotton'), ('MAIZE', 'Maize'), ('WHEAT', 'Wheat'),
    ('TOMATO', 'Tomato'), ('CHILLI', 'Chilli'), ('SUGARCANE', 'Sugarcane');

GRANT SELECT ON ref.crop TO ${app_role};
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ref.crop FROM ${app_role};
