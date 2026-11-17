# SPEC-GRW-06: What the apps need from grower-service
- **Requirements:** GRW-1, PRV-1 (show the notice the grower agrees to), ACC-11 (partner sees its growers), Phase 1 PRD UI scope (§8) and the UI-readiness gaps found before M4
- **Status:** Implemented
- **Owner:** grower-service
- **IDs:** rules GRW-R300+, examples GRW-E300+ (GRW-04 unmask and GRW-05 phone change are reserved)

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **`GET /growers/me`** returns the grower linked to the calling GROWER user (`dir.grower_user_link`); 404 while the user has not registered one | The grower app must find "my" grower without knowing its id |
| D2 | **Notice texts are reference data next to the versions:** `ref.consent_notice` gains `title` and `body` (plain text, ≤ 8000 characters). `GET /consent-notices?country=&language=` returns the **current** notice per purpose. Texts are written and approved by legal; the dev seed has placeholder texts in en/hi/mr/pt | The grower must see exactly the version they agree to (PRV-1); one source for version and text |
| D3 | **A partner's growers come from an index, not a scan:** `dir.partner_grower (partner_org_id, grower_id, country_code, relation ONBOARDED/PREFERRED, created_at)`, written in the same transaction as registration and preferred-partner changes. `GET /growers?partner=mine` pages through it (20 per page, newest first) and reads each grower from its shard | Growers of one partner are spread over 64 shards; scanning them all per page does not scale |
| D4 | **The list shows what the partner may see:** the same masked view as `GET /growers/{id}` for that partner (SPEC-GRW-01 R22) | No new disclosure |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R300 | a GROWER user with a registered grower | `GET /growers/me` | 200 grower view + `ETag` |
| GRW-R301 | a GROWER user without a grower · a staff or partner user | `GET /growers/me` | 404 · 404 |
| GRW-R302 | any authenticated user | `GET /consent-notices?country=IN&language=mr` | 200 `{items: [{purpose, version, title, body}]}`, the current notice per purpose (SPEC-GRW-03 D8) |
| GRW-R303 | a language without notices in that country · unknown country · current notices without their text yet | same | 404 (a version is never offered without the text the grower agrees to). The same holds for consent (SPEC-GRW-03 D8: such a version cannot be the "current" one) and for the code's language (R112: falls back to English) |
| GRW-R304 | a partner user | `GET /growers?partner=mine[&cursor]` | 200 `{items, nextCursor}`: growers its org onboarded or is preferred by, newest first, masked as for that partner |
| GRW-R305 | staff · grower | `GET /growers?partner=mine` | 403 (staff use phone search; growers have `/me`) |
| GRW-R306 | registration with a partner, assisted registration by a partner, or a preferred-partner change | write | `dir.partner_grower` rows added (and the old PREFERRED row removed on a change) in the same transaction |
| GRW-R307 | the database | migration | `ref.consent_notice` + `title`, `body`; `dir.partner_grower` with a backfill of existing growers; dev seed placeholder texts |

## 2. Examples
| Example | Rule | Actor | Input | Expected |
|---|---|---|---|---|
| GRW-E300 | R300 | grower.in1 | `GET /growers/me` | 200, G1 |
| GRW-E301 | R301 | a test grower user with no grower · care.in · retailer.in | `GET /growers/me` | 404 ×3 |
| GRW-E302 | R302 | grower.in1 | IN / mr · BR / pt | 3 notices `in-2026-11` with Marathi placeholder titles · 3 notices `br-2026-11` |
| GRW-E303 | R303 | grower.in1 | IN / `ta` · `XX` / en · IN / `kn` (a current version without its text) | 404 · 404 · 404 |
| GRW-E304 | R304 | retailer.in (501) · retailer2.in (502) | `partner=mine` | G1 (preferred 501) and growers it onboarded · G2 and G3 |
| GRW-E305 | R305 | care.in · grower.in1 | `partner=mine` | 403 · 403 |
| GRW-E306 | R306 | care.in | a grower preferring 501: change to 502, then back to 501 | retailer.in loses it and retailer2.in gains it; then the reverse |
| GRW-E307 | R307 | dev seed | partner index | rows for G1 (501 PREFERRED), G2 (502 PREFERRED), G3 (502 ONBOARDED) |

## 3. API contract
```
GET /growers/me                                    200 Grower + ETag · 404
GET /consent-notices?country=&language=            200 {items: [{purpose, version, title, body}]} · 404
GET /growers?partner=mine&cursor=                  200 {items: [Grower], nextCursor?} · 403
```

## 4. Data & events
- Common migration: `ref.consent_notice.title`, `.body`; `dir.partner_grower` (PK `(partner_org_id, grower_id, relation)`, index `(partner_org_id, created_at DESC, grower_id DESC)` (the keyset of the cursor)).
- Shard migration: backfill `dir.partner_grower` from each shard's growers.
- No new events.

## 5. Security
| Concern | Rule |
|---|---|
| `/me` | only the caller's own link (no id in the request) |
| Partner list | the caller's org from the principal, never from the request (`partner=mine` only) |
| Notices | not personal data; any authenticated user |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R300–R306 / E300–E306 | `GrowerAppSupportIT` (incl. keyset paging and its cursor) |
| R307 / E307 | `GrowerSchemaMigrationIT` (index rows for the seed growers; app role cannot rewrite the index) |
| §3 contract | `GrowerOpenApiContractIT` (3 new operations) |
| GRW-E65 (SPEC-GRW-01) | `GrowerReadSearchIT`: `GET /growers?phone=…` is now refused with 400 (the partner list needs `partner=mine`) |
