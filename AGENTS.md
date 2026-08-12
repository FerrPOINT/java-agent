# AGENTS.md — Java Agent Development Guide

## Project Overview

Java-агент: Spring Boot 4.1 + Java 25 + Telegram bot + MCP. Gradle multi-project: `backend` (REST API, LLM, tools) + `telegram-bot` (58 команд, streaming, polling).

## Build & Test

```bash
cd /opt/dev/java-agent
./gradlew check                # 5229 tests, 0 failures
./gradlew compileJava          # compile only
./gradlew bootJar              # build JAR
./gradlew slowTest             # @Tag("slow") integration tests
./gradlew jacocoTestReport     # coverage report
```

## Run

### `java -jar` (рекомендуется для реальных LLM-вызовов)

```bash
cd backend
./gradlew bootJar
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  --server.port=8090
```

Чистая JVM, нет Gradle daemon overhead, полный контроль памяти.

### `./gradlew bootRun` (для dev-итерации с noop/dev)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=noop'
```

`maxHeapSize = 2g` установлен в `build.gradle` как страховка от OOM.
Gradle daemon (~500MB–1GB) + app heap разделят одну JVM — при
больших LLM-payloads памяти может не хватить. Для реальных нагрузок
используй `java -jar`.

### CLI (standalone, общается с backend через REST)

```bash
cd cli
./gradlew bootJar
java -jar build/libs/java-agent-cli-0.0.1-SNAPSHOT.jar \
  --backend.url=http://localhost:8090 \
  --session.id=$(uuidgen)
```

CLI — отдельный Spring Boot модуль, не зависит от backend кода.
74 slash commands: `/new`, `/status`, `/compress`, `/undo`, `/checkpoint`,
`/rollback`, `/memory`, `/skills`, `/help`, `/exit`, `/diff`, `/credits`,
`/curator`, `/codex_runtime`, etc.
SSE streaming для real-time token output. JLine autocomplete.

### Profiles

| Профиль | Назначение |
|---------|------------|
| `dev` | Ollama Cloud / локальный endpoint, порт 8090, PostgreSQL localhost:5432 |
| `noop` | LLM-заглушка + H2 in-memory; для тестов и offline-разработки |
| `cli` | Активирует Picocli REPL |
| `prod` | Production endpoint (OpenAI / совместимый), INFO-логи |

### Key env vars

| Переменная | Назначение |
|------------|------------|
| `AGENT_MODEL_PROVIDER` | `openai-compatible`, `noop` |
| `AGENT_MODEL_BASE_URL` | URL endpoint |
| `AGENT_MODEL_API_KEY` | API-ключ |
| `AGENT_MODEL_NAME` | Название модели |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | PostgreSQL |
| `AGENT_SERVER_PORT` | Порт (default 8090) |
| `AGENT_BROWSER_CDP_URL` | URL Chrome DevTools Protocol |

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 25 LTS |
| Spring Boot | 4.1.0 |
| Gradle | 9.6.1 (Groovy DSL) |
| Lombok | 1.18.38 |
| MapStruct | 1.6.3 |
| LangChain4j | 1.18.0 |
| MCP Java SDK | 2.0.0 |
| PostgreSQL | 16 |
| Flyway | 12.4.0 |

## Architecture Best Practices

### 1. Layering

```
api (controllers + DTOs)
  ↓ mapper (MapStruct)
core (domain: AgentRuntime, tools, models)
  ↓ mapper (MapStruct)
persistence (JPA entities + repositories)
```

- Controllers не работают с entities напрямую — через мапперы.
- Domain models (`core.model`) — records, не entities.
- Bot layer — исключение: entities используются как domain models (нет отдельного mapping layer).

### 2. Lombok

**Mandatory:**
- `@RequiredArgsConstructor` + `@Slf4j` на всех Spring beans с final-зависимостями и чистыми конструкторами.
- `@Data` на JPA entities (`@Entity` + `@Data`).
- `record` для DTO и immutable core models.

**Forbidden:**
- Конструкторы с логикой (`HttpClient.new`, `Executors.new`, factory methods).
- Null-checks в конструкторе (`x == null ? "" : x`).
- `@Qualifier` на параметр (Lombok не поддерживает → manual constructor).
- Множественные конструкторы.

**`@PostConstruct` для derived fields:**
```java
@RequiredArgsConstructor
public class WebSearchTool {
    private final AgentProperties agentProperties;  // injected
    private int configuredLimit;                      // derived → non-final

