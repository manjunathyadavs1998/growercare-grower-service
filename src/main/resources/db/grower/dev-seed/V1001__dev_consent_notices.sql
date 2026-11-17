-- =====================================================================================================
-- DEV/TEST ONLY (gc.migration.dev-seed=true). Consent notice versions of the SPEC-GRW-03 R132 fixture:
-- IN in en, hi, mr and BR in pt, en, for every purpose. An outdated IN version (in-2025-01) proves R107.
-- =====================================================================================================
INSERT INTO ref.consent_notice (country_code, purpose, language, version, effective_from)
SELECT n.country_code, p.purpose, n.language, n.version, n.effective_from
  FROM (VALUES
        ('IN', 'en', 'in-2025-01', TIMESTAMPTZ '2025-01-01T00:00:00Z'),
        ('IN', 'en', 'in-2026-11', TIMESTAMPTZ '2026-11-01T00:00:00Z'),
        ('IN', 'hi', 'in-2025-01', TIMESTAMPTZ '2025-01-01T00:00:00Z'),
        ('IN', 'hi', 'in-2026-11', TIMESTAMPTZ '2026-11-01T00:00:00Z'),
        ('IN', 'mr', 'in-2025-01', TIMESTAMPTZ '2025-01-01T00:00:00Z'),
        ('IN', 'mr', 'in-2026-11', TIMESTAMPTZ '2026-11-01T00:00:00Z'),
        ('BR', 'pt', 'br-2026-11', TIMESTAMPTZ '2026-11-01T00:00:00Z'),
        ('BR', 'en', 'br-2026-11', TIMESTAMPTZ '2026-11-01T00:00:00Z')
       ) AS n (country_code, language, version, effective_from)
 CROSS JOIN (VALUES ('SERVICE'), ('DATA_SHARING_RETAILER'), ('MARKETING')) AS p (purpose);
