# Hermes Java Agent — Prototype Documentation

## 1. Purpose

This directory (`/opt/dev/java-agent/`) contains a **Java-focused prototype** of the [NousResearch Hermes Agent](https://github.com/NousResearch/hermes-agent).

Goal: extract the **minimal core** of Hermes that can be reimplemented in Java, document which parts are essential, which are platform/integration-specific, and map each Python dependency to a Java alternative.

The `prototype/` directory holds clones of the relevant Hermes repositories. The `docs/` directory is the single source of truth for the Java porting plan.

## 2. Repository Layout

```
/opt/dev/java-agent/
├── prototype/              # cloned Hermes repositories
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
    ├── 04-proposed-java-structure.md     # draft Maven/Gradle module layout
    ├── 05-migration-notes.md             # tricky parts and design decisions
    └── 06-vision-browser.md              # vision + browser re-added to scope
```

## 3. Quick Decisions

| Decision | Value | Rationale |
|----------|-------|-----------|
| Language | Java 21+ (LTS) | virtual threads, structured concurrency, modern HTTP client |
| Build tool | Maven (or Gradle) | standard for JVM ecosystem |
| Web server | Spring Boot 3.4+ | replaces FastAPI + Uvicorn for API server and gateway management |
| LLM abstraction | LangChain4j or Spring AI | both support OpenAI-compatible endpoints, Ollama, Anthropic, etc. |
| Validation | Jakarta Bean Validation + Jackson | replaces Pydantic |
| Templating | Pebble (Jinja-like) or Carrot | for system prompts and skill templates |
| HTTP client | Java 11 `HttpClient` + OkHttp fallback | replaces `httpx` / `requests` |
| WebSocket | `java.net.http.WebSocket` or Tyrus | replaces `websockets` |
| Persistence | SQLite (JDBC) or H2 for tests | Hermes uses SQLite/PostgreSQL |
| Async | `CompletableFuture` / virtual threads | replaces `asyncio` |
| MCP | `io.modelcontextprotocol.sdk:mcp` | official Anthropic Java SDK |
| ACP | custom/awaiting stable SDK | ACP is editor/agent protocol from Zed/JetBrains |

## 4. Next Steps

1. Read `01-scope.md` to confirm boundaries.
2. Read `02-core-architecture.md` for the runtime data flow.
3. Read `03-dependency-map.md` for the Java stack choices.
4. Read `04-proposed-java-structure.md` for the module skeleton.
5. Read `06-vision-browser.md` for vision and browser porting details.
6. Start implementation from `hermes-core` module.

## 5. Contact / Source

- Upstream: https://github.com/NousResearch/hermes-agent
- This prototype: `/opt/dev/java-agent/`
