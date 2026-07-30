# Java Agent

Spring Boot 4.1 + Java 25 + Gradle 9.6.1 (Groovy DSL) + Groovy 5 — Java-агент с поддержкой LLM, инструментов, Telegram-шлюза и MCP.

## Стек

| Компонент | Версия |
|-----------|--------|
| Java | 25 LTS |
| Gradle | 9.6.1 |
| Groovy | 5.0.7 |
| Spring Boot | 4.1.0 |
| Spring Framework | 7.0.8 |
| PostgreSQL | 16 (dev) |
| LangChain4j | 1.18.0 |
| MCP Java SDK | 2.0.0 |
| Flyway | 12.4.0 (Spring Boot BOM) |
| PostgreSQL JDBC | 42.7.11 (Spring Boot BOM) |
| Hibernate ORM | 7.4.1.Final (Spring Boot BOM) |
| Jackson | 3.1.4 (Spring Boot BOM) |
| Resilience4j | 2.4.0 |
| Picocli | 4.7.7 |
| JLine | 4.3.1 |
| Pebble | 4.1.2 |
| Lombok | 1.18.38 |
| MapStruct | 1.6.3 |
| Testcontainers | 2.0.5 |

## Архитектурные правила

### Lombok

| Правило | Аннотация | Применение |
|---------|-----------|------------|
| Spring beans (сервисы, контроллеры, компоненты) | `@RequiredArgsConstructor` + `@Slf4j` | Все классы с final-зависимостями и чистыми конструкторами |
| JPA entities | `@Entity` + `@Data` | `MessageEntity`, `SessionEntity`, `BotSessionEntity` и др. |
| Records для DTO и core models | `record` (без Lombok) | `ChatRequest`, `Message`, `ToolCall`, `Session`, `ChatResponse` |
| Логирование | `@Slf4j` | Заменяет ручной `LoggerFactory.getLogger(...)` |

**Когда НЕ использовать Lombok:**
- Конструктор с логикой (HttpClient.new, Executors.new, RestClient.builder)
- Null-checks в конструкторе (`x == null ? "" : x`)
- `@Qualifier` на параметр конструктора (Lombok не поддерживает)
- Множественные конструкторы
- Классы без DI-зависимостей

**@PostConstruct для derived fields:**
Когда поле вычисляется из injected-зависимости (например `configuredLimit = properties.getWeb().getSearchResults()`), поле делается non-final, а вычисление переносится в `@PostConstruct void init()`. В unit-тестах `init()` вызывается вручную после `new`.

**Inline init для runtime state:**
Поля, не зависящие от injected-зависимостей (executors, caches, maps), инициализируются inline: `private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(...)`.

### MapStruct

| Маппер | Пакет | Направление |
|--------|-------|-------------|
| `MessageMapper` | `persistence.mapper` | `MessageEntity` ↔ `Message` |
| `SessionEntityMapper` | `persistence.mapper` | `SessionEntity` ↔ `Session` |
| `DomainDtoMapper` | `api.mapper` | `Session` → `SessionSummaryDto` |
| `OpenAiMapper` | `api.mapper` | `Message` ↔ `OpenAiMessage`, `ChatResponse` ↔ `OpenAiChatResponse`, `ToolCall` → `OpenAiToolCall` |

**Конфигурация:** `MapStructConfig` — `componentModel = "spring"`, `unmappedTargetPolicy = ERROR`.

**Правила:**
- Мапперы — Spring beans (`@Mapper(config = MapStructConfig.class)`)
- В unit-тестах — `Mappers.getMapper(X.class)` (не mock)
- `@Named` helper methods для non-trivial conversion (enum → string, nested objects)
- `default` methods для conditional logic
- `roleToString` → `role.name().toLowerCase()`, `stringToRole` → `Role.valueOf(role.toUpperCase())`

**Когда НЕ использовать MapStruct:**
- `buildResponse` в сервисах, где DTO собирается из множества источников (Session + TurnResult + UsageTracker + Properties)
- Streaming chunk creation (DTO specifique to SSE format)
- Bot layer: entities используются как domain models, mapping не нужен

### Структура проекта

```
java-agent/
├── backend/                    # Backend: REST API, LLM client, tools, persistence
│   └── src/main/java/com/azhukov/agent/
│       ├── api/                # REST controllers + DTO + mappers (OpenAiMapper, DomainDtoMapper)
│       ├── cli/                # Picocli / JLine REPL
│       ├── client/             # LLM clients (LangChain4j, NoOp) + MCP client
│       ├── config/             # AgentProperties, MapStructConfig, beans
│       ├── core/               # domain layer (AgentRuntime, tools, context, memory, skills, state, security)
│       ├── gateway/            # Telegram/webhook adapters + routing
│       ├── persistence/        # JPA entities + repositories + mappers (MessageMapper, SessionEntityMapper) + Flyway
│       ├── security/           # SSRF-safe HTTP client, safety validators
│       ├── service/            # AgentRuntimeService, AgentStreamingService, TTS, transcription
│       └── tools/              # @AgentTool implementations (file, terminal, web, browser, memory, delegate, etc.)
├── telegram-bot/               # Telegram bot: 56 commands, streaming, polling, media
│   └── src/main/java/com/azhukov/agent/bot/
│       ├── api/                # Bot API DTOs
│       ├── auth/               # Authorization, pairing
│       ├── batch/              # Text/photo batch debouncers
│       ├── client/             # TelegramClient, RestClient config
│       ├── commands/           # 56 command handlers + CommandRegistry (10 aliases)
│       ├── config/             # BotProperties, BotConfig
│       ├── core/               # BotMessageProcessor, AgentBackendClient
│       ├── footer/             # Runtime footer
│       ├── formatting/         # Markdown converter, response filter
│       ├── group/              # Group message filter
│       ├── keyboard/           # Inline keyboards (model/provider selection)
│       ├── lifecycle/          # Bot lifecycle
│       ├── media/              # Media cache, inbound media, location handler
│       ├── polling/            # Long polling, reconnect watcher, fallback IP resolver
│       ├── reaction/           # Reaction manager
│       ├── session/            # BotSessionEntity, BotSessionStore
│       ├── sticker/            # Sticker cache
│       ├── streaming/          # StreamEditor (edit-message streaming)
│       ├── typing/             # TypingManager
│       └── webhook/            # Webhook secret validator
├── docs/                       # Architecture docs
└── docker-compose.yml          # Production deployment
```

