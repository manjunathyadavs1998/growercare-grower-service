# SPEC-GRW-07: Grower contact for notifications, and the PATCH bodies in the contract
- **Requirements:** NOT-1, PRV-5; SPEC-NOT-02 D4; frontend follow-up (contract types)
- **Status:** Implemented
- **Owner:** grower-service
- **IDs:** rules GRW-R320+, examples GRW-E320+

## 0. Decisions
| # | Decision | Why |
|---|---|---|
| D1 | **`GET /internal/growers/{id}/contact`** returns `{phone (E.164, decrypted), language, status}` to allow-listed service clients only (`GC_CONTACT_CLIENTS`, Phase 1: `notification-service`), separate from `GC_INTERNAL_CLIENTS` (case-service may read growers, not phones) | The phone leaves the encrypted column only for the sender; a separate allow-list keeps the blast radius small |
| D2 | **Every contact read is audited** (`grower_contact_read`: who, which grower, when; never the number) | PRV-5 accountability for decrypting PII |
| D3 | **ACTIVE growers only**; others are 404 (no messages to growers without consent) | Consent first (GRW-03) |
| D4 | **The OpenAPI contract describes the merge-patch bodies** of `PATCH /growers/{id}` and `PATCH …/farms/{farmId}` and the farm create body with their fields (`@Schema(implementation=…)`), instead of Jackson's `JsonNode` bean | The generated UI types were unusable (frontend follow-up) |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R320 | a `GC_CONTACT_CLIENTS` service token | `GET /internal/growers/{id}/contact` | 200 `{phone, language, status: ACTIVE}`; audit row |
| GRW-R321 | grower not ACTIVE · unknown | contact | 404 (same body) |
| GRW-R322 | a user token · a service client not in `GC_CONTACT_CLIENTS` (e.g. case-service) | contact | 401 · 403 |
| GRW-R323 | the contract | generated | the three bodies list their fields (name, areaHa, crops, location; displayName, preferredLanguage, preferredPartnerOrgId, territoryId…) |

## 2. Examples
| Example | Rule | Input | Expected |
|---|---|---|---|
| GRW-E320 | R320 | notification-service, G1 | `+919876543210`, `mr`; one audit row without the number |
| GRW-E321 | R321 | G3 (PENDING_CONSENT) · 999 | 404 · 404 |
| GRW-E322 | R322 | grower.in1 token · case-service token | 401 · 403 |
| GRW-E323 | R323 | the exported contract | `FarmCreateRequest`, `FarmPatch`, `GrowerPatch` schemas with their properties |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R320–R322 / E320–E322 | `GrowerContactIT` |
| R323 / E323 | `GrowerOpenApiContractIT` rows |
