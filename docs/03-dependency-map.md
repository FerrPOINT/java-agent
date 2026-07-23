# 03 — Python → Java Dependency Map

Target stack: **Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + H2 (noop profile) + OpenAI-compatible LLM endpoint**.

This document maps every *core* Hermes Python dependency to a Java alternative. Optional/provider-specific deps are listed but marked out-of-scope.

## 1. Core Dependencies

| Python library | Agent usage | Java replacement | Notes |
|----------------|--------------|--------------------|-------|
| `openai` | Chat completions, embeddings | **LangChain4j** `open-ai` module | Works with any OpenAI-compatible endpoint |
| `httpx` / `requests` | HTTP clients | **Java 11 `java.net.http.HttpClient`** | Virtual threads make HttpClient ergonomic |
| `pydantic` | Validation, JSON schemas | **Jackson** + **Jakarta Bean Validation** | No exact Pydantic clone in Java |
| `jinja2` | System/skill prompt templates | **Pebble** (`io.pebbletemplates:pebble`) | Jinja-like syntax |
| `pyyaml` | Config files | **SnakeYAML** (via Spring Boot) | Standard |
| `prompt_toolkit` | Interactive CLI REPL | **JLine** + **Picocli** | History, completion |
| `rich` | Colored terminal output | **Jansi** + Logback colorizers | Keep minimal |
| `tenacity` | Retry decorators | **Resilience4j** (`resilience4j-retry`) | Lightweight |
| `croniter` | Cron scheduling | **Quartz Scheduler** or Spring `@Scheduled` cron | Quartz for cron expressions |
| `Markdown` | Markdown rendering | **CommonMark** or **Flexmark** | Optional |
| `PyJWT` | JWT for gateway/auth | **JJWT** or **Nimbus JOSE** | Optional |
| `cryptography` | Crypto ops | **BouncyCastle** | Optional |
| `psutil` | Process/system info | JDK `ProcessHandle` + OSHI | Use ProcessHandle first |
| `websockets` | WebSocket clients | `java.net.http.WebSocket` | CDP browser client |
| `fastapi` + `uvicorn` | API server | **Spring Boot 4.1.0** Web MVC + virtual threads | Replaces both |
| `Pillow` | Image processing | Base64 passthrough; optional Thumbnailator | Vision mostly base64 |
| Browser automation | Playwright | Chromium launcher + CDP WebSocket client | Avoid 200 MB Playwright deps |
| `BeautifulSoup` / `html2text` | HTML parsing | `org.jsoup:jsoup` | Web extract fallback |
| `commons-lang3` / `commons-io` | String/file utilities | Apache Commons Lang3 / IO | Standard |
| `cron-utils` | Cron expression parsing | `com.cronutils:cron-utils` | Cron tool / gateway job parsing |

## 2. Provider / Optional Integrations (Out of Scope)

| Python library | Agent extra | Java replacement (if ever needed) |
|----------------|--------------|-----------------------------------|
| `anthropic` | `anthropic` | LangChain4j Anthropic module |
| `mistralai` | `mistral` | LangChain4j Mistral module |
| `boto3` | `bedrock` | AWS SDK v2 for Java |
| `google-auth` | `google` | Google API Client for Java |
| `azure-identity` | `azure-identity` | Azure Identity for Java |
| `honcho-ai` | `honcho` | REST wrapper or skip |
| `supermemory` | `supermemory` | REST wrapper or skip |
| `mem0ai` | `mem0` | REST wrapper or skip |
| `slack-bolt`, `discord.py`, `mautrix` | `messaging` | Slack SDK, Discord4J, Matrix — defer |
| `mcp` | `mcp` | **`io.modelcontextprotocol.sdk:mcp:2.0.0`** |
| `agent-client-protocol` | `acp` | ACP is new; custom or JetBrains lib when stable |
| `firecrawl-py` | `web` | Firecrawl REST API |
| `exa-py` | `exa` | Exa REST API |
| `fal-client` | `fal` | FAL REST API |
| `edge-tts` / `elevenlabs` | `tts-*` | ElevenLabs REST API; Edge TTS skip |
| `modal`, `daytona` | `modal`, `daytona` | Their respective Java/REST APIs |

## 3. Build / Test / Dev Dependencies

| Python tool | Java replacement |
|-------------|------------------|
| `pytest` + `pytest-asyncio` | **JUnit 5** + **AssertJ** + **Awaitility** |
| `ruff` | **Spotless** + **Checkstyle** |
| `mypy` | Checker Framework or strict null checks in Java |
| `uv` / `pip` | **Gradle** |
| `setuptools` entry points | Spring Boot `spring.factories` / ServiceLoader |

## 4. Decision Notes

- **LangChain4j vs Spring AI:** LangChain4j is more mature and provider-agnostic. Default provider is **OpenAI-compatible** via `langchain4j-open-ai`; dev default endpoint is Ollama-compatible.
- **WebFlux vs Virtual Threads:** Chose **Spring MVC + virtual threads** (`spring.threads.virtual.enabled=true`). Agent tools are mostly blocking (JDBC, CDP, shell); reactive types would infect the whole stack.
- **Validation:** Bean Validation + Jackson. Tool schemas generated via introspection of `@ToolParam` annotations.
- **HTTP client:** `java.net.http.HttpClient`. Switch to OkHttp if proxy/SOCKS needs exceed JDK support.
- **Persistence:** PostgreSQL via JDBC + Flyway for dev/prod; H2 in-memory for `noop` profile and tests.
- **Agent name:** Configurable via `agent.name`; defaults to `Джава агент`.

## 5. Implementation Status

- **Phase 0**: config, Flyway schema, build ✅
- **Phase 1 skeleton**: domain model, contracts, tool registry, NoOp model, happy path HTTP endpoint ✅
- **Phase 1.5**: real `LangChain4jModelClient`, JPA persistence, tool skeletons, CLI REPL, gateway `/v1/chat/completions`, approvals gate ✅
- **Phase 2**: browser/vision tools, MCP client, skill/memory tools, delegation ✅
- **Phase 3 (current)**: tests, NoOp profiles, docs, production readiness, CLI hardening