## Coverage

| Метрика | Значение |
|---------|----------|
| LINE | 80.4% |
| BRANCH | 66.0% |
| METHOD | 84.5% |
| CLASS | 92.7% |
| Тестов | 1500 (279 test files), 0 failures |
| @RequiredArgsConstructor | 159 файлов |
| @Slf4j | 83 файла |
| @Data (JPA entities) | 16 файлов |
| MapStruct мапперов | 4 (+ 5 тестов) |
| Bot команд | 56 (+ 10 алиасов) |
| Backend endpoints | 50 |
| MCP файлов | 21 |

## Конкурентность: виртуальные потоки

## Имя агента

Настраивается через `agent.name` (default — `Джава агент`):

```yaml
agent:
  name: ${AGENT_NAME:Джава агент}
```

## Профили

| Профиль | Назначение |
|---------|------------|
| `dev` | Ollama Cloud / локальный endpoint, порт 8090, PostgreSQL localhost:5432 |
| `noop` | LLM-заглушка + H2 in-memory; для тестов и offline-разработки |
| `cli` | Активирует Picocli REPL |
| `prod` | Production endpoint (OpenAI / совместимый), INFO-логи |

## Быстрый старт

### Dev (real LLM через Ollama Cloud)

```bash
cd backend
export OLLAMA_API_KEY=***
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  --spring.datasource.password=project_workflow \
  --server.port=8090
```

> ⚠️ Gradle `bootRun` падает с OOM/SIGKILL при реальных LLM-вызовах. Используйте `java -jar`.

### NoOp (без LLM, без PostgreSQL)

```bash
cd backend
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=noop \
  --server.port=8090
```

### Streaming

```bash
curl -N -X POST http://localhost:8090/api/v1/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"Привет"}'
```

### OpenAI-compatible

```bash
curl -s -X POST http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"kimi-k2.6","messages":[{"role":"user","content":"hi"}]}' | jq .
```

## Production

```bash
docker compose up --build      # production, порт 8080
docker compose -f docker-compose.local.yml up --build  # local dev, порты 18090/18091
```

- `docker-compose.yml` — production (порт 8080, PostgreSQL 5432)
- `docker-compose.local.yml` — local dev (порт 18090, PostgreSQL 18091) — не конфликтует с другими сервисами

- Dockerfile: `eclipse-temurin:25-jre-noble` + Chromium runtime deps.
- `server.shutdown: immediate` — workaround для graceful shutdown бага Spring Boot 4.1.0.
- Health readiness включает только `db`, чтобы LLM/CDP-сбои не помечали под неготовой.

Подробности — `backend/docs/13-production-hardening.md`.

## Сборка и тесты

```bash
cd backend
./gradlew test          # unit + integration, исключает live/slow
./gradlew jacocoTestReport
./gradlew bootJar       # собрать jar
```

### Slow / E2E tests

```bash
cd backend
./gradlew slowTest      # @Tag("slow") integration tests (no external services, uses noop LLM)
cd ..
./scripts/e2e-docker-compose-test.sh  # Docker Compose E2E (noop provider + PostgreSQL)
```

Текущий coverage gate: LINE ≥ 80%, per-package целевые пакеты ≥ 75%. Отчёт JaCoCo: `backend/build/reports/jacoco/test/html/index.html`.

## Переменные окружения

| Переменная | Назначение |
|------------|------------|
| `AGENT_NAME` | Имя агента |
| `AGENT_MODEL_PROVIDER` | Провайдер модели (`openai-compatible`, `noop`) |
| `AGENT_MODEL_BASE_URL` | URL OpenAI-compatible endpoint |
| `AGENT_MODEL_API_KEY` | API-ключ |
| `AGENT_MODEL_NAME` | Название модели |
| `AGENT_MODEL_TIMEOUT_SECONDS` | Таймаут HTTP-модели (default 600) |
| `AGENT_MODEL_MAX_RETRIES` | Повторы |
| `AGENT_AUXILIARY_*` | Настройки auxiliary-модели |
| `AGENT_VISION_*` | Настройки vision |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL |
| `AGENT_BROWSER_CDP_URL` | URL Chrome DevTools Protocol |
| `AGENT_TERMINAL_*` | Таймауты терминала |
| `AGENT_SERVER_PORT` | Порт приложения (default 8090) |

## Документация

- `docs/README.md` — обзор
- `docs/01-scope.md` — границы проекта
- `docs/02-core-architecture.md` — архитектура
- `docs/03-dependency-map.md` — маппинг Python → Java
- `docs/04-proposed-java-structure.md` — модульная структура
- `docs/05-migration-notes.md` — нетривиальные моменты
- `docs/06-vision-browser.md` — vision и browser
- `backend/docs/09-builtin-tools.md` — встроенные инструменты
- `backend/docs/10-production-readiness.md` — production readiness
- `backend/docs/11-chromium.md` — Chromium auto-install
- `backend/docs/12-streaming.md` — SSE streaming
- `backend/docs/13-production-hardening.md` — context compression и production packaging
- `backend/docs/conventions.md` — конвенции Lombok / Records / MapStruct