# ADR-010: Testcontainers for Integration Tests

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-08-12 |
| **Deciders** | Project lead |
| **Tags** | testing, integration, postgresql |

## Context

The default test profile uses H2 in PostgreSQL compatibility mode. This is fast (milliseconds startup) and runs all 5000+ tests quickly. However, H2 does not perfectly replicate PostgreSQL behavior:

- JSONB and array types differ.
- Index behavior and query plans differ.
- Some DDL syntax is PostgreSQL-specific.
- FTS (full-text search) is PostgreSQL-specific and not available in H2.

Subtle bugs can pass H2 tests but fail on real PostgreSQL. We needed a way to run integration tests against a real PostgreSQL instance without requiring developers to install PostgreSQL locally.

Options considered:

1. **H2 only** — fast, but misses PostgreSQL-specific behavior.
2. **Shared PostgreSQL instance** — requires infrastructure setup, tests interfere with each other.
3. **Testcontainers** — each test class gets a fresh PostgreSQL container. Requires Docker, but fully isolated.

## Decision

Add Testcontainers dependencies (`testcontainers`, `postgresql`, `junit-jupiter`). Integration tests tagged `@Tag("slow")` use Testcontainers PostgreSQL containers. A `@Testcontainers` annotation on the test class spins up a PostgreSQL 16 container, and `@Container` provides a `PostgreSQLContainer` instance.

Default tests (the `test` task) still use H2 for speed. The `slowTest` Gradle task runs only `@Tag("slow")` tests with Testcontainers.

## Consequences

**Positive:**

- Real PostgreSQL behavior — catches dialect-specific bugs.
- Fully isolated — each test class gets a fresh database.
- No local PostgreSQL installation required (just Docker).
- Flyway migrations run against real PostgreSQL, catching migration issues.

**Negative:**

- Requires Docker in CI (Docker-in-Docker or Docker socket).
- Slower startup (~5–10s per container).
- Must be tagged `@Tag("slow")` — not run in the default `test` task.
- Docker resource usage in CI (CPU, memory, disk).
