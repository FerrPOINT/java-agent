# ADR-002: Migration from SQLite to PostgreSQL

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-03-15 |
| **Deciders** | Project lead |
| **Tags** | persistence, database, migration |

## Context

The project initially used SQLite as the persistence layer — it was simple, embedded, and required no external service. As the project grew:

1. **Concurrent writes:** SQLite uses file-level locking; concurrent sessions with multiple writes caused `SQLITE_BUSY` errors under load.
2. **Feature limitations:** No native `JSONB` type, no full-text search (FTS) without extensions, no `gen_random_uuid()`.
3. **Docker deployment:** Production `docker-compose.yml` needed a shared database between backend and telegram-bot containers — SQLite's file-based model doesn't support this cleanly.
4. **Flyway support:** While Flyway supports SQLite, the feature set is limited (no schema-level migrations, no concurrent migration across containers).
5. **Data volume:** With 14 JPA entities, 18 migrations, and growing data (sessions, messages, usage logs, skills), SQLite's single-file approach became a bottleneck.

## Decision

Migrate to **PostgreSQL 16** as the sole production database.

- Backend: owns the schema, runs Flyway migrations (`db/migration/`).
- Telegram bot: shares the same PostgreSQL instance (separate Flyway schema history table: `flyway_bot_schema_history`).
- CLI: no direct database access — communicates with backend via REST only.

## Consequences

**Positive:**

- Full `JSONB` support — used in `context_references.metadata`, `approvals.request`.
- `gen_random_uuid()` native — all primary keys are UUID.
- Concurrent read/write without locking issues.
- `TIMESTAMPTZ` for all timestamps — no timezone ambiguity.
- Full-text search (FTS) added in V18 for session search.
- Testcontainers integration for integration tests (`@Tag("slow")`).
- Docker-friendly: official `postgres:16` image in `docker-compose.yml`.

**Negative:**

- Requires an external service (PostgreSQL container) — more complex than embedded SQLite.
- `noop` profile uses H2 in-memory for offline development/testing (not PostgreSQL — minor compatibility risk).
- Connection pool management needed (Spring Boot default HikariCP).

**Mitigations:**

- `noop` profile uses H2 in PostgreSQL compatibility mode to minimise drift.
- Integration tests use Testcontainers with real PostgreSQL 16.
- HikariCP connection pooling with sensible defaults.

## Migration Path

The migration was done early (V1 baseline → V2 agent schema), before significant data existed. No data migration script was needed — V1 is a placeholder, V2 creates the full schema fresh.

## References

- Flyway migrations: `backend/src/main/resources/db/migration/V1–V18`
- `docker-compose.yml` — PostgreSQL 16 on port 5432
- `docker-compose.local.yml` — PostgreSQL 16 on port 18091
- `noop` profile: H2 in-memory (`application-noop.yml`)
