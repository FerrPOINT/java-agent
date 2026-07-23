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

### Проверка

```bash
curl -s http://localhost:8090/actuator/health
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"message":"echo OK"}' \
  http://localhost:8090/api/v1/agent/chat
```

## Сборка и тесты

```bash
cd backend
./gradlew test          # unit + integration (web tests skipped без ENABLE_NETWORK_TESTS)
./gradlew bootJar       # собрать jar
```

## Структура

```
backend/
├── build.gradle
├── settings.gradle
└── src/main/java/com/azhukov/agent/
    ├── JavaAgentApplication.java
    ├── api/                # REST controllers
    ├── cli/                # Picocli / JLine REPL
    ├── client/             # LLM clients (LangChain4j, NoOp)
    ├── config/             # AgentProperties, beans
    ├── core/               # domain layer (AgentRuntime, ToolRegistry, prompts, context, memory, skills)
    ├── persistence/        # JPA entities + repositories
    └── tools/              # @AgentTool implementations (file, terminal, web, browser, coding, memory, skills, mcp)
```

- `backend/src/main/resources/` — конфигурация и миграции Flyway
- `docs/` — архитектура и планирование
- `prototype/` — клоны репозиториев Hermes (не в git)

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
