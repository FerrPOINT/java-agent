# AGENTS.md — Java Agent Development Guide

## Make и время сборки

Обычный Make без заданного `SOURCE_DATE_EPOCH` использует стабильный dev epoch
`0`. Install/release запрашивают `--candidate-epoch`; явный epoch сохраняется.
Не переносить dev epoch на native/release QA и не переиздавать старый receipt
для нового commit. Команды и проверяемые границы — в [TESTING](docs/TESTING.md).

## Project Overview

Java-агент: Spring Boot 4.1 + Java 25 + Telegram bot + MCP. Gradle multi-project: `backend` (REST API, LLM, tools) + `telegram-bot` (61 команда, streaming, polling) + `cli` (92+ slash commands, REPL). Production (dev): 0.1.239; main накопил WP-3…WP-12 + upstream sync — ждёт релиза 0.1.239.

## Build & Test

При работе над повторным использованием Main/JAR читать раздел «Private Main
producer» в [TESTING](docs/TESTING.md). `baseProductionRole=cli-main-v1` —
внутренний неподключённый этап Base, без unit/candidate grant. Обычные команды
сохраняют unit prerequisites; metadata fixture не заменяет application qualification.

CI and Docker use Temurin **25.0.4.1+1-LTS**; all Docker stages pin image
digests. Use committed wrappers: **9.6.1** at the repository root for
bot/CLI and **9.8.0** from `backend/` for the standalone backend gates.

```bash
cd /opt/dev/java-agent
# Ordinary build: reviewed isolated units and the candidate JAR.
bash scripts/quality-gradle.sh --backend :test :bootJar --no-daemon --max-workers=2
bash scripts/quality-gradle.sh --quality-root :telegram-bot:test :cli:test --no-daemon --max-workers=2
# Manual release only: integration, slow checks and full coverage in one graph.
python3 scripts/release_verify.py
# During implementation: one affected test class, without packaging.
bash scripts/quality-gradle.sh --backend :test --tests 'com.azhukov.agent.gateway.GatewayLifecycleServiceTest' --no-daemon --max-workers=2
```

Integration/slow/full coverage run manually before release. Ordinary build/check
uses the reviewed unit inventory in `gradle/test-lanes.json`; it does not select
the manual lanes. Backend integration requires the disposable PostgreSQL fixture declared in
`.quality-gate.json`. Use an owned Linux runner for POSIX-dependent tests.
The helper freezes SOURCE_DATE_EPOCH from the candidate; preserve the tested JAR
and its digest for delivery. Coverage tasks use the same Gradle graph and do not
need a second test invocation. Keep maxParallelForks and product coverage gates.
Ordinary helper tasks use the qualified strict configuration-cache default;
scope and notice-input requirements are in [TESTING](docs/TESTING.md).
`gradle/test-lanes.json` also owns every support source. Unit compilation uses
unit and pure shared support; manual lanes have their own source set/classpath.
Do not add a dependency on manual test classes to unit/shared code, including
reflection: extract a pure shared fixture instead. Testcontainers dependencies
belong to manual compilation. `check_test_lanes.py` verifies the full partition;
Gradle rejects unknown/stale sources before compilation. Preserve shared test
resources and full release coverage. This sourceSet change needs application
qualification; the bounded Gradle metadata fixture alone does not qualify it.
New Base context readers verify the actual selected JDK/configuration using
`scripts/gradle-context.py`. Portable grouped receipt reuse remains disabled.
Selected Windows Backend/Bot/Shared declare native consent through the separate
Base `javaNativeExecution` profile (`-JavaNativeExecution`, desktop-linux and
explicit backend/bot/shared components). Backend uses the standalone 9.8.0
graph with Shared; the root 9.6.1 graph contains only Bot/Shared or Shared.
Each Build declares one exact JAR. Source consent alone is not native proof;
the current qualification belongs to the canonical Base performance plan.
The selected Windows CLI candidate declares native consent;
accept it only after current distribution/resolved-input, Unit/JAR and unchanged
Root reuse proofs in TESTING. Source consent alone is not native qualification.
`qualityDependencyInputs` is explicit preparation only; ordinary test/bootJar
must not run it automatically. It indexes external production/unit inputs;
it is not an artifact receipt. Commands and scope are in TESTING.
Prepared input inspection is opt-in through `--prepared-module` and `--role`
on the context reader. It validates the selected cached distribution and role
files without Gradle/bootstrap. It does not certify resolution provenance or
enable receipt reuse; ordinary gate declarations remain unchanged.
`gradle/java-preparation.json` owns native dependency-preparation inputs and the
Shared project closure. Base seals these in the dependency image layer; the QA
runner checks readiness without another resolver. This source path still needs
native qualification and exact adoption; it does not enable JAR receipts.
`java-artifacts.json` owns source QA JAR filenames, entrypoints and notices;
keep it outside dependency-preparation inputs. Base QA accepts a selected JAR
subset so backend packaging with CLI units does not package CLI. It retains
selected JAR hashes/source/image in the private handoff after unit/cleanup/source
checks. Actual native JAR qualification and generic Windows/root adoption remain
separate; metadata/ZIP fixture checks do not enable receipt reuse.
New Base quality tooling uses the explicit `native_execution` declaration for
Windows ordinary gates. It keeps per-gate results, validates selected JDK/tool
contexts before/after tasks in Linux and owns one shared host slot through
handoff publication. It requires clean source and the candidate epoch until
broader context qualification. Integration/slow/release keep their Linux fixture
gates; supported exact packet adoption/native measurements remain separate.

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
| Java | Temurin 25.0.4.1+1 LTS |
| Spring Boot | 4.1.0 |
| Gradle | workspace 9.6.1; standalone backend 9.8.0 (Groovy DSL) |
| Lombok | 1.18.38 |
| MapStruct | 1.6.3 |
| LangChain4j | 1.21.0 |
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

