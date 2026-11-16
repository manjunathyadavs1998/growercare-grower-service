# SPEC-GRW-01: Grower registry (register, read, update, find by phone)
- **Requirements:** GRW-1 (grower profile part), IAM-1 (assisted onboarding), ACC-1, ACC-3, ACC-11, PRV-5, ID-1/ID-3/ID-4/ID-8, convention 1–5
- **Status:** Agreed (2026-11-16). Implemented: increment 0 (phone normalisation and protection, D3, E80–E85), increment 1 (schema, R50–R57, E90–E97)
- **Owner:** grower-service (new repository `growercare-grower-service`, port 8083)
- **Follow-ups (separate specs):** SPEC-GRW-02 farms/plots and crops · SPEC-GRW-03 consents + OTP confirmation of assisted onboarding (PRV-1) · SPEC-GRW-04 audited phone unmask (ACC-3) · SPEC-CASE-06 case-service replica sync from `grower.*` events

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **Placement:** a new grower is written to shard `unsigned(first 8 bytes of phone_hmac) mod 64` of its country (an HMAC is already uniformly distributed, so no extra mixing is needed), and `grower_id` comes from that shard's `next_id()` (same 64-bit layout as `case_id`). Reads by id **decode** the shard from the id (`ShardRouter.forId`), with no lookup | Grower row and its phone-directory row land in the **same shard**, so register and find-by-phone are single-shard transactions. The id is unknown before the insert, so `hash(grower_id)` cannot place the grower row itself |
| D2 | **Cases stay where they are:** case-service keeps routing a grower's cases by `fmix64(grower_id) mod 64` (ADR-002). The grower's own row and its cases may sit in different shards; each service owns its own database, so that's fine | No change to case-service; a grower's cases stay co-located |
| D3 | **Phone handling:** normalised to E.164 using the country's **phone plan from reference data** (`ref.country.calling_code` + `national_pattern`, e.g. IN `91` / `[6-9][0-9]{9}`, BR `55` / `[1-9][0-9]{9,10}`; convention 4, no country `if`s): spaces, dashes, brackets and a leading trunk `0` are removed; `+<cc>…` must carry the territory's own calling code. Stored as `phone_hmac` (HMAC-SHA-256, key `GC_PHONE_HMAC_KEY`) for lookup, `phone_enc` (AES-256-GCM, key `GC_PHONE_ENC_KEY` + `phone_key_id` for rotation) for a later unmask, and `phone_last4` for display. Plain phone numbers are never stored, logged, put in events or **put in URLs** | PRV-5; HMAC because a plain hash of a 10-digit number is brute-forceable |
| D4 | **Country is never taken from the client.** The grower's country is the country of `territoryId` (validated against the `ref.territory` replica); the caller must have that territory in scope | Convention 1 |
| D5 | **Phone is immutable in this spec.** Changing a number moves the directory entry across shards and needs OTP proof of the new number; that flow is SPEC-GRW-03 | Keeps every write in SPEC-GRW-01 single-shard |
| D6 | **Dev seed:** growers get properly encoded ids (country IN = 46 / BR, the shard from D1). case-service keeps its own seeded replica (70001…) for its tests until SPEC-CASE-06 syncs the replica from events; `smoke.sh` then registers a grower here before raising a case | Seeded 70001-style ids do not decode to a shard |
| D8 | **User link outside the shards:** "one grower per user" (R12) needs a lookup by `userId`, which does not route to a shard. `dir.grower_user_link(user_id PK, grower_id UNIQUE)` is written in the registering transaction; the app role may only SELECT and INSERT it. With one cluster per country this is one transaction; across clusters it becomes insert-link-first with a cleanup job (noted in `docs/schema.md`) | Keeps R12 race-free without a cross-shard scan |
| D7 | **New permissions** `grower:create`, `grower:read`, `grower:update`, added to IAM roles by a migration in identity-access-service (same PR series) | Authorization stays in IAM grants (ACC-1) |

## 1. Behaviour (rules)
### Who sees which grower (`grower:read`)
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R1 | GROWER (scope OWN) | reads growers | only the grower linked to their own `userId` |
| GRW-R2 | partner (scope ORG) | reads growers | only growers whose `preferredPartnerOrgId` **or** `onboardedViaOrgId` is their org (ACC-11) |
| GRW-R3 | TERRITORY / COUNTRY / REGION scope; ASSIGNED scope uses its territory | reads growers | growers whose territory is in the expanded geo scope. **BU scope does not apply** (growers are not BU-specific) |
| GRW-R4 | GLOBAL scope, expired grant, or no `grower:read` | reads growers | nothing: object reads 404, searches return no match |
| GRW-R5 | any role | — | SQL `ScopeFilter` and in-memory `PolicyEnforcer` give identical answers (as CASE-R5) |

