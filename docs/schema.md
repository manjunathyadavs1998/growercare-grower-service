# grower-service database schema

Database `gc_grower`, with the owner/app role split and sharded migrations as in the `sharded-migration` skill. The design comes from SPEC-GRW-01 §4 and D1/D3/D8; the schema rules are R50–R57. Keep this file in step with migrations (PR policy R25).

```
src/main/resources/db/grower/
├─ common/          V1 ref replicas + phone plans + shard map · V2 dir.grower_user_link, CDC heartbeat, publication
├─ dev-seed/        V1000 partner replicas, user links (local/tests only)
├─ shard/           V1 next_id(), grower, phone_directory, grower_idempotency, outbox · V2 outbox → publication
└─ shard-dev-seed/  V1000 the four fixture growers, each kept only in its own shard (local/tests only)
```

## 1. Roles
| Role | Rights |
|---|---|
| `gc_grower_owner` | Runs migrations (DDL). Production: only the pre-deploy migration Job has its credentials. |
| `gc_grower_app` | Runtime. `SELECT` on `ref.*`; `SELECT, INSERT` on `dir.grower_user_link`; `SELECT, INSERT, UPDATE` on `grower` and `phone_directory` (**no DELETE**); `SELECT, INSERT, DELETE` on `grower_idempotency` and `outbox` (retention jobs). |
| `gc_cdc` | Debezium. `SELECT` on each shard's `outbox`; read/write `msg.cdc_heartbeat`. Nothing else. |

**Important:** the databases give the app role full DML on every new table by default (`ALTER DEFAULT PRIVILEGES` in local-platform's `01-init.sh` and in `PostgresTestDatabase`). A restriction therefore has to be an explicit `REVOKE` in the migration; a narrower `GRANT` alone doesn't remove anything (found by GRW-E94).

## 2. `ref` (replicas, read-only)
| Table | Key | Notes |
|---|---|---|
| `region`, `country` | code | `country` also holds `country_ix`, `case_no_cc` and `time_zone` (shared `CountryRegistry`) and the **phone plan** `calling_code` + `national_pattern` (D3: country rules are data) |
| `territory` | `territory_id`; UNIQUE (`territory_id`, `country_code`) | Composite key so a grower's territory can be tied to the grower's country by FK |
| `partner_org` | `partner_org_id`; UNIQUE (`partner_org_id`, `country_code`) | Same pattern for preferred and onboarding partners |
| `shard` | (`country_code`, `shard_no`) | 64 rows per active country; `schema_name` = `<cc>_sNN` (CHECK) |

## 3. `dir.grower_user_link` (D8)
`user_id` PK, `grower_id` UNIQUE, `country_code`. Enforces "one grower per user" (R12) without a cross-shard scan, because a lookup by user doesn't route to a shard. It's written in the registering transaction, and the application never updates or deletes it.

**Limitation:** while a country has one database cluster, the link and the grower are written in one transaction. Once a country spans several clusters, this becomes insert-the-link-first plus a cleanup job for orphaned links.

## 4. Per shard `<cc>_sNN` (64 per country)
| Table | Key | Notes |
|---|---|---|
| `grower` | `grower_id` = `next_id()` (decodes to country + shard, R50) | `grower_country_check` pins the shard's country. Composite FKs to `ref.territory` and `ref.partner_org` (×2) keep territory and partners in the same country. `grower_onboarding_check`: SELF needs a user and no onboarder; ASSISTED needs the onboarding user. Phone stored only as `phone_hmac` (32 bytes), `phone_enc` + `phone_key_id`, and `phone_last4` (D3). `version` for `If-Match`. `user_id` UNIQUE. |
| `phone_directory` | `phone_hmac` | FK to `grower` **in the same shard**: the shard is chosen as `unsigned(first 8 bytes of phone_hmac) mod 64` (D1), so register and find-by-phone are single-shard. The PK enforces R13 under concurrency. |
| `grower_idempotency` | `client_request_id` | R16/R17. A retry carries the same phone, so it lands in the same shard. |
| `outbox` | `event_id` | ADR-012; in publication `gc_outbox` (shard V2). Payloads carry **ids only**. |

Indexes for the scope filters (R2/R3): `grower_territory_ix (territory_id, grower_id)`, plus partial indexes on `preferred_partner_org_id` and `onboarded_via_org_id`.

## 5. Dev seed
Four growers, G1–G4 (SPEC-GRW-01 §2). Their HMACs and ciphertexts use the **dev keys** in `.env.example`, and their ids are encoded for the D1 shard of each phone: IN 39, 30, 40 and BR 41. GRW-E96 checks the ids, the shards, the directory rows and decryption.
