# growercare-grower-service

Grower registry for GrowerCare: grower profiles, the phone directory, and (next) farms and consents. One of the independent service repositories of the GrowerCare platform (ADR-017).

- **Spec:** [`docs/specs/grower/01-grower-registry.md`](docs/specs/grower/01-grower-registry.md)
- **Port:** 8084 (8083 is Kafka Connect locally) · **Image:** `ghcr.io/manjunathyadavs1998/grower-service`
- **Configuration:** environment variables only, see [`.env.example`](.env.example)

## Build and run
```bash
mvn -B verify                                   # tests (Docker for Testcontainers), coverage gate input
cp .env.example .env && mvn -B package jib:dockerBuild -DskipTests && docker compose up -d
```
The shared infrastructure (Postgres, Redis, Keycloak, Kafka) comes from `growercare-local-platform`.