### Register (`POST /growers`)
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R10 | GROWER with `grower:create`, no grower linked yet | registers self (`territoryId`, `displayName`, `phone`, `preferredLanguage`, optional `preferredPartnerOrgId`) | 201, `Location`, `ETag "0"`; `userId` = caller, `status ACTIVE`, `onboardingChannel SELF`; outbox `grower.registered` |
| GRW-R11 | staff or partner with `grower:create` and the territory in scope | registers someone else (assisted, IAM-1) | 201, `userId null`, `status PENDING_CONSENT`, `onboardingChannel ASSISTED`, `onboardedByUserId` = caller, `onboardedViaOrgId` = caller's org (partners) ; outbox `grower.registered`. Becomes ACTIVE only through SPEC-GRW-03 (grower OTP consent) |
| GRW-R12 | GROWER already linked to a grower | registers self again | 409 `grower.already-registered` |
| GRW-R13 | phone already in the country's directory | register | 409 `grower.phone-exists`. The response **never** contains the other grower's id (no enumeration) |
| GRW-R14 | territory unknown, inactive, or outside the caller's create scope; or `preferredPartnerOrgId` not an active partner in the same country | register | 404 `NOT_FOUND` for the territory (scope), 422 `grower.partner-invalid` for the partner |
| GRW-R15 | invalid input: blank or > 120-char `displayName`; phone that does not normalise to a valid E.164 number **for the territory's country**; unsupported `preferredLanguage`; malformed ids; missing or invalid `Idempotency-Key` | register | 400 `VALIDATION_FAILED` with `errors[].field` |
| GRW-R16 | same `Idempotency-Key`, identical body | register retried | 200 with the original grower, `Idempotent-Replayed: true`; no second row or event |
| GRW-R17 | same `Idempotency-Key`, different body | register | 409 `IDEMPOTENCY_KEY_REUSED` |
| GRW-R18 | a new grower | stored | in the D1 shard of the territory's country; `grower_id` decodes to that country and shard; directory row in the same transaction |

### Read and update
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R20 | grower in scope (R1–R3) | `GET /growers/{id}` | 200 + `ETag "<version>"` |
| GRW-R21 | id out of scope, unknown, malformed, or of an unknown country/shard | `GET` / `PATCH` | 404 `NOT_FOUND` (same body for all, nothing revealed) |
| GRW-R22 | grower PII (ACC-3) | grower returned | `displayName` full for FULL clearance (the grower themself, field staff), masked word by word (`R***** P****`) for MASKED (care, partners, managers); `phoneLast4` always; **no phone field ever** |
| GRW-R23 | `grower:update` + in scope | `PATCH /growers/{id}` with `If-Match` (fields: `displayName`, `preferredLanguage`, `preferredPartnerOrgId`, `territoryId`) | 200, version + 1, new `ETag`; outbox `grower.updated` with the **names** of changed fields only |
| GRW-R24 | — | `If-Match` missing · stale | 428 `PRECONDITION_REQUIRED` · 412 `STALE_VERSION` (with `expectedVersion`), no change |
| GRW-R25 | PATCH tries `phone`, `userId`, `status`, `countryCode` or an unknown field | update | 422 `grower.field-immutable` naming the field (phone: D5) |
| GRW-R26 | new `territoryId` in another country, or outside the caller's scope | update | 422 `grower.country-change` (a country move is a new grower record, data residency) · 404 for out-of-scope territory |
| GRW-R27 | GROWER (OWN) | PATCH own `territoryId` or `preferredPartnerOrgId` | allowed (territory within the same country); `displayName` too |

### Find by phone (`POST /growers/search`)
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R30 | `grower:read` | `POST /growers/search {phone, countryCode}` | phone normalised + HMAC → directory shard → grower; 200 `{items:[grower]}` if in scope, otherwise `{items:[]}`. Same status and timing class for "not registered" and "out of scope" |
| GRW-R31 | — | phone sent as a query parameter (`GET /growers?phone=`) | not supported: 405/404 by routing; the phone never appears in a URL or access log (PRV-5) |
| GRW-R32 | `countryCode` not in the caller's scope | search | `{items:[]}` (here the country is a search hint, not trusted for placement; it only selects the directory) |

