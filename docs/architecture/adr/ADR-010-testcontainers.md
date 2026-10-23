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

Integration tests tagged `@Tag("slow")` use real PostgreSQL with Flyway migrations.
`PostgresTestContainer` keeps a JVM-wide singleton so Spring's cached contexts
retain a valid connection. By default, the fixture starts PostgreSQL 16 through
Testcontainers; the Docker environment owns its lifecycle.

For an explicitly prepared disposable Compose database, set
`JAVA_AGENT_TEST_JDBC_URL`, `JAVA_AGENT_TEST_DB_USER` and
`JAVA_AGENT_TEST_DB_PASSWORD`. The URL must name a database with the
`java_agent_test_` prefix. This mode does not create nested Docker containers;
the caller owns creation, isolation, resource caps and cleanup. A failed explicit
connection does not fall back to another database. Existing application data or
an installed runtime database must never be supplied to this migration fixture.

Default tests (the `test` task) still use H2 for speed. The `slowTest` Gradle task runs only `@Tag("slow")` tests, including real
retry/backoff/timeout waits and the PostgreSQL fixture. It is manual only:
`cd backend && ./gradlew slowTest`, the `Manual slow integration` workflow, or
`python3 scripts/release_verify.py --run-slow`. Ordinary PR/push CI,
`build`/`check`, default release verification and `jacocoTestReport` do not invoke it.
The default coverage report reads only the fast `test` execution data, even if
an earlier manual run left `slowTest.exec` on disk.

## Consequences

**Positive:**

- Real PostgreSQL behavior — catches dialect-specific bugs.
- Isolated per qualification run; the shared JVM fixture preserves Spring context caching.
- No local PostgreSQL installation required (just Docker).
- Flyway migrations run against real PostgreSQL, catching migration issues.

**Negative:**

- The default fixture requires Docker; an explicitly managed disposable Compose database avoids a nested Docker socket.
- PostgreSQL and Flyway startup add integration-test cost.
- Must be tagged `@Tag("slow")` — not run in the default `test` task.
- Docker resource usage in CI (CPU, memory, disk).

Coverage uses separate reports for the two test sets: the measured fast backend
floor is 79.05%, while the previous combined floor of 79.38% is retained by the
manual `cd backend && ./gradlew fullCheck`. The bot floor stays 82.47%.
Fast coverage never reads an old `slowTest.exec`; the full report has its own path.
