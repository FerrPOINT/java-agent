# AGENTS.md — Java Agent Development Guide

## Project Overview

Java-агент: Spring Boot 4.1 + Java 25 + Telegram bot + MCP. Gradle multi-project: `backend` (REST API, LLM, tools) + `telegram-bot` (56 команд, streaming, polling).

## Build & Test

```bash
cd /opt/dev/java-agent
./gradlew check                # 1500 tests, 0 failures
./gradlew compileJava          # compile only
./gradlew bootJar              # build JAR
./gradlew slowTest             # @Tag("slow") integration tests
./gradlew jacocoTestReport     # coverage report
```

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

- **1500 тестов**, 279 test files, 0 failures.
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
- Approval gate: destructive tools требуют confirmation (`ApprovalGate`).
- Telegram auth: `agent.gateway.telegram.allowed-user-ids`, `allowed-usernames`, `allow-by-default`.

### 8. Configuration

- All settings in `application.yml` with env var overrides (`${ENV:default}`).
- `AgentProperties` (`@ConfigurationProperties`) — backend, `BotProperties` — telegram-bot.
- Profiles: `dev` (Ollama Cloud), `noop` (H2 + mock LLM), `cli` (REPL), `prod` (production).
- Flyway migrations: `backend/src/main/resources/db/migration/`.

### 9. Bot Architecture

- 56 commands, each `@Component` implementing `CommandHandler` interface.
- `CommandRegistry` — maps command names to handlers, resolves 10 aliases.
- `BotMessageProcessor` — central message dispatch (18 dependencies, `@PostConstruct` for debouncer wiring).
- Streaming: `StreamEditor` edits messages in-place with rate limiting.
- Polling: `LongPollingService` + `ReconnectWatcher` (exponential backoff).
- Media: `MediaCache` (TTL 24h), `InboundMediaHandler` (photos, voice, documents).
- Session: `BotSessionEntity` — stores per-chat state (user, model, provider).
- Backend communication: `AgentBackendClient` — REST client with `@Qualifier("backendRestClient")`.

### 10. Key Numbers

| Metric | Value |
|--------|-------|
| Java files | 634 |
| Test files | 279 |
| Total tests | 1500 |
| `@RequiredArgsConstructor` | 159 files |
| `@Slf4j` | 83 files |
| `@Data` (JPA) | 16 files |
| MapStruct mappers | 4 |
| Mapper tests | 5 |
| Bot commands | 56 (+ 10 aliases) |
| Backend endpoints | 50 |
| MCP files | 21 |
| LINE coverage | 80.4% |

## Project Structure

```
backend/src/main/java/com/azhukov/agent/
├── api/           # REST controllers + DTOs + mappers (OpenAiMapper, DomainDtoMapper)
├── cli/           # Picocli REPL
├── client/        # LLM clients (LangChain4j, NoOp, MCP)
├── config/        # AgentProperties, MapStructConfig, beans
├── core/          # Domain: AgentRuntime, tools, context, memory, skills, state, security
├── gateway/       # Telegram/webhook adapters + routing
├── persistence/   # JPA entities + repositories + mappers + Flyway
├── security/      # SSRF protection, safety validators
├── service/       # AgentRuntimeService, AgentStreamingService, TTS, transcription
└── tools/         # @AgentTool implementations

telegram-bot/src/main/java/com/azhukov/agent/bot/
├── api/           # Bot API DTOs
├── auth/          # Authorization, pairing
├── batch/         # Text/photo batch debouncers
├── client/        # TelegramClient, RestClient config
├── commands/      # 56 command handlers + CommandRegistry
├── config/        # BotProperties, BotConfig
├── core/          # BotMessageProcessor, AgentBackendClient
├── formatting/    # Markdown converter, response filter
├── media/         # Media cache, inbound media, location
├── polling/       # Long polling, reconnect watcher
├── session/       # BotSessionEntity, BotSessionStore
├── streaming/     # StreamEditor (edit-message streaming)
├── typing/        # TypingManager
└── webhook/       # Webhook secret validator
```

## Deployment

- `docker-compose.yml` — production (порт 8080, PostgreSQL 5432)
- `docker-compose.local.yml` — local dev (порт 18090, PostgreSQL 18091)
- Dockerfile: `eclipse-temurin:25-jre-noble` + Chromium runtime deps
- Bot: separate Spring Boot app with shared PostgreSQL (Flyway `flyway_bot_schema_history`)

## Conventions

Full conventions doc: `backend/docs/conventions.md`