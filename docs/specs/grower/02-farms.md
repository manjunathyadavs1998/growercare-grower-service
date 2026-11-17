# SPEC-GRW-02: Farms
- **Requirements:** GRW-1 (farms/plots with point or polygon, crops, area), PRV-5 (precise location only with FULL clearance), Phase 1 PRD journey **J3** and M2
- **Status:** Implemented
- **Owner:** grower-service; case-service replicates farms (SPEC-CASE-08)
- **IDs:** rules GRW-R200+, examples GRW-E200+ (GRW-01 uses R1–R57 / E01–E97, GRW-03 R100–R132 / E100–E133)

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **A farm lives in its grower's shard** and is only reached through the grower (`/growers/{growerId}/farms/{farmId}`). Its id comes from that shard's `next_id()`, so it is globally unique | One grower and their farms are always one single-shard read; case-service needs unique ids for its replica |
| D2 | **Location is a point** (latitude, longitude, WGS 84) in Phase 1, optional. Polygons come later (a nullable GeoJSON column added then; no PostGIS yet) | PRD J3 says "point; polygon later"; a point is enough to route and to visit |
| D3 | **Precise location only with FULL clearance** (PRV-5): other readers get the point rounded to **0.1°** (about 11 km, district level) and `locationPrecision: "DISTRICT"` | Care agents and retailers can see the region without the exact field position |
| D4 | **Crops are codes from reference data** (`ref.crop`, seeded per migration in Phase 1: GRAPE, ONION, SOYBEAN, RAGI, PADDY, COTTON, MAIZE, WHEAT, TOMATO, CHILLI, SUGARCANE). 1–5 crops per farm. The crop list later comes from catalog-registry (labels are crop × pest) | Validated input without waiting for a crop master; one place to change later |
| D5 | **No hard delete:** a farm is archived (`status ARCHIVED`). An archived farm cannot be picked for new cases; its old cases keep it | Cases reference farms; history must stay intact |
| D6 | **Who may write:** the grower (OWN) and staff with `grower:update` in scope (care, agronomist, territory manager). Retailers (no `grower:update`) only read | Same permission model as editing the grower (SPEC-GRW-01); no new IAM permission |
| D7 | **Events on topic `grower.farm.events`** (aggregate `farm`, key = farmId): `grower.farm.created`, `grower.farm.updated`, `grower.farm.archived`, payload `{farmId, growerId, version}` only. Consumers fetch the farm from `GET /internal/growers/{growerId}/farms/{farmId}` | Ids-only events like the grower events (no location or name on Kafka) |
| D8 | **Dev seed farms keep case-service's ids** 80011–80014 (G1–G4) | Existing local cases reference them (SPEC-CASE-06 D6); the seed stays consistent across services |
| D9 | **At most 50 active farms per grower** | Bounds the list; a real grower has a handful |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R200 | caller with `grower:update` and the grower in scope (OWN or geo) | `POST /growers/{id}/farms` with `Idempotency-Key` `{name, areaHa, crops[], location?}` | 201 `Location`, `ETag "0"`, farm `ACTIVE`; event `grower.farm.created` |
| GRW-R201 | same key and body · same key, other body | retried | 200 + `Idempotent-Replayed` with the original farm · 409 `IDEMPOTENCY_KEY_REUSED` |
| GRW-R202 | name blank or > 120 or with control characters · area ≤ 0 or > 100000 · 0 or > 5 crops, unknown or duplicate crop · latitude/longitude out of range or only one of them | create or update | 400 `VALIDATION_FAILED` naming the field |
| GRW-R203 | the grower already has 50 active farms | create | 422 `grower.farm-limit` |
| GRW-R204 | grower `INACTIVE` | create | 422 `grower.not-active` (a `PENDING_CONSENT` grower may add farms: assisted onboarding records them at the shop) |
| GRW-R205 | caller with `grower:read`, grower in scope | `GET /growers/{id}/farms` | 200 `{items}`: active farms (archived with `?includeArchived=true`), ordered by creation |
| GRW-R206 | same | `GET /growers/{id}/farms/{farmId}` | 200 + `ETag`; 404 when the farm is not this grower's (same body as an unknown grower) |
| GRW-R207 | reader without FULL clearance | read a farm with a location | latitude and longitude rounded to 1 decimal, `locationPrecision "DISTRICT"`; FULL clearance: exact values, `"EXACT"` (D3) |
| GRW-R208 | caller with `grower:update` in scope, `If-Match` current | `PATCH /growers/{id}/farms/{farmId}` (merge patch of `name`, `areaHa`, `crops`, `location`; `location: null` clears it) | 200, version + 1, event `grower.farm.updated`; no change → same version, no event |
| GRW-R209 | stale or missing `If-Match` · unknown field (e.g. `growerId`, `status`) | patch | 412 · 428 · 422 `grower.field-immutable` |
| GRW-R210 | caller with `grower:update` in scope | `POST /growers/{id}/farms/{farmId}/archive` with `If-Match` | 200 `ARCHIVED`, version + 1, event `grower.farm.archived`; already archived → 200 unchanged, no event |
| GRW-R211 | an archived farm | patch | 422 `grower.farm-archived` |
| GRW-R212 | retailer (no `grower:update`) · out of scope · no permission | write | 403 · 404 · 403 |
| GRW-R213 | allow-listed service client | `GET /internal/growers/{id}/farms/{farmId}` | 200 full replica view (exact location, status, version); 404 unknown |

