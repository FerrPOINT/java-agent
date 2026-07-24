# Java Agent — Prototype Documentation

## 1. Purpose

This directory (`/opt/dev/java-agent/`) contains a **Java-focused port** of the [NousResearch Hermes Agent](https://github.com/NousResearch/hermes-agent) core.

Goal: extract the **minimal stable core** of an agent runtime that can run in Java, document which parts are essential, which are platform/integration-specific, and map each Python dependency to a Java alternative.

The `prototype/` directory holds clones of the relevant Hermes repositories for reference. The `backend/` directory is the actual application. The `docs/` directory is the single source of truth for the porting plan.

## 2. Repository Layout

```
/opt/dev/java-agent/
├── backend/                # Gradle + Spring Boot 4 application
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/com/azhukov/agent/
│       ├── api/            # REST controllers
│       ├── cli/            # Picocli / JLine REPL
│       ├── client/         # LLM clients (LangChain4j, NoOp)
│       ├── config/         # AgentProperties, beans
│       ├── core/           # domain layer
│       ├── persistence/    # JPA entities + repositories
│       └── tools/          # @AgentTool implementations
├── prototype/              # cloned reference repositories (not in git)
│   ├── hermes-agent/
│   ├── Hermes-Function-Calling/
│   ├── hermes-example-plugins/
│   ├── hermes-paperclip-adapter/
│   ├── hermes-telegram-business/
│   ├── agent-governance-toolkit/
│   ├── OpenShell/
│   ├── OpenShell-Community/
│   ├── nousflash-agents/
│   ├── wterm/
│   └── hermes-agent-ci-infra/
└── docs/                   # this documentation
    ├── README.md                         # this file
    ├── 01-scope.md                       # what we port, what we skip
    ├── 02-core-architecture.md           # essential modules and data flow
    ├── 03-dependency-map.md              # Python → Java library mapping
    ├── 04-proposed-java-structure.md     # module layout
    ├── 05-migration-notes.md             # tricky parts and design decisions
    ├── 06-vision-browser.md              # vision + browser porting details
    ├── 07-mcp-client-status.md           # MCP client state and config
    ├── 08-browser-vision.md              # browser/vision endpoints
    ├── 09-builtin-tools.md               # matrix of implemented tools
    └── 10-production-readiness.md        # docker, systemd, logging, health

## 3. Implementation Status

- **Phase 0**: Gradle build, Flyway PostgreSQL schema, config ✅
- **Phase 1 skeleton**: domain model, contracts, tool registry, NoOp model, happy path HTTP endpoint ✅
- **Phase 1.5**: real `LangChain4jModelClient`, JPA persistence, tool skeletons, CLI REPL, gateway `/v1/chat/completions`, approvals gate ✅
- **Phase 2**: browser/vision tools, MCP client, skill/memory tools, delegation ✅
- **Phase 3 (current)**: tests, NoOp profiles, docs, production readiness, CLI hardening

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
| HTTP client | `java.net.http.HttpClient` + jsoup | replaces `httpx` / `requests` |
| WebSocket | `java.net.http.WebSocket` | CDP, replaces `websockets` |
| Persistence | PostgreSQL (JDBC) + Flyway | dev DB is existing Postgres container; H2 for `noop` |
| LLM provider | **OpenAI-compatible** (default local Ollama `http://localhost:11434/v1`) | configurable endpoint |
| MCP | `io.modelcontextprotocol.sdk:mcp:2.0.0` | official Anthropic Java SDK |
| CLI | Picocli 4.7.7 + JLine 4.3.1 | REPL |

## 5. Agent Name

The agent name is configurable via `agent.name` (defaults to `Джава агент`).

## 6. Build & Run

```bash
cd backend
./gradlew test
./gradlew bootJar

# Dev with real LLM
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  --spring.datasource.password=project_workflow

# NoOp / offline
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=noop
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

## 7. Next Steps

1. Read `01-scope.md` to confirm boundaries.
2. Read `02-core-architecture.md` for the runtime data flow.
3. Read `03-dependency-map.md` for the Java stack choices.
5. Read `04-proposed-java-structure.md` for the module skeleton.
6. Read `06-vision-browser.md` for vision and browser porting details.
7. Read `07-mcp-client-status.md` for MCP configuration.
8. Read `08-browser-vision.md` for HTTP examples.
9. Read `09-builtin-tools.md` for the implemented tool matrix and security defaults.
10. Read `10-production-readiness.md` for Docker/systemd/health.

## Source

- Upstream reference: https://github.com/NousResearch/hermes-agent
- This port: https://github.com/FerrPOINT/java-agent