### Schema (increment 1; database `gc_grower`)
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R50 | a row inserted into a shard's `grower` without an id | — | `grower_id` comes from that shard's `next_id()` and decodes (`IdCodec`) to the shard's country and shard number |
| GRW-R51 | a shard's `phone_directory` | the same `phone_hmac` is inserted twice | rejected by `phone_directory_pk` (R13 under concurrency) |
| GRW-R52 | a grower row | territory or partner of another country, country other than the shard's, SELF without a user, ASSISTED without the onboarding user, malformed HMAC or last 4 | rejected by a named constraint |
| GRW-R53 | `dir.grower_user_link` | the same user is linked twice | rejected by `grower_user_link_pk` (R12 under concurrency) |
| GRW-R54 | the runtime (app) role | DDL; writes to `ref.*`; DELETE on `grower`, `phone_directory` or `dir.grower_user_link`; UPDATE of `dir.grower_user_link` | `permission denied` (least privilege) |
| GRW-R55 | every shard schema | — | its `outbox` is in publication `gc_outbox`; the CDC role can read only the outbox tables and the heartbeat |
| GRW-R56 | the dev seed (local and tests only) | — | each seed grower's id decodes to the D1 shard of its phone HMAC (dev key), and its directory row is in that shard |
| GRW-R57 | an already migrated database | migration runs again | no migration executed |

### Platform behaviour (same as case-service)
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R40 | no/invalid token · valid token without an account | any request | 401 problem+json |
| GRW-R41 | IAM unreachable, slow or unreadable | any request | 503 `DEPENDENCY_UNAVAILABLE`, never 500 (as CASE-R9/R9a) |
| GRW-R42 | allow-listed service client (aud `gc-internal`) | `GET /internal/growers/{id}` | 200 replica view `{growerId, countryCode, territoryId, userId, displayName, phoneHmac, phoneLast4, preferredPartnerOrgId, status, version}` for SPEC-CASE-06; user token → 403 |
| GRW-R43 | missing env variable (`GC_PHONE_HMAC_KEY`, `GC_PHONE_ENC_KEY`, DB, IAM, Keycloak…) | start | refuses to start naming every missing variable (SPEC-PLAT-06) |

## 2. Examples
**Fixture (dev seed):** territories IN 1002 Pune, 1003 Nashik, 1005 Karnataka; BR 2002. Partner orgs 501 (retailer.in), 502 (retailer2.in). Growers (ids encoded per D1/D6; shown by name):

| Grower | User | Country | Territory | Preferred partner | Onboarded via | Phone (last4) |
|---|---|---|---|---|---|---|
| G1 Ramesh Patil | 1 (grower.in1) | IN | 1002 Pune | 501 | — | `+91 98765 43210` (3210) |
| G2 Sunita Rao | 2 (grower.in2) | IN | 1003 Nashik | 502 | — | `+91 98223 37788` (7788) |
| G3 Lakshmi (assisted onboarding) | — | IN | 1005 Bengaluru Rural | — | 502 (onboarded by retailer2.in) | `+91 98450 55432` (5432), status PENDING_CONSENT |
| G4 Joao Silva | 3 (grower.br1) | BR | 2002 Ribeirão Preto | — | — | `+55 16 99123 4455` (4455) |

Names, users, territories, partners and last-4 digits match case-service's replica seed, so the two services describe the same people until SPEC-CASE-06 replaces that seed.

### Isolation matrix (`GET /growers/{id}` and search)
| Example | Rule | User | Visible |
|---|---|---|---|
| GRW-E01 | R1 | grower.in1 | G1 |
| GRW-E02 | R1 | grower.in2 | G2 |
| GRW-E03 | R1 | grower.br1 | G4 |
| GRW-E04 | R3 | care.in (COUNTRY IN) | G1, G2, G3 |
| GRW-E05 | R3 | care.br | G4 |
| GRW-E06 | R3 | tm.in (Maharashtra + districts) | G1, G2 |
| GRW-E07 | R3 | agro.in (ASSIGNED, Pune) | G1 |
| GRW-E08 | R2 | retailer.in (501) | G1 |
| GRW-E09 | R2 | retailer2.in (502) | G2 (preferred), G3 (onboarded via) |
| GRW-E10 | R4 | admin.in (no `grower:read`) | none |
| GRW-E11 | R5 | every user above | `ScopeFilter` result = `PolicyEnforcer` result |