### Data and seed
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R220 | the database | migration | per shard `farm` (FK grower, `farm_grower_uq (farm_id, grower_id)`, status ACTIVE/ARCHIVED, version, point columns both-or-neither) and `farm_idempotency`; common `ref.crop`; app role cannot DELETE farms |
| GRW-R221 | dev seed | migration | farms 80011 (G1, "Patil grape block A", 2.5 ha, GRAPE), 80012 (G2, ONION), 80013 (G4, SOYBEAN), 80014 (G3, RAGI) with the locations case-service seeds |

## 2. Examples
| Example | Rule | Actor | Input | Expected |
|---|---|---|---|---|
| GRW-E200 | R200 | grower.in1 | add "Nashik plot 2", 1.5 ha, [GRAPE, ONION], point 20.0110, 73.7900 | 201, ETag "0", event `grower.farm.created {farmId, growerId, version 0}` |
| GRW-E201 | R200/D6 | agro.in (Pune, G1 in scope) · tm.in · care.in | add a farm for G1 | 201 (each) |
| GRW-E202 | R201 | grower.in1 | replay E200 · same key, other name | 200 replayed, same farmId · 409 |
| GRW-E203 | R202 | grower.in1 | name "" · area 0 · crops [] · crops [GRAPE ×2] · crop FOO · 6 crops · latitude 91 · only longitude | 400 naming `name` / `areaHa` / `crops` / `crops` / `crops[0]` / `crops` / `location.latitude` / `location` |
| GRW-E204 | R203 | care.in | 51st active farm of a test grower | 422 `grower.farm-limit` |
| GRW-E205 | R204 | care.in | add a farm for an INACTIVE grower · for G3 (PENDING_CONSENT) | 422 `grower.not-active` · 201 |
| GRW-E206 | R205 | grower.in1 · retailer.in (G1's partner 501) · retailer2.in | list G1's farms | 200 incl. 80011 · 200 · 404 |
| GRW-E207 | R206 | grower.in1 | read 80012 (G2's) on G1's path | 404 |
| GRW-E208 | R207 | agro.in (FULL) · care.in (MASKED) | read 80011 | 18.520430, 73.856743 EXACT · 18.5, 73.9 DISTRICT |
| GRW-E209 | R208 | grower.in1 | patch area 3.0 with `If-Match "0"` · same area again | 200 ETag "1", event `grower.farm.updated` · 200 ETag "1", no event |
| GRW-E210 | R209 | grower.in1 | stale `If-Match` · none · `{"growerId": "1"}` | 412 · 428 · 422 `grower.field-immutable` |
| GRW-E211 | R210/R211 | grower.in1 | archive a farm · archive again · patch it | 200 ARCHIVED + event · 200, no event · 422 `grower.farm-archived` |
| GRW-E212 | R212 | retailer.in (no `grower:update`) · care.br (Brazil, G1 out of scope) · admin.in | add a farm for G1 | 403 · 404 · 403 |
| GRW-E213 | R213 | case-service client · care.in user · unknown farm | `GET /internal/growers/{G1}/farms/80011` | 200 exact view · 403 · 404 |
| GRW-E220 | R220 | app role | `DELETE FROM in_s05.farm` | permission denied |
| GRW-E221 | R221 | dev seed | farms 80011–80014 | owned by G1, G2, G4, G3 in their shards, ACTIVE, version 0 |
| GRW-E222 | **smoke** (PRD J3) | grower.in1 | add a farm, then raise a case on it in case-service | 201 farm; case 201 (CASE-08 replica via `grower.farm.events` or read-through) |

## 3. API contract
```
POST  /growers/{growerId}/farms                    Idempotency-Key  {name, areaHa, crops[], location?: {latitude, longitude}}
GET   /growers/{growerId}/farms[?includeArchived=true]
GET   /growers/{growerId}/farms/{farmId}
PATCH /growers/{growerId}/farms/{farmId}           If-Match  (merge patch)
POST  /growers/{growerId}/farms/{farmId}/archive   If-Match
GET   /internal/growers/{growerId}/farms/{farmId}  (service clients)
Farm = {farmId, growerId, name, areaHa, crops[], location?: {latitude, longitude}, locationPrecision?, status, version, createdAt, updatedAt}
```
Ids are strings; `areaHa` is a decimal number with at most 2 fractional digits.

## 4. Data & events
- Shard: `farm` (farm_id `next_id()`, grower_id FK, name, area_ha numeric(10,2), crops varchar(16)[], latitude/longitude numeric(9,6), status, version, timestamps), `farm_idempotency` (key per grower: retries carry the grower id, so the shard is stable).
- Common: `ref.crop (crop_code, name)`.
- Outbox aggregate `farm` → topic `grower.farm.events` (existing connector, `route.topic.replacement grower.${routedByValue}.events`).

## 5. Security
| Concern | Rule |
|---|---|
| Permission (403) | `grower:update` to write, `grower:read` to read (D6) |
| Scope (404) | the grower's scope (SPEC-GRW-01 R1–R3); a farm only through its own grower |
| Location (PRV-5) | D3/R207: exact only with FULL clearance; events carry no location |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R200–R212 / E200–E212 | `FarmApiIT`, `FarmRulesTest` (validation, rounding, request hash) |
| §3 contract | `GrowerOpenApiContractIT` (6 new operations) |
| R213 / E213 | `InternalGrowerApiIT` (new rows) |
| R220–R221 / E220–E221 | `GrowerSchemaMigrationIT` (new rows) |
| E222 | growercare-local-platform `smoke.sh` |
