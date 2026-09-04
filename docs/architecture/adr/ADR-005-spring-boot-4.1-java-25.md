# ADR-005: Spring Boot 4.1 + Java 25 Platform

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-06-15 |
| **Deciders** | Project lead |
| **Tags** | platform, spring-boot, java, framework |

## Context

The project started on Spring Boot 3.x + Java 21. As the feature set grew, several capabilities of newer platforms became critical:

1. **Virtual threads** (stable in Java 21, refined in Java 25) — needed for parallel tool execution and async memory sync (see [ADR-001](ADR-001-virtual-threads-vs-reactive.md)).
2. **Latest Spring Boot features** — improved SSE support, `@ConfigurationProperties` binding, Testcontainers integration.
3. **Jakarta EE 10+ namespace** — `jakarta.persistence.*` instead of `javax.persistence.*`.
4. **Long-Term Support** — Java 25 LTS provides years of stable releases.

## Decision

Adopt **Spring Boot 4.1.0** on **Java 25 LTS** as the development and production platform.

### Version matrix

| Component | Version | Notes |
|-----------|---------|-------|
| Java | 25 LTS | Virtual threads stable, pattern matching, records, sealed types |
| Spring Boot | 4.1.0 | Latest — full Java 25 compatibility |
| Gradle | 9.6.1 | Groovy DSL, multi-project build |
| Lombok | 1.18.38 | `@RequiredArgsConstructor`, `@Slf4j`, `@Data` |
| MapStruct | 1.6.3 | Compile-time entity ↔ domain mapping |
| LangChain4j | 1.18.0 | LLM client abstraction (see [ADR-003](ADR-003-langchain4j-for-llm-abstraction.md)) |
| MCP Java SDK | 2.0.0 | Tool protocol (see [ADR-004](ADR-004-mcp-sdk-2.0.md)) |
| PostgreSQL | 16 | Database (see [ADR-002](ADR-002-sqlite-to-postgresql.md)) |
| Flyway | 12.4.0 | Schema migrations |
| Docker | `eclipse-temurin:25-jre-noble` | Production runtime |

## Consequences

**Positive:**

- **Virtual threads** as first-class concurrency primitive — no reactive complexity (ADR-001).
- **Records and pattern matching** — domain models are immutable records with sealed hierarchies where applicable.
- **Spring Boot 4.1 improvements** — better SSE handling, native virtual thread support in `Tomcat`/`Jetty`, improved Testcontainers integration.
- **Jakarta namespace** — modern persistence API, no `javax` legacy.
- **LTS** — Java 25 LTS ensures long-term support through 2030+.

**Negative:**

- **Spring Boot 4.1.0 bug** — graceful shutdown doesn't work correctly; workaround: `server.shutdown: immediate`.
- **Ecosystem maturity** — some libraries may not fully support Java 25 / Spring Boot 4.1 yet.
- **Lombok compatibility** — requires latest Lombok version (1.18.38) for Java 25 annotation processing.
- **Gradle version** — requires Gradle 9.6+ for Java 25 support.

**Mitigations:**

- `server.shutdown: immediate` in `application.yml` — documented workaround.
- All dependencies verified compatible before upgrade.
- `noop` profile with H2 in-memory for offline development/testing.
- `maxHeapSize = 2g` in Gradle build for `bootRun` to prevent OOM with large LLM payloads.

### Profile strategy

| Profile | DB | LLM | Port | Purpose |
|---------|-----|-----|------|---------|
| `dev` | PostgreSQL localhost:5432 | Ollama Cloud / local | 8090 | Development |
| `noop` | H2 in-memory | NoOp stub | 8090 | Tests, offline dev |
| `cli` | — (REST only) | — | — | CLI REPL |
| `prod` | PostgreSQL (Docker) | OpenAI-compatible | 8080 | Production |

## References

- `build.gradle` — version declarations
- `docker-compose.yml` — production deployment
- `docker-compose.local.yml` — local dev
- `Dockerfile` — `eclipse-temurin:25-jre-noble`
- `application.yml`, `application-dev.yml`, `application-noop.yml` — profile configs
- [Spring Boot 4.1 release notes](https://spring.io/projects/spring-boot)
- [JDK 25 release notes](https://openjdk.org/projects/jdk/25/)
