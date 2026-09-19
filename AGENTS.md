# AGENTS.md — Java Agent Development Guide

## Project Overview

Java-агент: Spring Boot 4.1 + Java 25 + Telegram bot + MCP. Gradle multi-project: `backend` (REST API, LLM, tools) + `telegram-bot` (61 команда, streaming, polling) + `cli` (92+ slash commands, REPL). Production (dev): 0.1.239; main накопил WP-3…WP-12 + upstream sync — ждёт релиза 0.1.239.

## Build & Test

```bash
cd /opt/dev/java-agent
./gradlew check                # 6221 tests, 0 failures
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
92 slash commands: `/new`, `/status`, `/compress`, `/undo`, `/checkpoint`,
`/rollback`, `/memory`, `/skills`, `/help`, `/exit`, `/diff`, `/credits`,
`/curator`, `/codex_runtime`, `/learn`, `/init`, `/refine`, `/heartbeat`,
`/loop`, `/suggestions`, etc.
SSE streaming для real-time token output. JLine autocomplete.

### Profiles

| Профиль | Назначение |
|---------|------------|
| `dev` | LiteLLM proxy / локальный endpoint, порт 8090, PostgreSQL localhost:5432 |
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
| `AGENT_MODEL_RETURN_THINKING` | reasoning_content echo для DeepSeek/Kimi/MiMo (default false) |
| `AGENT_MODEL_THINKING_FIELD_NAME` | Wire field name (default `reasoning_content`) |
| `AGENT_CORE_CODING_CONTEXT` | Coding posture: `auto`, `focus`, `on`, `off` (default `auto`) |
| `AGENT_VERIFY_ON_STOP` | Verify-on-stop guard (default false) |
| `AGENT_MCP_ENABLED` | Enable MCP clients including Repomix (default false) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | PostgreSQL |
| `AGENT_SERVER_PORT` | Порт (default 8090) |
| `AGENT_BROWSER_CDP_URL` | URL Chrome DevTools Protocol |
| `BOT_DISPLAY_TOOL_PROGRESS` | Tool progress bubbles: `hidden`, `compact`, `verbose` (default `hidden`) |

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
| Repomix | 1.18.0 (MCP server, npm) |
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

- **6221 тестов** (backend 4710 + bot 1511), 567 test files, 0 failures.
- Coverage gate: LINE ≥ 80%.
- Маппер-тесты: `Mappers.getMapper(X.class)`, edge cases (nulls, enums, empty collections).
- Service-тесты с `new`: вызывать `init()` после конструирования (для `@PostConstruct`).
- `@ExtendWith(MockitoExtension.class)` + `@Mock` для зависимостей; real mappers для mapping.
- `@Tag("slow")` для integration tests (Testcontainers, Spring context).
- `@Tag("live")` — отключены в CI, требуют external services.
- E2E: 28 HTTP scenarios + 35 CLI scenarios, run via `e2e/run_e2e.py` / `e2e/run_cli_e2e.py`.

### 6. Concurrency

- Virtual threads для tool execution (`Executors.newVirtualThreadPerTaskExecutor()`).
- `ConcurrentHashMap` для session-scoped state (interrupts, steer buffers, typing).
- Per-session `ReentrantLock` в `DefaultAgentRuntime` для concurrent turn protection.
- `ConcurrentLinkedDeque` для process output buffers (ProcessTool).
- `ScheduledExecutorService` для background tasks (review, typing, reconnect) — daemon threads.
- `TransactionTemplate` для programmatic transactions in streaming (не `@Transactional` — self-invocation).

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
- SOUL.md path: `agent.soul-md-path` in `application.yml` — configurable path to the soul/personality file (defaults to `~/.hermes/soul.md`).
- Profiles: `dev` (LiteLLM proxy), `noop` (H2 + mock LLM), `cli` (REPL), `prod` (production).
- Flyway migrations: `backend/src/main/resources/db/migration/` — 30 migrations (V1–V30).
- Repomix MCP: `agent.mcp.servers[0]` — stdio server `repomix --mcp --sandbox .`, enabled when `AGENT_MCP_ENABLED=true`. Config in `repomix.config.json`.
- Coding posture: `agent.core.coding-context` — `auto`/`focus`/`on`/`off`, controls workspace snapshot injection.
- Verify-on-stop: `agent.verify-on-stop.enabled` — nudges agent to run tests after code mutations (default false).
- Tool progress: `bot.display.tool-progress` — `hidden`/`compact`/`verbose`, controls per-tool Telegram bubbles (default `hidden`).
- Reasoning echo: `agent.model.return-thinking` + `agent.model.thinking-field-name` — reasoning_content echo-back for DeepSeek/Kimi/MiMo.

### 9. Bot Architecture

- 61 command, each `@Component` implementing `CommandHandler` interface.
- `CommandRegistry` — maps command names to handlers, resolves aliases.
- `GoalAutoContinueService` — automatically continues goal-driven agent loops until completion or user interrupt.
- `BotMessageProcessor` — central message dispatch.
- Streaming: `StreamEditor` edits messages in-place with rate limiting.
- Polling: `LongPollingService` + `ReconnectWatcher` (exponential backoff).
- Media: `MediaCache` (TTL 24h), `InboundMediaHandler` (photos, voice, documents).
- Session: `BotSessionEntity` — stores per-chat state (user, model, provider).
- Backend communication: `AgentBackendClient` — REST client with `@Qualifier("backendRestClient")`.
- Tool progress: gated on `bot.display.tool-progress` config (default `hidden` — no per-tool Telegram messages).

### 10. Hermes Parity (rounds 29–44)

| Round | Version | Feature |
|-------|---------|---------|
| 29 | 0.1.49 | ReplayCleanup — interrupted/dangling tool tails |
| 30 | 0.1.50 | Truncated tool call recovery (LENGTH + tool_calls) |
| 31 | 0.1.51 | ToolLoopGuardrail — dead-wired → wired |
| 32 | 0.1.52 | ToolResultStorage — dead-wired → wired |
| 33 | 0.1.53 | Re-stream data loss fix + close_interrupted_tool_sequence |
| 34 | 0.1.54 | ThinkingTimeoutGuidance + transport-kill patterns |
| 35 | 0.1.55 | CheckpointManager — dead-wired → wired |
| 36 | 0.1.56 | Streaming compression infinite loop cap |
| 37–38 | 0.1.57–58 | Verify-on-stop guard (sync + streaming) |
| 39–40 | 0.1.59–60 | CodingPostureResolver + platform propagation |
| 41 | 0.1.61 | reasoning_content echo-back (DeepSeek/Kimi/MiMo) |
| 42 | 0.1.62 | Deterministic call_id for blank tool-call IDs |
| 43 | 0.1.63 | Review prompts byte-identical to Hermes |
| 44 | 0.1.65 | Tool progress off + todo numeric ID |

### 10a. Full Parity Wave (docs/35, 2026-09-14)

| WP | Commit | Scope |
|----|--------|-------|
| WP-1 tail | `e8024563` | Delegate-completion coalescing (claimNextBatch, one target per batch, batch fencing) |
| WP-4 | `a38eba76`, `2aadcd7e`, `e2cfc5d3` | V56 profile runtime registry + audited config/env writes + dashboard ops ledger + session prune |
| WP-3 | `7ffebd98`, `1c29dd51` | V57 MCP config store + schema cache; V58 OAuth Code+PKCE + AES-GCM tokens + catalog install |
| WP-5 | `2d776ddb` | SkillHubInstaller staged+rollback, capability-based toolsets, ADR-014 |
| WP-6 | `4e304515` | V59 durable OpenAI Runs state machine + restart-safe event replay |
| WP-7 | `590a9fed` | V60 execute-code session_kernel (persistent driver, no local fallback) |
| WP-8 | `12d15b9f` | BrowserBackend port + explicit provider router + extension backend |
| WP-9 | `f974c67e`, `6d54c534` | V61 console tasks + cursor replay; PTY + pub channels (ADR-015) |
| WP-10 | `b06cedba` | MediaProvider capability matrix (honest provider exposure) |
| WP-11 | `04bfc6cb` | V62 attachment artifacts (dedupe, safe cache, receipts) |
| WP-12 | (this commit) | Remaining 501 inventory: describe-auto real (LLM), toolset env writes real (ProfileEnvStore), web-extract backend honest 400; docs/34/35 refreshed |

Remaining 501s are deliberate fail-closed guards: env reveal (ADR-013), shell hooks (no arbitrary commands), plugins (ADR-014), credential pool (deferred), unsupported prune filters (persisted-fields-only), standalone-deployment ObjectProvider guards.

### 11. Key Numbers

| Metric | Value |
|--------|-------|
| Java source files | 584 |
| Test files | 567 |
| `@RequiredArgsConstructor` | 210 files |
| `@Slf4j` | 210 files |
| `@Data` (JPA) | 20 files |
| MapStruct mappers | 10 |
| Bot commands | 61 |
| CLI slash commands | 92 |
| Backend endpoints | 140 |
| Flyway migrations | 30 (V1–V30) |
| Gradle modules | 4 (backend, telegram-bot, cli, shared) |
| Backend tests | 6834 |
| Bot tests | 1752 |
| CLI tests | 345 |
| E2E scenarios | 28 HTTP + 35 CLI + PG slowTests |
| Production version | 0.1.239 (dev) |

## Project Structure

```
backend/src/main/java/com/azhukov/agent/
├── api/           # REST controllers + DTOs + mappers (OpenAiMapper, DomainDtoMapper)
├── client/        # LLM clients (LangChain4j, NoOp, MCP, ReasoningEchoFamily)
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
├── commands/      # 61 command handler + CommandRegistry (+ GoalAutoContinueService)
├── config/        # BotProperties, BotConfig, DisplayConfig
├── core/          # BotMessageProcessor, AgentBackendClient, StreamingOrchestrator
├── formatting/    # Markdown converter, response filter
├── media/         # Media cache, inbound media, location
├── polling/       # Long polling, reconnect watcher
├── session/       # BotSessionEntity, BotSessionStore
├── streaming/     # StreamEditor, ToolEmojiMap
├── typing/        # TypingManager
└── webhook/       # Webhook secret validator

