# Java Agent — Prototype Documentation

## 1. Purpose

This directory (`/opt/dev/java-agent/`) contains the **Java agent** — a standalone LLM agent runtime built on Spring Boot 4.1.

Goal: build a **minimal stable core** of an agent runtime that runs in Java, document which parts are essential, which are platform/integration-specific, and track all architecture decisions.

The `backend/` directory is the actual application. The `docs/` directory is the single source of truth for architecture and design.

## 2. Repository Layout

```
/opt/dev/java-agent/
├── backend/                # Gradle + Spring Boot 4 application
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/com/azhukov/agent/
│       ├── api/            # REST controllers + DTOs
│       ├── cli/            # Picocli / JLine REPL
│       ├── client/         # LLM clients, MCP client
│       ├── config/         # AgentProperties, beans
│       ├── core/           # domain + runtime
│       ├── gateway/        # Telegram gateway adapters
│       ├── persistence/    # JPA entities + repositories
│       ├── security/       # approvals, safety, redaction
│       ├── service/        # application services
│       └── tools/          # @AgentTool implementations
├── prototype/              # removed (reference repos, not in git)
└── docs/                   # this documentation
    ├── README.md                         # this file
    ├── 01-scope.md                       # what we port, what we skip
    ├── 02-core-architecture.md           # essential modules and data flow
    ├── 03-dependency-map.md              # Python → Java library mapping
    ├── 04-proposed-java-structure.md     # package layout
    ├── 05-migration-notes.md             # tricky parts and design decisions
    ├── 06-vision-browser.md              # vision + browser porting details
    ├── 07-mcp-client-status.md           # MCP client state and config
    ├── 08-browser-vision.md              # browser/vision endpoints
    ├── 09-builtin-tools.md               # matrix of implemented tools
    └── 10-production-readiness.md        # docker, systemd, logging, health, gateway
```

## 3. Implementation Status

- **Phase 0**: Gradle build, Flyway PostgreSQL schema, config ✅
- **Phase 1 skeleton**: domain model, contracts, tool registry, NoOp model, happy path HTTP endpoint ✅
- **Phase 1.5**: real `LangChain4jModelClient`, JPA persistence, tool skeletons, CLI REPL, gateway `/v1/chat/completions`, approvals gate ✅
- **Phase 2**: browser/vision tools, MCP client, skill/memory tools, delegation ✅
- **Phase 3**: tests, NoOp profiles, docs, production readiness, CLI hardening ✅
- **Phase 4 (current)**: Telegram gateway (webhook + long-polling + auth), docs update, E2E hardening

## 4. Quick Decisions

| Decision | Value | Rationale |
|----------|-------|-----------|
| Language | **Java 25 LTS** | virtual threads, modern HTTP client |
| Build tool | **Gradle 9.6.1** with Groovy DSL | user requirement |
| Web server | **Spring Boot 4.1.0** | replaces FastAPI + Uvicorn |
| Concurrency | **Virtual threads** (`spring.threads.virtual.enabled=true`) | simpler than WebFlux for I/O-bound tools |
| LLM abstraction | **LangChain4j 1.18.0** | provider-agnostic, OpenAI-compatible |
| Validation | Jakarta Bean Validation + Jackson | replaces Pydantic |
| Templating | **Pebble 4.1.2** | Jinja-like |
| HTTP client | `java.net.http.HttpClient` + `RestClient` + jsoup | replaces `httpx` / `requests` |
| WebSocket | `org.java-websocket:Java-WebSocket` | CDP, replaces `websockets` |
| Persistence | PostgreSQL (JDBC) + Flyway | dev DB is existing Postgres container; H2 for `noop` |
| LLM provider | **OpenAI-compatible** (default Ollama Cloud `https://ollama.com/v1` in dev) | configurable endpoint |
| MCP | `io.modelcontextprotocol.sdk:mcp:2.0.0` | official Anthropic Java SDK |
| CLI | Picocli 4.7.7 + JLine 4.3.1 | REPL |
| Rate limiting | Bucket4j 8.10.1 | HTTP filter |
| Resilience | Resilience4j 2.4.0 | retry + time-limiter for model calls |
| Gateway | Telegram webhook/long-polling | other messaging platforms deferred |

## 5. Agent Name

The agent name is configurable via `agent.name` (defaults to `Джава агент`).

## 6. Build & Run

```bash
cd backend
./gradlew test
./gradlew bootJar

# Dev with real LLM
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev

# NoOp / offline
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=noop

# CLI REPL (no web server)
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=cli,noop \
  --enable-native-access=ALL-UNNAMED
```

Health endpoints:
- `GET http://localhost:8090/actuator/health`
- `GET http://localhost:8090/api/v1/health`

Chat endpoint:
```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"message":"echo OK"}' \
  http://localhost:8090/api/v1/agent/chat
```

Telegram gateway:
- Webhook: `POST /api/v1/telegram/webhook`
- Long-polling: enabled via `AGENT_GATEWAY_TELEGRAM_LONG_POLLING_ENABLED=true`

## 7. Next Steps

1. Read `01-scope.md` to confirm boundaries.
2. Read `02-core-architecture.md` for the runtime data flow.
3. Read `03-dependency-map.md` for the Java stack choices.
4. Read `04-proposed-java-structure.md` for the package skeleton.
5. Read `05-migration-notes.md` for tricky parts.
6. Read `06-vision-browser.md` for vision and browser porting details.
7. Read `07-mcp-client-status.md` for MCP configuration.
8. Read `08-browser-vision.md` for HTTP examples.
9. Read `09-builtin-tools.md` for the implemented tool matrix and security defaults.
10. Read `10-production-readiness.md` for Docker/systemd/health/telegram.

## Source

- This project: https://github.com/FerrPOINT/java-agent