- Точное число тестов берётся из XML текущего прогона; исторические totals не подтверждают новый commit.
- Blocking LINE floors: backend 79.38%, bot 82.47%, source `docs/standards/coverage-baseline.json`. Tests precede the coverage ratchet; missing configured reports fail. The separate 80% backend summary is informational.
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

- `python3 scripts/check_docs_standard.py` — быстрый DOC001/DOC002/DOC003 ratchet против documentation counters в `docs/standards/coverage-baseline.json`, без тестов и компиляции. LINE coverage проверяется отдельно ручным Gradle release graph; отсутствие обязательного coverage report не объявляется PASS.
- Baseline повышается только осознанно: вместе с новыми тестами, `--update-baseline`, коммит "chore: raise docs baseline".
- Текущий долг: backend 513×DOC001 / bot 58 / cli 6; LINE backend 79.15% / bot 82.16%. Гасить пакетами из bottom-списка, не разом.

## Быстрые проверки и сборка

Перед тестами, build и CI читать обязательный [стандарт Base](https://github.com/FerrPOINT/services-base/blob/197ead9d9b6cf6901e8f9dd31f43f719885502c3/docs/BUILD_PERFORMANCE.md).
При разработке и сборке unit/static. Integration/slow/full coverage —
ручной gate при подготовке релиза по docs/TESTING.md.
Сохранять полезный cache, не переустанавливать неизменные dependencies и не
пересобирать приложение из-за test-only. До повторного тяжёлого запуска назвать
изменившиеся inputs/недостающее evidence; передавать один artifact между этапами.
Required security/data/compatibility/live checks и product toolchains сохраняются.
Компоненты и команды: `.quality-gate.json`; общие правила не копировать.

Уровни и команды: [TESTING](docs/TESTING.md). Полный `.github/workflows/release.yml`
запускается вручную на exact ref; зелёный unit build не означает release acceptance.

## Выбор сборки и повторное использование

Перед build/test/install определить нужные компоненты и изменившиеся реальные
inputs; проверить готовый artifact, environment и cache. Неизменные inputs и
проверенный artifact означают отсутствие повторных install/compile/source export.
Docs/test-only не пересобирают приложение без изменения его build inputs.
Во время работы выполнять scoped unit/static; полный gate — на согласованной
вехе. Integration/slow/full coverage — вручную перед релизом; необходимые
security/data/compatibility/live gates сохраняются. Полные команды в этом
документе — каталог проверок, а не требование выполнять всё после каждой правки.
Перед повторным heavy назвать changed inputs или missing proof; REUSED не
выдавать за новый тест. Полезный прогретый cache сохранять в его бюджете.
В source workspace читать `docs/BUILD_PERFORMANCE.md` из `qualityTooling.path`
в `../source-workspace.json` и [правила workspace](../AGENTS.md).
В standalone checkout использовать стандарт принятой версии Base.