    @PostConstruct
    void init() {
        configuredLimit = agentProperties.getWeb().getSearchResults();
    }
}
```
В unit-тестах: `new WebSearchTool(...); tool.init();`

**Inline init для runtime state:**
```java
private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "background-review");
    t.setDaemon(true);
    return t;
});
```

**Field order = constructor param order:** `@RequiredArgsConstructor` генерирует конструктор в порядке объявления полей. Проверяй порядок полей при миграции — call sites (тесты, `@Bean` methods) должны совпадать.

### 3. MapStruct

**Mandatory:**
- `@Mapper(config = MapStructConfig.class)` — `componentModel = "spring"`, `unmappedTargetPolicy = ERROR`.
- Мапперы в `persistence.mapper` (entity ↔ domain) или `api.mapper` (domain ↔ DTO).
- Unit-тесты: `Mappers.getMapper(X.class)` (не mock, не `@SpringBootTest`).
- В service-тестах: real mappers через `Mappers.getMapper(...)`.

**Mapper catalog:**
| Mapper | Direction |
|--------|-----------|
| `MessageMapper` | `MessageEntity` ↔ `Message` |
| `SessionEntityMapper` | `SessionEntity` ↔ `Session` |
| `DomainDtoMapper` | `Session` → `SessionSummaryDto` |
| `OpenAiMapper` | Domain ↔ OpenAI DTOs (messages, tools, responses) |

**Conversion rules:**
- `roleToString(Role)` → `role.name().toLowerCase()` (e.g., "user", "assistant")
- `stringToRole(String)` → `Role.valueOf(role.toUpperCase())`, null → `Role.USER`
- `@Named` helper methods для non-trivial conversion
- `default` methods для conditional logic (switch, null checks, factory)

**Forbidden:**
- Mock мапперов в unit-тестах.
- Маппинг в bot layer (entities = domain models).
- `buildResponse` в сервисах, где DTO собирается из множества источников — ручная сборка.

### 4. Spring Patterns

**Self-invocation pitfall:**
`@Transactional` на методе, вызванном из того же класса, молча обходится (proxy не engaged). Fix: extract в отдельный `@Component`.

**`@Qualifier` + `@RequiredArgsConstructor`:**
Несовместимы. Если нужен `@Qualifier` на конструктор-параметр → manual constructor.

**Virtual threads:**
`ToolExecutionService` использует `Executors.newVirtualThreadPerTaskExecutor()` для параллельных tool calls.

**Health readiness:**
Только `db` — LLM/CDP сбои не блокируют readiness.

**Graceful shutdown:**
`server.shutdown: immediate` — workaround для Spring Boot 4.1.0 бага.

### 5. Testing

- **5229 тестов**, 425 test files, 0 failures.
- Coverage gate: LINE ≥ 80%.
- Маппер-тесты: `Mappers.getMapper(X.class)`, edge cases (nulls, enums, empty collections).
- Service-тесты с `new`: вызывать `init()` после конструирования (для `@PostConstruct`).
- `@ExtendWith(MockitoExtension.class)` + `@Mock` для зависимостей; real mappers для mapping.
- `@Tag("slow")` для integration tests (Testcontainers, Spring context).
- `@Tag("live")` — отключены в CI, требуют external services.

### 6. Concurrency

- Virtual threads для tool execution (`Executors.newVirtualThreadPerTaskExecutor()`).
- `ConcurrentHashMap` для session-scoped state (interrupts, steer buffers, typing).
- `ScheduledExecutorService` для background tasks (review, typing, reconnect) — daemon threads.
- `TransactionTemplate` для programmatic transactions в streaming (не `@Transactional` — self-invocation).

### 7. Security

- SSRF protection: `SsrfSafeHttpClient` блокирует private/local IPs.
- File safety: `DefaultFileSafety` проверяет пути against allowed list.
- URL safety: `DefaultUrlSafety` валидирует URLs.
- Secret redaction: `DefaultRedactor` маскирует secrets в output.
- `redact-secrets` config (`agent.security.redact-secrets`): toggle masking of API keys, tokens, passwords in logs/output.
- `redact-pii` config (`agent.security.redact-pii`): toggle masking of PII (emails, phone numbers, IPs) in logs/output.
- Approval gate: destructive tools требуют confirmation (`ApprovalGate`).
- Telegram auth: `agent.gateway.telegram.allowed-user-ids`, `allowed-usernames`, `allow-by-default`.

### 8. Configuration

- All settings in `application.yml` with env var overrides (`${ENV:default}`).
- `AgentProperties` (`@ConfigurationProperties`) — backend, `BotProperties` — telegram-bot.
- `RuntimeConfigService` — allows runtime override of the codex model without restart (via `/codex_runtime` CLI command or REST endpoint).
- `CreditsDto` — DTO for the `/credits` endpoint, reports token/credit usage per session.
- Curator config: `agent.curator.*` in `application.yml` controls the auto-curated kanban board (columns, WIP limits, labels).
- Memory limits: `agent.memory.char-limit` and `agent.user.char-limit` in `application.yml` cap memory and user input sizes.
- Profiles: `dev` (Ollama Cloud), `noop` (H2 + mock LLM), `cli` (REPL), `prod` (production).
- Flyway migrations: `backend/src/main/resources/db/migration/` — 23 migrations (V1–V23).

### 9. Bot Architecture

- 58 commands, each `@Component` implementing `CommandHandler` interface.
- `CommandRegistry` — maps command names to handlers, resolves 10 aliases.
- `GoalAutoContinueService` — automatically continues goal-driven agent loops until completion or user interrupt.
- `BotMessageProcessor` — central message dispatch (18 dependencies, `@PostConstruct` for debouncer wiring).
- Streaming: `StreamEditor` edits messages in-place with rate limiting.
- Polling: `LongPollingService` + `ReconnectWatcher` (exponential backoff).
- Media: `MediaCache` (TTL 24h), `InboundMediaHandler` (photos, voice, documents).
- Session: `BotSessionEntity` — stores per-chat state (user, model, provider).
- Backend communication: `AgentBackendClient` — REST client with `@Qualifier("backendRestClient")`.

### 10. Key Numbers

| Metric | Value |
|--------|-------|
| Java source files | 468 |
| Test files | 425 |
| `@RequiredArgsConstructor` | 159 files |
| `@Slf4j` | 88 files |
| `@Data` (JPA) | 16 files |
| MapStruct mappers | 5 |
| Bot commands | 58 (+ 10 aliases) |
| CLI slash commands | 74 |
| Backend endpoints | 103 |
| Flyway migrations | 23 (V1–V23) |
| Gradle modules | 3 (backend, telegram-bot, cli) |

## Project Structure

```
backend/src/main/java/com/azhukov/agent/
├── api/           # REST controllers + DTOs + mappers (OpenAiMapper, DomainDtoMapper)
├── client/        # LLM clients (LangChain4j, NoOp, MCP)
├── config/        # AgentProperties, MapStructConfig, beans
├── core/          # Domain: AgentRuntime, tools, context, memory, skills, state, security
├── gateway/       # Telegram/webhook adapters + routing
├── persistence/   # JPA entities + repositories + mappers + Flyway
├── security/      # SSRF protection, safety validators, redacting log layout
├── service/       # AgentRuntimeService, AgentStreamingService, TTS, transcription
└── tools/         # @AgentTool implementations

