# growercare-grower-service

Grower registry for GrowerCare: grower profiles, the phone directory, consent and phone verification by one-time code, and farms. One of the independent service repositories of the GrowerCare platform (ADR-017).

- **Specs:** [`01-grower-registry.md`](docs/specs/grower/01-grower-registry.md), [`02-farms.md`](docs/specs/grower/02-farms.md), [`03-consent-and-otp.md`](docs/specs/grower/03-consent-and-otp.md)
- **Port:** 8084 (8083 is Kafka Connect locally) · **Image:** `ghcr.io/manjunathyadavs1998/grower-service`
- **Configuration:** environment variables only, see [`.env.example`](.env.example)

## Build and run
```bash
mvn -B verify                                   # tests (Docker for Testcontainers), coverage gate input
cp .env.example .env && mvn -B package jib:dockerBuild -DskipTests && docker compose up -d
```
The shared infrastructure (Postgres, Redis, Keycloak, Kafka) comes from `growercare-local-platform`.
