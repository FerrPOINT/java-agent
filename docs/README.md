# Java Agent — Prototype Documentation

## 1. Purpose

This directory (`/opt/dev/java-agent/`) contains a **Java-focused prototype** inspired by the [NousResearch Hermes Agent](https://github.com/NousResearch/hermes-agent).

Goal: extract the **minimal core** of an agent runtime that can be reimplemented in Java, document which parts are essential, which are platform/integration-specific, and map each Python dependency to a Java alternative.

The `prototype/` directory holds clones of the relevant Hermes repositories for reference. The `docs/` directory is the single source of truth for the Java porting plan.

## 2. Repository Layout

```
/opt/dev/java-agent/
├── prototype/              # cloned reference repositories
│   ├── hermes-agent/                   # core runtime (693 MB)
│   ├── Hermes-Function-Calling/        # function-calling dataset/format
│   ├── hermes-example-plugins/         # plugin examples
│   ├── hermes-paperclip-adapter/       # adapter patterns
│   ├── hermes-telegram-business/       # Telegram business gateway
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
    └── 06-vision-browser.md              # vision + browser porting details
```

## 3. Quick Decisions

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
| LLM provider | **Ollama** local endpoint (`http://localhost:11434`) | default; OpenAI-compatible endpoints via LangChain4j |
| MCP | `io.modelcontextprotocol.sdk:mcp:2.0.0` | official Anthropic Java SDK |
| CLI | Picocli 4.7.7 + JLine 4.3.1 | REPL |

## 4. Agent Name

The agent name is configurable via `AGENT_NAME` (defaults to `Джава агент`).

```yaml
agent:
  name: ${AGENT_NAME:Джава агент}
```

## 5. Build & Run

```bash
./gradlew build
./gradlew bootRun
```

Health endpoints:
- `GET http://localhost:8080/api/v1/health`
- `GET http://localhost:8080/actuator/health`

## 6. Next Steps

1. Read `01-scope.md` to confirm boundaries.
2. Read `02-core-architecture.md` for the runtime data flow.
3. Read `03-dependency-map.md` for the Java stack choices.
4. Read `04-proposed-java-structure.md` for the module skeleton.
5. Read `06-vision-browser.md` for vision and browser porting details.
6. Start implementation from the core module.

## 7. Contact / Source

- Upstream reference: https://github.com/NousResearch/hermes-agent
- This prototype: `/opt/dev/java-agent/`
