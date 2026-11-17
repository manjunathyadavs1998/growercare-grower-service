# SPEC-GRW-03: Consent and phone verification (OTP)
- **Requirements:** PRV-1 (consent per purpose, notice version, language), IAM-1 (assisted onboarding with grower OTP consent), IAM-5 (OTP fraud controls), PRV-5, Phase 1 PRD journeys **J1** and **J2** and decisions D1–D3
- **Status:** Implemented
- **Owner:** grower-service; messages are sent by notification-service (SPEC-NOT-01)
- **Supersedes:** GRW-R10's "status ACTIVE" (see R100). **Defers** the phone change promised by SPEC-GRW-01 D5 to SPEC-GRW-05 (D6 below)

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **Every new grower starts `PENDING_CONSENT`**, self-registered ones too (supersedes the status part of GRW-R10). A grower becomes `ACTIVE` only by confirming a one-time code sent to their phone **and** granting the SERVICE consent | PRD J1: a password login (PRD D1) does not prove phone possession, and DPDP needs recorded consent before processing |
| D2 | **The code is sent synchronously** through notification-service's internal API (`POST /internal/messages`, SPEC-NOT-01), not through Kafka. grower-service decrypts the phone only for that call | A code must never sit in a topic, a log or an event; OTP delivery is latency-sensitive |
| D3 | **The code is never stored in clear:** 6 random digits, stored as `HMAC-SHA-256(phone HMAC key, "otp:" ‖ verificationId ‖ code)`, valid **5 minutes**, **5 attempts**; compared in constant time | Same protection as the phone itself (SPEC-GRW-01 D3); a database leak does not reveal live codes |
| D4 | **Fraud limits (IAM-5):** at most **3 verifications per grower per 15 minutes**; a verification that used up its 5 attempts blocks new ones for that grower for **15 minutes**. Per-IP and per-device limits belong to the edge gateway (SPEC-EDGE-01) | Stops SMS pumping and code guessing at the source of truth; the gateway adds the network-level limits |
| D5 | **Consent is append-only per purpose:** `SERVICE` (required to activate), `DATA_SHARING_RETAILER`, `MARKETING` (optional), each with the **notice version** and **language** the grower saw, the channel (`SELF` / `ASSISTED`, PRD D3) and who recorded it. The current consent is the latest row per purpose. `SERVICE` cannot be withdrawn through this API (that is account closure / DSAR, PRV-2) | PRV-1; an audit trail of every change; no silent overwrite |
| D6 | **Phone change moves to SPEC-GRW-05.** It needs the new number's directory row in another shard (cross-shard move) plus OTP to the new number; M1 only needs activation | Keeps M1 single-shard and small; nothing in J1/J2 needs a number change |
| D7 | **New permission `grower:consent`** (IAM V8) for `GROWER`, `RETAILER_USER`, `RETAILER_ADMIN`, `CARE_AGENT`, `FIELD_AGRONOMIST`, `TERRITORY_MANAGER`: start a verification and confirm it for a grower **in scope** (SPEC-GRW-01 R1–R3); changing optional consents is the grower's own action only | Retailers must be able to do J2 although they have no `grower:update` |
| D8 | **Notice versions are reference data** (`ref.consent_notice`: country, purpose, language, version, effective from). A confirmation must name the **current** version for the grower's country, purpose and language | The record proves which text the grower agreed to; an outdated client cannot record consent to an old notice |

