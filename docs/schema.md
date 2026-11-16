# grower-service database schema

Database `gc_grower` (owner/app role split, sharded migrations as in the `sharded-migration` skill). The design is fixed by SPEC-GRW-01 §4; tables are added with increment 1 (schemas) and documented here in the same PR (PR policy R25).

| Schema | Tables | Notes |
|---|---|---|
| `ref` | `country` (with the phone plan), `territory`, `partner_org`, `shard`, `grower_user_link` | Replicas of control-plane / IAM masters, read-only for the app role |
| `<cc>_sNN` (64 per country) | `grower`, `phone_directory`, `outbox`, `idempotency_key` | A grower and its directory row share a shard (`fmix64(phone_hmac) mod 64`, SPEC-GRW-01 D1) |
