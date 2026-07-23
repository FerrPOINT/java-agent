# Java Agent — Prototype Documentation

## 1. Purpose

This directory (`/opt/dev/java-agent/`) contains a **Java-focused prototype** inspired by the [NousResearch Hermes Agent](https://github.com/NousResearch/hermes-agent).

Goal: extract the **minimal core** of an agent runtime that can be reimplemented in Java, document which parts are essential, which are platform/integration-specific, and map each Python dependency to a Java alternative.

The `prototype/` directory holds clones of the relevant agent repositories for reference. The `docs/` directory is the single source of truth for the Java porting plan.

## 2. Repository Layout

```
/opt/dev/java-agent/
├── prototype/              # cloned reference repositories
│   ├── hermes-agent/                   # core runtime (693 MB)
│   ├── agent-Function-Calling/        # function-calling dataset/format
│   ├── agent-example-plugins/         # plugin examples
│   ├── agent-paperclip-adapter/       # adapter patterns
│   ├── agent-telegram-business/       # Telegram business gateway
│   ├── agent-governance-toolkit/       # governance/sandbox primitives
│   ├── OpenShell/                      # sandboxing runtime
│   ├── OpenShell-Community/            # community sandbox configs
│   ├── nousflash-agents/               # agent experiments
│   ├── wterm/                          # terminal UI component
│   └── hermes-agent-ci-infra/          # CI/deployment infra
└── docs/                   # this documentation
    ├── README.md                         # this file
    ├── 01-scope.md                       # what we port, what we skip
    ├── 02-core-architecture.md           # essential modules and data flow
    ├── 03-dependency-map.md              # Python → Java library mapping
    ├── 04-proposed-java-structure.md     # draft module layout
    ├── 05-migration-notes.md             # tricky parts and design decisions
    ├── 06-vision-browser.md              # vision + browser porting details
    └── 07-application-design.md          # Java application design (current)
```

## 3. Implementation Status

- **Phase 0**: config, Flyway schema, build ✅
- **Phase 1 skeleton**: domain model, contracts, tool registry, NoOp model, happy path HTTP endpoint ✅
- **Phase 1.5**: real `LangChain4jModelClient`, JPA persistence, tool skeletons, CLI REPL, gateway `/v1/chat/completions`, approvals gate ✅

## 4. Quick Decisions

| Decision | Value | Rationale |
|----------|-------|-----------|
| Language | **Java 25 LTS** | virtual threads, structured concurrency, modern HTTP client |
| Build tool | **Gradle 9.6.1** with Groovy DSL | user requirement |
| Web server | **Spring Boot 4.1.0** | replaces FastAPI + Uvicorn |
| Concurrency | **Virtual threads** (`spring.threads.virtual.enabled=true`) | simpler than WebFlux for I/O-bound tools |
| LLM abstraction | **LangChain4j 1.18.0** | provider-agnostic, supports OpenAI-compatible endpoints |
| Validation | Jakarta Bean Validation + Jackson | replaces Pydantic |
| Templating | **Pebble 4.1.2** | Jinja-like |
| HTTP client | `java.net.http.HttpClient` + OkHttp fallback | replaces `httpx` / `requests` |
| WebSocket | `java.net.http.WebSocket` | CDP, replaces `websockets` |
| Persistence | PostgreSQL (JDBC) + Flyway | dev DB is existing Postgres container |
| LLM provider | **OpenAI-compatible** (default local Ollama `http://localhost:11434/v1`) | any provider via `langchain4j-open-ai` |
| MCP | `io.modelcontextprotocol.sdk:mcp:2.0.0` | official Anthropic Java SDK |
| CLI | Picocli 4.7.7 + JLine 4.3.1 | REPL |

## 5. Agent Name

The agent name is configurable via `agent.name` (defaults to `Джава агент`).

## 6. Build & Run

```bash
cd backend
./gradlew build
./gradlew bootRun --args='--spring.profiles.active=dev --spring.datasource.password=project_workflow'
```

Health endpoints:
- `GET http://localhost:8080/api/v1/health`
- `GET http://localhost:8080/actuator/health`

Chat endpoint:
```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"message":"Read README"}' \
  http://localhost:8080/api/v1/agent/chat
```

## 7. Next Steps

1. Read `01-scope.md` to confirm boundaries.
2. Read `02-core-architecture.md` for the runtime data flow.
3. Read `03-dependency-map.md` for the Java stack choices.
4. Read `04-proposed-java-structure.md` for the module skeleton.
5. Read `06-vision-browser.md` for vision and browser porting details.
6. Start implementation from the core module.

## 7. Contact / Source

- Upstream reference: https://github.com/NousResearch/hermes-agent
- This prototype: `/opt/dev/java-agent/`

## Readiness status

Last audit: all upstream Hermes tool schemas and config sections reviewed. Known out-of-scope areas documented in `01-scope.md`. Decisions and Phase 0 status are tracked in `07-application-design.md` sections 26–28.