### Register
| Example | Rule | Actor | Input | Expected |
|---|---|---|---|---|
| GRW-E20 | R10 | new grower user (no grower) | territory 1002, "Anil Shinde", `+91 99887 76655`, `mr` | 201, ACTIVE, SELF, `userId` = caller, `phoneLast4 "6655"`, event `grower.registered` with ids only |
| GRW-E21 | R11 | agro.in | territory 1002, phone `9988776644` (national format) | 201, PENDING_CONSENT, ASSISTED, `onboardedByUserId 102`, phone normalised to `+919988776644` |
| GRW-E22 | R11 | retailer.in | territory 1002 | 201, `onboardedViaOrgId 501`; visible to retailer.in afterwards (R2) |
| GRW-E23 | R12 | grower.in1 | register self | 409 `grower.already-registered` |
| GRW-E24 | R13 | care.in | phone of G1 | 409 `grower.phone-exists`, body has no grower id |
| GRW-E25 | R14 | tm.in | territory 1005 (outside Maharashtra) | 404 |
| GRW-E26 | R14 | care.in | territory 2002 (BR) | 404 |
| GRW-E27 | R14 | care.in | `preferredPartnerOrgId` of a BR partner | 422 `grower.partner-invalid` |
| GRW-E28 | R15 | care.in | `displayName` "" · 121 chars · phone `+55 11 91234 5678` for an IN territory · phone `12345` · language `xx` · no `Idempotency-Key` · `territoryId "abc"` | 400 `VALIDATION_FAILED`, `errors[].field` = displayName · displayName · phone · phone · preferredLanguage · Idempotency-Key · territoryId |
| GRW-E29 | R15 | care.in | `displayName` exactly 120 chars | 201 (boundary) |
| GRW-E30 | R16 | care.in | E21 retried with the same key and body | 200, `Idempotent-Replayed: true`, one row, one event |
| GRW-E31 | R17 | care.in | same key, other phone | 409 `IDEMPOTENCY_KEY_REUSED` |
| GRW-E32 | R18 | — | any registered grower | `ShardRouter.forId(growerId)` = the D1 shard of its phone HMAC and country; directory row in that shard |
| GRW-E33 | R4 | admin.in (no `grower:create`) | register | 403 |

### Read and update
| Example | Rule | Actor | Input | Expected |
|---|---|---|---|---|
| GRW-E40 | R20 | care.in | GET G1 | 200, `ETag "0"` |
| GRW-E41 | R21 | care.br | GET G1 · GET `999` (unknown) · GET `abc` · GET id with unknown country bits | 404, identical bodies |
| GRW-E42 | R22 | grower.in1 / agro.in → FULL; care.in / retailer.in → MASKED | GET G1 | "Ramesh Patil" vs "R***** P****"; `phoneLast4 "3210"`; no `phone` key in JSON |
| GRW-E43 | R23 | care.in | PATCH G2 `preferredLanguage "hi"`, `If-Match "0"` | 200, `ETag "1"`, event `grower.updated {changedFields:["preferredLanguage"]}` |
| GRW-E44 | R24 | care.in | PATCH without `If-Match` · with `"7"` | 428 · 412 `expectedVersion 0` |
| GRW-E45 | R25 | care.in | PATCH `phone` · `status` · `countryCode` · `userId` · `foo` | 422 `grower.field-immutable` naming each field |
| GRW-E46 | R26 | care.in | PATCH G1 `territoryId 2002` | 422 `grower.country-change` |
| GRW-E47 | R26 | tm.in | PATCH G1 `territoryId 1005` | 404 (target territory out of scope) |
| GRW-E48 | R27 | grower.in1 | PATCH own `territoryId 1003` | 200 |
| GRW-E49 | R21 | grower.in2 | PATCH G1 | 404 |

### Find by phone
| Example | Rule | Actor | Input | Expected |
|---|---|---|---|---|
| GRW-E60 | R30 | care.in | `{phone:"+919876543210", countryCode:"IN"}` | `{items:[G1]}` masked |
| GRW-E61 | R30 | care.in | `{phone:"98765 43210", countryCode:"IN"}` (national format, spaces) | `{items:[G1]}` |
| GRW-E62 | R30 | care.in | unregistered number | `{items:[]}` |
| GRW-E63 | R30 | retailer2.in | phone of G1 | `{items:[]}` (out of scope, same answer as unregistered) |
| GRW-E64 | R32 | care.br | phone of G1, `countryCode "IN"` | `{items:[]}` |
| GRW-E65 | R31 | care.in | `GET /growers?phone=…` | not 200; access log contains no phone digits |