telegram-bot/src/main/java/com/azhukov/agent/bot/
├── api/           # Bot API DTOs
├── auth/          # Authorization, pairing
├── batch/         # Text/photo batch debouncers
├── client/        # TelegramClient, RestClient config
├── commands/      # 56 command handlers + CommandRegistry (+ GoalAutoContinueService)
├── config/        # BotProperties, BotConfig
├── core/          # BotMessageProcessor, AgentBackendClient
├── formatting/    # Markdown converter, response filter
├── media/         # Media cache, inbound media, location
├── polling/       # Long polling, reconnect watcher
├── session/       # BotSessionEntity, BotSessionStore
├── streaming/     # StreamEditor (edit-message streaming)
├── typing/        # TypingManager
└── webhook/       # Webhook secret validator

cli/src/main/java/com/azhukov/agent/cli/
├── BackendClient.java         # REST client to backend
├── CliApplication.java         # Spring Boot main
├── CliConfig.java             # RestClient + ObjectMapper beans
├── CliReplRunner.java         # CommandLineRunner entry
├── ReplLoop.java              # JLine interactive REPL
├── MarkdownRenderer.java      # ANSI color markdown
├── SlashCommandRegistry.java  # 74 slash commands
├── SlashCompleter.java        # JLine autocomplete
└── SlashAutoSuggest.java      # Inline suggestions
```

## Deployment

- `docker-compose.yml` — production (порт 8080, PostgreSQL 5432)
- `docker-compose.local.yml` — local dev (порт 18090, PostgreSQL 18091)
- Dockerfile: `eclipse-temurin:25-jre-noble` + Chromium runtime deps
- Bot: separate Spring Boot app with shared PostgreSQL (Flyway `flyway_bot_schema_history`)

## Conventions

Full conventions doc: `backend/docs/conventions.md`