## 1. Behaviour (rules)
### Activation
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R100 | a grower registers (self or assisted) | `POST /growers` | status `PENDING_CONSENT` for both channels (D1); everything else as SPEC-GRW-01 |
| GRW-R101 | caller with `grower:consent`, grower in scope and `PENDING_CONSENT` | `POST /growers/{id}/verifications` `{purpose: "ACTIVATION"}` | 202 `{verificationId, expiresAt, phoneLast4}`; a 6-digit code is sent to the grower's phone through notification-service (template `otp.activation`, grower's language, category `OTP`) |
| GRW-R102 | grower already `ACTIVE` · `INACTIVE` | start a verification | 422 `grower.already-active` · 422 `grower.not-verifiable` |
| GRW-R103 | 3 verifications for this grower in the last 15 minutes, or one exhausted its attempts in the last 15 minutes | start a verification | 422 `grower.otp-rate-limited`, nothing sent (D4) |
| GRW-R104 | notification-service unreachable or refusing | start a verification | 503 `DEPENDENCY_UNAVAILABLE`; no verification stored (the send happens inside the transaction, which rolls back) |
| GRW-R105 | open verification V, correct code, `consents` grant `SERVICE`, `noticeVersion` current (D8), `language` supported | `POST /growers/{id}/verifications/{vid}/confirm` `{code, noticeVersion, language, consents: [{purpose, granted}]}` | 200 grower view with `status ACTIVE`, version + 1; V consumed; one consent row per listed purpose (channel `SELF` when the caller is the grower, else `ASSISTED` with `recordedByUserId`); events `grower.updated {changedFields: ["status"]}` and one `grower.consent.recorded` per purpose |
| GRW-R106 | `SERVICE` missing or `granted: false` | confirm | 422 `grower.service-consent-required`; **no attempt used**, V stays open |
| GRW-R107 | `noticeVersion` not the current one (D8) | confirm | 422 `grower.notice-outdated`; no attempt used |
| GRW-R108 | wrong code | confirm | 422 `grower.otp-invalid` with `attemptsLeft`; the 5th wrong code makes V **exhausted**: 422 `grower.otp-locked` now and for every later confirm of V |
| GRW-R109 | V expired (> 5 min) · already consumed | confirm | 422 `grower.otp-expired` · 422 `grower.otp-used` |
| GRW-R110 | V belongs to another grower, unknown V, or grower out of scope | confirm | 404 (same body as an unknown grower) |
| GRW-R111 | invalid input: code not 6 digits, unknown purpose, duplicate purpose, unsupported language, missing fields | start or confirm | 400 `VALIDATION_FAILED` naming the field; no attempt used |
| GRW-R112 | the grower's preferred language has no consent notice in their country (e.g. `kn`) | start a verification | the code is sent in English (`en`); a confirmation must name a language that has a notice (R111) |