### Phone normalisation and protection (unit level, D3 · R15 phone field · R30)
| Example | Rule | Country plan | Input | Expected |
|---|---|---|---|---|
| GRW-E80 | D3 | IN | `+91 98765 43210` · `98765-43210` · `(0)9876543210` · `+919876543210` | `+919876543210` |
| GRW-E81 | D3 | BR | `+55 11 91234 5678` · `11912345678` · `1132345678` (landline, 10 digits) | `+5511912345678` · `+5511912345678` · `+551132345678` |
| GRW-E82 | R15 | IN | `+55 11 91234 5678` (other country) · `12345` · `5876543210` (starts with 5) · `98765432101` (11 digits) · `` · `98765abc10` | invalid → `VALIDATION_FAILED` on `phone` |
| GRW-E83 | D3 | — | same E.164 number twice; two different numbers | equal HMACs (32 bytes); different HMACs; HMAC changes with the key |
| GRW-E84 | D3 | — | encrypt then decrypt; encrypt the same number twice; decrypt with a tampered ciphertext or another key | original number; different ciphertexts (random nonce); rejected |
| GRW-E85 | D3 | — | `+919876543210` | `phoneLast4 "3210"`; `toString()` of the value object never contains the number |

### Schema
| Example | Rule | Input | Expected |
|---|---|---|---|
| GRW-E90 | R50 | insert into `in_s05.grower` and `br_s05.grower` | ids decode to (IN 46, 5) and (BR 8, 5); ids increase within a shard |
| GRW-E91 | R51 | second directory row with the same HMAC in `in_s05` | `phone_directory_pk` |
| GRW-E92 | R52 | territory 2002 in an IN shard · partner of BR · `country_code 'BR'` in an IN shard · SELF with `user_id NULL` · ASSISTED with `onboarded_by_user_id NULL` · 31-byte HMAC · last 4 `'12a4'` | `grower_territory_fk` · `grower_partner_fk` · `grower_country_check` · `grower_onboarding_check` · `grower_onboarding_check` · `grower_phone_hmac_check` · `grower_phone_last4_check` |
| GRW-E93 | R53 | link user 1 a second time | `grower_user_link_pk` |
| GRW-E94 | R54 | app role: `CREATE TABLE` in a shard · `INSERT INTO ref.territory` · `DELETE FROM` grower / phone_directory / `dir.grower_user_link` · `UPDATE dir.grower_user_link` | permission denied |
| GRW-E95 | R55 | every migrated shard | `in_sNN.outbox` listed in `pg_publication_tables` for `gc_outbox`; CDC role cannot read `grower` |
| GRW-E96 | R56 | G1–G4 | `IdCodec.decode(id)` = (country of the seed row, D1 shard of HMAC(phone, dev key)); `phone_directory` row in the same shard; `phone_enc` decrypts to the phone with the dev key |
| GRW-E97 | R57 | migrate twice | second run: 0 migrations executed |

### Platform
| Example | Rule | Input | Expected |
|---|---|---|---|
| GRW-E70 | R40 | no token · forged token · valid token of an unknown subject | 401 problem+json |
| GRW-E71 | R41 | IAM 5xx · body cut mid-response · token endpoint refuses | 503 `DEPENDENCY_UNAVAILABLE` |
| GRW-E72 | R42 | case-service client token · grower.in1 user token | 200 replica view of G1 (with `phoneHmac`) · 403 |
| GRW-E73 | R43 | start without `GC_PHONE_HMAC_KEY` and `GC_PHONE_ENC_KEY` | startup fails naming both |

## 3. API contract
OpenAPI in `contracts/grower-service.openapi.json` (exported by `GrowerOpenApiContractIT`, as SPEC-PLAT-01). IDs are strings.