cli/src/main/java/com/azhukov/agent/cli/
├── BackendClient.java         # REST client to backend
├── CliApplication.java         # Spring Boot main
├── CliConfig.java             # RestClient + ObjectMapper beans
├── CliReplRunner.java         # CommandLineRunner entry
├── ReplLoop.java              # JLine interactive REPL
├── MarkdownRenderer.java      # ANSI color markdown
├── SlashCommandRegistry.java  # 92 slash commands
├── SlashCompleter.java        # JLine autocomplete
├── SlashAutoSuggest.java      # Inline suggestions
└── LearnInitCommands.java     # /learn, /init, /refine commands
```

## Deployment

- `docker-compose.yml` — production (порт 8080, PostgreSQL 5432)
- `docker-compose.local.yml` — local dev (порт 18090, PostgreSQL 18091)
- Dockerfile: `eclipse-temurin:25-jre-noble` + Chromium runtime deps
- Bot: separate Spring Boot app with shared PostgreSQL (Flyway `flyway_bot_schema_history`)
- Deploy ladder: bootJar → cp to `/opt/java-agent/lib/` → ln -sfn latest → systemctl restart → health check :8090/:8091

## Repomix

Repomix (github.com/yamadashy/repomix, MIT, npm v1.18.0) — repository-to-text packing for AI context.
Integrated as stdio MCP server providing 6 tools: `pack_codebase`, `pack_remote_repository`,
`read_repomix_output`, `grep_repomix_output`, `generate_skill`, `attach_packed_output`.
Config: `repomix.config.json` (Java/YAML/Gradle/SQL files, exclude build/).
Enabled when `AGENT_MCP_ENABLED=true`.

## Conventions

Full conventions doc: `backend/docs/conventions.md`

## Documentation & Test Standard (2026-09-20)

Полный текст: [docs/standards/documentation.md](docs/standards/documentation.md). Кратко:

**Javadoc**
- Классы ≥50 строк обязаны иметь class-level javadoc (1–3 строки контракта, WHY-суть; не пересказ имени).
- Методы — javadoc только на кросс-пакетных контрактах (port-интерфейсы, сервисные API, `@AgentTool`). Getters/`@Override`/Lombok — нет.
- Запрещены формулировки-вода: "responsible for", "provides functionality", "utility class", "This method is used to" (линтер DOC002).
- Комментарии в коде — только WHY (обходы багов, parity-ссылки `// Hermes parity: file.py:line`), без пересказа кода.
- `@AgentTool.description` — контракт для LLM: когда звать, что вернёт, когда падает. Образец — `TerminalTool`.

**Тесты (обязательное сопровождение кода)**
- Новая логика → unit-тесты с граничным и негативным кейсом.
- Багфикс → regression-тест, красный до фикса.
- Refuse/error-ветка backend → lane в бот-классификаторе `StreamingOrchestrator.toUserFriendlyError` + тест.
- Строковые сравнения провайдер-мета (`finishReason`) → `equalsIgnoreCase`; тест гоняет оба регистра (sync="LENGTH", streaming="length").
- Новый seam/setter → тест, доказывающий реальный вызов (dead-wiring класс багов).
- Запрещены change-detector'ы, тесты-читающие-исходники, моки там где дёшев real-path.

**Enforcement (CI, job `docs`)**
- `python3 scripts/check_docs_standard.py` — ratchet против `docs/standards/coverage-baseline.json` (DOC001/DOC002/DOC003 + LINE coverage per модуль). Нарастание нарушений или падение coverage ниже baseline = красный CI.
- Baseline повышается только осознанно: вместе с новыми тестами, `--update-baseline`, коммит "chore: raise docs baseline".
- Текущий долг: backend 513×DOC001 / bot 58 / cli 6; LINE backend 79.15% / bot 82.16%. Гасить пакетами из bottom-списка, не разом.
