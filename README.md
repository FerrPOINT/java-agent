# Java Agent

Spring Boot 4.1 + Java 25 + Gradle 9.6.1 (Groovy DSL) + Groovy 5 — Java-порт ядра NousResearch Hermes Agent.

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
| Testcontainers | 2.0.5 |

## Конкурентность: виртуальные потоки

Проект использует **Spring MVC + виртуальные потоки**, а не WebFlux/Reactor:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Обоснование — в `docs/03-dependency-map.md` и `docs/05-migration-notes.md`.

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
export OLLAMA_API_KEY=<ключ>
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
docker compose up --build
```

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

Текущий coverage gate: LINE ≥ 80%, per-package целевые пакеты ≥ 75%. Отчёт JaCoCo: `backend/build/reports/jacoco/test/html/index.html`.

## Структура

```
backend/
├── build.gradle
├── settings.gradle
├── ARCHITECTURE.md      # текущий стек и профили
└── src/main/java/com/azhukov/agent/
    ├── JavaAgentApplication.java
    ├── api/                # REST controllers + health indicators
    ├── cli/                # Picocli / JLine REPL
    ├── client/             # LLM clients (LangChain4j, NoOp) + MCP client
    ├── config/             # AgentProperties, beans
    ├── core/               # domain layer (AgentRuntime, ToolRegistry, prompts, context, memory, skills, state, audit)
    ├── gateway/            # Telegram/webhook adapters + routing
    ├── persistence/        # JPA entities + repositories + Flyway migrations
    ├── security/           # SSRF-safe HTTP client, safety validators
    └── tools/              # @AgentTool implementations (file, terminal, process, web, browser, coding, memory, skills, mcp)
```

- `backend/src/main/resources/` — конфигурация и миграции Flyway
- `docs/` — архитектура и планирование
- `prototype/` — удалён; для исходников Hermes используйте официальные репозитории NousResearch

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
