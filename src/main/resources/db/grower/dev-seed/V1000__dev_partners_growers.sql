-- =====================================================================================================
-- DEV/TEST ONLY (gc.migration.dev-seed=true). Partner replicas and the user → grower links of the SPEC-GRW-01 §2
-- fixture (D6). Partner and user ids match identity-access-service's and case-service's dev seeds.
-- The grower rows themselves live in shard schemas, which do not exist yet when this runs: they are seeded by the
-- shard dev-seed template db/grower/shard-dev-seed/V1000.
-- =====================================================================================================

INSERT INTO ref.partner_org (partner_org_id, country_code, org_type, parent_partner_org_id, legal_name, status) VALUES
    (503, 'IN', 'DISTRIBUTOR', NULL, 'Deccan Agri Distributors Pvt Ltd', 'ACTIVE'),
    (501, 'IN', 'RETAILER',    503,  'Patil Agro Store',                 'ACTIVE'),
    (502, 'IN', 'RETAILER',    NULL, 'Krishi Kendra Nashik',             'ACTIVE'),
    (504, 'BR', 'RETAILER',    NULL, 'Agro Ribeirao Insumos Ltda',       'ACTIVE');

-- D8: the three self-registered growers are linked to their IAM users (grower.in1 = 1, grower.in2 = 2, grower.br1 = 3).
INSERT INTO dir.grower_user_link (user_id, grower_id, country_code) VALUES
    (1, 110165911145454593, 'IN'),
    (2, 110165911145445378, 'IN'),
    (3, 110165911142966276, 'BR');