### Consent after activation
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R120 | caller who may read the grower (`grower:read`, in scope) | `GET /growers/{id}/consents` | 200 the current consent per purpose `{purpose, granted, noticeVersion, language, channel, recordedAt}` |
| GRW-R121 | the grower themself (OWN), `ACTIVE` | `PUT /growers/{id}/consents/{purpose}` `{granted, noticeVersion, language}` for `DATA_SHARING_RETAILER` or `MARKETING` | 200; a new consent row (channel `SELF`); event `grower.consent.recorded` |
| GRW-R122 | anyone | `PUT …/consents/SERVICE` with `granted: false` | 422 `grower.service-consent-required` (D5) |
| GRW-R123 | staff or partner (not the grower) | `PUT …/consents/{purpose}` | 403 `PERMISSION_DENIED` (optional consents are the grower's own decision) |
| GRW-R124 | the grower is not `ACTIVE` (still `PENDING_CONSENT`, or `INACTIVE`) | `PUT …/consents/{purpose}` | 422 `grower.not-active` (consent is first recorded by the activation, R105) |

### Data and seed
| Rule | Given | When | Then |
|---|---|---|---|
| GRW-R130 | the database | migration | per shard `grower_verification` (id, grower, purpose, code HMAC, expires_at, attempts, status OPEN/CONSUMED/EXHAUSTED, created_at) and `grower_consent` (append-only); `ref.consent_notice`; the app role cannot UPDATE or DELETE consent rows |
| GRW-R131 | a code | anywhere | never stored, logged, returned or put in an event in clear; only its HMAC is stored (D3) |
| GRW-R132 | dev seed | migration | G1, G2, G4 `ACTIVE` with a SERVICE consent row (notice `in-2026-11`, channel `SELF`); G3 stays `PENDING_CONSENT`; notices for IN in en, hi, mr and BR in pt, en for every purpose |

## 2. Examples
**Fixture:** dev seed (SPEC-GRW-01 §2) plus this spec's R132. notification-service is stubbed in tests (`MessageSender` port): it records each call and can fail. Fresh growers come from `POST /growers` (now `PENDING_CONSENT`).

| Example | Rule | Actor | Input | Expected |
|---|---|---|---|---|
| GRW-E100 | R100 | new grower user · care.in | register self · register assisted | both `PENDING_CONSENT` |
| GRW-E101 | R101 | the grower themself | start ACTIVATION | 202, `phoneLast4` = the grower's; one message to the grower's phone, template `otp.activation`, language = grower's, a 6-digit code |
| GRW-E102 | R101/D7 | retailer.in (onboarded the grower) · retailer2.in (not in scope) · admin.in (no `grower:consent`) | start ACTIVATION | 202 · 404 · 403 |
| GRW-E103 | R102 | care.in | start for G1 (ACTIVE) | 422 `grower.already-active` |
| GRW-E104 | R103 | the grower | 4th start within 15 minutes · start after an exhausted verification | 422 `grower.otp-rate-limited` (both); no message sent |
| GRW-E105 | R104 | the grower | start while the sender fails | 503; no verification row |
| GRW-E106 | R105 | the grower | confirm with the sent code, SERVICE + MARKETING granted, current notice, `mr` | 200 `ACTIVE`, version 1; 2 consent rows channel `SELF`; events `grower.updated [status]` + 2 × `grower.consent.recorded` (ids and purposes only) |
| GRW-E107 | R105/PRD D3 | retailer.in | confirm the code the grower read out | 200; consent rows channel `ASSISTED`, `recordedByUserId` 201 |
| GRW-E108 | R106 · R107 | the grower | SERVICE missing · `granted: false` · `noticeVersion "in-2025-01"` | 422 `service-consent-required` (×2) · 422 `notice-outdated`; `attemptsLeft` still 5 afterwards |
| GRW-E109 | R108 | the grower | wrong code 4 times, then the right one | `otp-invalid` with `attemptsLeft` 4…1, then 200 |
| GRW-E110 | R108 | the grower | wrong code 5 times, then the right one | 5th: `otp-locked`; right code afterwards: `otp-locked`; a new start within 15 min: `otp-rate-limited` |
| GRW-E111 | R109 | the grower | confirm after expiry (clock + 5 min 1 s) · confirm a consumed verification | `otp-expired` · `otp-used` |
| GRW-E112 | R110 | grower A | confirm grower B's verification id on A's path · unknown id | 404 · 404 |
| GRW-E113 | R111 | the grower | code `12345` · `1234567` · `12a456`, purpose `FOO`, SERVICE listed twice, language `xx` | 400 naming `code` / `consents[0].purpose` / `consents` / `language`; no attempt used |
| GRW-E114 | R112 | retailer2.in | start ACTIVATION for G3 (language `kn`) | 202; the message is in `en` |
| GRW-E120 | R120 | grower.in1 · care.in · retailer2.in | GET G1's consents | 200 (SERVICE granted, `in-2026-11`) · 200 · 404 |
| GRW-E121 | R121 | grower.in1 | grant `DATA_SHARING_RETAILER`, then withdraw it | 200, 200; two rows; current = not granted; two events |
| GRW-E122 | R122 | grower.in1 | withdraw SERVICE | 422 `grower.service-consent-required` |
| GRW-E123 | R123 | care.in · retailer.in | PUT G1's MARKETING | 403 |
| GRW-E124 | R124 | a new self-registered grower (PENDING_CONSENT) | grant MARKETING | 422 `grower.not-active` |
| GRW-E130 | R130 | app role | `UPDATE` / `DELETE` a `grower_consent` row | permission denied |
| GRW-E131 | R131 | — | after E101 and E106: verification row, logs, events, response bodies | the code appears nowhere; the row holds a 32-byte HMAC |
| GRW-E132 | R132 | dev seed | G1, G2, G4, G3 | ACTIVE + SERVICE consent (×3); G3 PENDING_CONSENT, no consent |
| GRW-E133 | **smoke** (PRD J2) | retailer.in registers a grower, starts ACTIVATION, reads the code from the dev mailbox (SPEC-NOT-01), confirms with SERVICE | grower `ACTIVE`; case-service then accepts a case for that grower (CASE-06 refresh via `grower.updated`) |

## 3. API contract
```
POST /growers/{growerId}/verifications                   {purpose: "ACTIVATION"}
  202 {verificationId, purpose, expiresAt, phoneLast4} · 400 · 401 · 403 · 404 · 422 · 503
POST /growers/{growerId}/verifications/{verificationId}/confirm
  {code, noticeVersion, language, consents: [{purpose, granted}]}
  200 Grower (SPEC-GRW-01 view) + ETag · 400 · 401 · 403 · 404 · 422 (rule + attemptsLeft where relevant)
GET  /growers/{growerId}/consents                        200 {items: [Consent]}
PUT  /growers/{growerId}/consents/{purpose}              {granted, noticeVersion, language} → 200 Consent · 403 · 404 · 422
Consent = {purpose, granted, noticeVersion, language, channel, recordedByUserId?, recordedAt}
```
Ids are strings; `attemptsLeft` is a Problem property (next to `code` and `rule`). The verification id is a UUID (not sharded: it is only used together with the grower id, which routes).

## 4. Data & events
- Per shard: `grower_verification` (PK `verification_id`; FK grower; index `(grower_id, created_at)` for R103) and `grower_consent` (append-only; FK grower; index `(grower_id, purpose, recorded_at DESC)`); `ref.consent_notice` (PK country, purpose, language, version).
- Events (outbox, topic `grower.grower.events`): `grower.updated {growerId, version, changedFields: ["status"]}` (existing type) and **new** `grower.consent.recorded {growerId, purpose, granted, noticeVersion, channel}` — no name, phone or code.
- Outbound: notification-service `POST /internal/messages` (client credentials, audience `gc-internal`; notification-service allow-lists `grower-service`). New variable `NOTIFICATION_BASE_URL`.
- IAM: V8 adds `grower:consent` (D7).

## 5. Security
| Concern | Rule |
|---|---|
| Permission (403) | `grower:consent` to start/confirm (D7); optional consents only by the grower (R123) |
| Scope (404) | SPEC-GRW-01 R1–R3 on the grower; a verification is only reachable through its own grower |
| Code secrecy | D3, R131; constant-time compare; codes from `SecureRandom` |
| Abuse | D4 per grower; gateway limits per IP/device later (EDGE-01) |
| PII | the phone is decrypted only to hand it to notification-service; never logged |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R100 / E100 | `GrowerRegistrationIT` (updated rows) |
| R101–R112 / E101–E114 | `GrowerVerificationIT` (stubbed `MessageSender`; expiry and windows by moving stored timestamps), `OtpRulesTest` (code format, HMAC binding, constant-time compare), `NotificationClientTest` (request shape, every refusal → 503 without phone or code) |
| R120–R124 / E120–E124 | `GrowerConsentIT` |
| R130–R132 / E130–E132 | `GrowerSchemaMigrationIT` (new rows) |
| E133 | growercare-local-platform `smoke.sh` (PRD J2) |
| §3 contract | `GrowerOpenApiContractIT` (4 new operations) |
