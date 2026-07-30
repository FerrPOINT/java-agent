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

## Структура проекта

```
java-agent/
├── backend/                    # Backend: REST API, LLM client, tools, persistence
│   └── src/main/java/com/azhukov/agent/
│       ├── api/                # REST controllers + DTO + mappers
│       ├── cli/                # Picocli / JLine REPL
│       ├── client/             # LLM clients (LangChain4j, NoOp) + MCP client
│       ├── config/             # AgentProperties, MapStructConfig, beans
│       ├── core/               # domain layer (AgentRuntime, tools, context, memory, skills, state, security)
│       ├── gateway/            # Telegram/webhook adapters + routing
│       ├── persistence/        # JPA entities + repositories + mappers + Flyway
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
│       ├── formatting/         # Markdown converter, response filter
│       ├── group/              # Group message filter
│       ├── keyboard/           # Inline keyboards (model/provider selection)
│       ├── lifecycle/          # Bot lifecycle
│       ├── media/              # Media cache, inbound media, location handler
│       ├── polling/            # Long polling, reconnect watcher, fallback IP resolver
│       ├── session/            # BotSessionEntity, BotSessionStore
│       ├── streaming/          # StreamEditor (edit-message streaming)
│       ├── typing/             # TypingManager
│       └── webhook/            # Webhook secret validator
├── docs/                       # Architecture docs
└── docker-compose.yml          # Production deployment
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
- Dockerfile: `eclipse-temurin:25-jre-noble` + Chromium runtime deps
- `server.shutdown: immediate` — workaround для graceful shutdown бага Spring Boot 4.1.0
- Health readiness включает только `db`, чтобы LLM/CDP-сбои не помечали под неготовой

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