```
POST /growers                      Idempotency-Key: <uuid>
  {territoryId, displayName, phone, preferredLanguage, preferredPartnerOrgId?}
  201 Grower · 200 replay · 400 · 401 · 403 · 404 · 409 · 422 · 503
GET  /growers/{growerId}           200 Grower + ETag · 401 · 404 · 503
PATCH /growers/{growerId}          If-Match: "<version>"   (JSON merge patch of the editable fields)
  200 Grower + ETag · 400 · 401 · 404 · 412 · 422 · 428 · 503
POST /growers/search               {phone, countryCode}  →  200 {items: Grower[]}
GET  /internal/growers/{growerId}  service clients only → 200 GrowerReplica · 403 · 404

Grower = {growerId, countryCode, territoryId, userId?, displayName (masked per R22), phoneLast4,
          preferredLanguage, preferredPartnerOrgId?, status: ACTIVE|PENDING_CONSENT|INACTIVE,
          onboardingChannel: SELF|ASSISTED, onboardedByUserId?, onboardedViaOrgId?, version, createdAt, updatedAt}
```
Errors are RFC 9457 problem+json with `code` and, for rule violations, `rule` (`GRW-R13` …).

## 4. Data & events
Database `gc_grower`, owner/app role split, migrations as in `sharded-migration`. `docs/schema.md` in the new repository.

- **`ref` schema (replicas, read-only):** `country` (with `calling_code`, `country_ix`), `territory`, `partner_org`, `shard`.
- **Per shard `<cc>_sNN`:**
  - `grower(grower_id DEFAULT next_id() PK, country_code, territory_id, user_id UNIQUE NULL, display_name, phone_hmac bytea(32) NOT NULL, phone_enc bytea, phone_key_id, phone_last4, preferred_language, preferred_partner_org_id, status, onboarding_channel, onboarded_by_user_id, onboarded_via_org_id, version, created_at, updated_at)`;
  - `phone_directory(phone_hmac PK, grower_id NOT NULL)`: same shard as the grower (D1); UNIQUE gives R13 under concurrency;
  - `outbox`, `idempotency_key` (as case-service).
- `user_id` uniqueness across shards: a self-registering grower's shard is chosen by phone, so R12 checks a **per-country** `ref`-level table `grower_user_link(user_id PK, grower_id)` written in the same transaction (one cluster per country locally; across clusters this becomes a two-step write — noted in `docs/schema.md`).
- **Events** (outbox → Debezium → topic `grower.grower.events`, key = `growerId` string; contracts in `gc-event-contracts`):
  - `grower.registered {growerId, countryCode, territoryId, userId?, status, onboardingChannel}`;
  - `grower.updated {growerId, version, changedFields[]}`.
  - **No PII** (no name, no phone, no hmac). Consumers that need replica fields call `GET /internal/growers/{id}` (R42).

## 5. Security
| Concern | Rule |
|---|---|
| Permission (403) | `grower:create` (R10/R11), `grower:read` (R20/R30), `grower:update` (R23). Role mapping (D7): GROWER create/read/update (OWN); CARE_AGENT, FIELD_AGRONOMIST, TERRITORY_MANAGER create/read/update; RETAILER create/read; COUNTRY_ADMIN none |
| Object scope (404) | R1–R4 via `PolicyEnforcer.require` and `ScopeFilter`; geo only, BU ignored |
| Isolation matrix | E01–E11 (same users as CASE-E01…E09) |
| PII | R22 masking; phone only as hmac/enc/last4 (D3); no PII in logs, events or URLs (R31) |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R1–R5 / E01–E11 | `GrowerIsolationIT#visibleGrowers` (matrix), `GrowerScopeTest#filterAndEnforcerAgree` |
| R10–R18 / E20–E33 | `GrowerRegistrationIT#registers`, `#rejected`, `#idempotency`; `GrowerPlacementTest#placementMatchesDecodedId` |
| R20–R27 / E40–E49 | `GrowerUpdateIT#reads`, `#updates`, `#rejectedUpdates`; `GrowerViewTest#namesAreMaskedByClearance` |
| R30–R32 / E60–E65 | `GrowerSearchIT#findsByPhone` |
| D1 placement / E32 (unit part) | `GrowerPlacementTest#shardOfHmac` |
| R50–R57 / E90–E97 | `GrowerSchemaMigrationIT` (nested by rule), `ShardMigrationConventionsTest` |
| D3 / E80–E85 | `PhoneNumberTest#normalises`, `#rejects`, `#lastFourAndToString`; `PhoneProtectorTest#hmac`, `#encryption` |
| R40–R43 / E70–E73 | `GrowerSecurityIT`, `IamPrincipalClientTest`, `InternalGrowerApiIT`, `ConfigurationHygieneTest` |
