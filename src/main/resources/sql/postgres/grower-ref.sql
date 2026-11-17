-- Reference data (schema ref, read-only replicas). Statement ids: grower-ref.<name>

-- name: territory
SELECT t.territory_id, t.country_code, t.active
  FROM ref.territory t
  JOIN ref.country c ON c.country_code = t.country_code
 WHERE t.territory_id = :territoryId AND c.active

-- D3: the country's phone plan (convention 4: country rules are data).
-- name: phonePlan
SELECT calling_code, national_pattern FROM ref.country WHERE country_code = :countryCode AND active

-- name: partner
SELECT partner_org_id, country_code, status FROM ref.partner_org WHERE partner_org_id = :partnerOrgId

-- SPEC-GRW-03 D8: the current notice version for a country, purpose and language.
-- name: currentNotice
SELECT n.version
  FROM ref.consent_notice n
 WHERE n.country_code = :countryCode AND n.purpose = :purpose AND n.language = :language AND n.effective_from <= :now
 ORDER BY n.effective_from DESC
 LIMIT 1

-- SPEC-GRW-02 D4: which crop codes exist.
-- name: knownCrops
SELECT c.crop_code FROM ref.crop c WHERE c.crop_code = ANY(:codes::varchar[])

-- SPEC-GRW-06 R302: the current notice per purpose for a country and language, with its text.
-- name: currentNotices
SELECT DISTINCT ON (n.purpose) n.purpose, n.version, n.title, n.body
  FROM ref.consent_notice n
 WHERE n.country_code = :countryCode AND n.language = :language AND n.effective_from <= :now
 ORDER BY n.purpose, n.effective_from DESC
