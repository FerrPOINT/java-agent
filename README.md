# Java Agent

Spring Boot 4.1 + Java 25 + Gradle 9.6.1 (Groovy DSL) + Groovy 5 — Java-порт ядра NousResearch agent.

## Стек

| Компонент | Версия |
|-----------|--------|
| Java | 25 LTS |
| Gradle | 9.6.1 |
| Groovy | 5.0.7 |
| Spring Boot | 4.1.0 |
| Spring Framework | 7.0.8 |
| PostgreSQL | 16 |
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
| Testcontainers | 1.21.4 |

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

Настраивается через `AGENT_NAME` (default — `Джава агент`):

```yaml
agent:
  name: ${AGENT_NAME:Джава агент}
```

## LLM-провайдер

Агент работает с любым **OpenAI-compatible endpoint** через `langchain4j-open-ai`.
По умолчанию endpoint указывает на локальный Ollama (`http://localhost:11434/v1`), но это просто удобный default для разработки.

```yaml
agent:
  model:
    provider: openai-compatible
    base-url: ${AGENT_MODEL_BASE_URL:http://localhost:11434/v1}
    api-key: ${AGENT_MODEL_API_KEY:}
    model-name: ${AGENT_MODEL_NAME:qwen2.5:3b}
```

Пример подключения внешнего OpenAI-compatible провайдера:

```bash
export AGENT_MODEL_BASE_URL=https://api.moonshot.ai/v1
export AGENT_MODEL_API_KEY=sk-...
export AGENT_MODEL_NAME=kimi-k2.7-code
```

## Быстрый старт

```bash
cd backend
export DB_PASSWORD=*** bootRun
```

По умолчанию приложение стартует на http://localhost:8080.

Для локальной разработки используется существующий Postgres-контейнер `project-workflow-db-1` на `localhost:5432`, база `java_agent`.

## Сборка

```bash
cd backend
./gradlew build
```

## Тесты

```bash
cd backend
./gradlew test
```

## Структура

- `backend/` — Spring Boot приложение (Gradle + Java + Groovy)
- `backend/src/main/java/` — Java-код ядра
- `backend/src/main/groovy/` — Groovy-скрипты / DSL
- `backend/src/main/resources/` — конфигурация и миграции Flyway
- `docs/` — архитектура и планирование
- `prototype/` — клоны репозиториев agent (не в git)

## Переменные окружения

| Переменная | Назначение |
|------------|------------|
| `AGENT_NAME` | Имя агента |
| `AGENT_MODEL_PROVIDER` | Провайдер модели (default `openai-compatible`) |
| `AGENT_MODEL_BASE_URL` | URL OpenAI-compatible endpoint |
| `AGENT_MODEL_API_KEY` | API-ключ |
| `AGENT_MODEL_NAME` | Название модели |
| `AGENT_MODEL_TIMEOUT_SECONDS` | Таймаут |
| `AGENT_MODEL_MAX_RETRIES` | Повторы |
| `AGENT_VISION_*` | Аналогичные настройки для vision |
| `DB_HOST` | Хост PostgreSQL (default `localhost`) |
| `DB_PORT` | Порт PostgreSQL (default `5432`) |
| `DB_NAME` | Имя базы (default `java_agent`) |
| `DB_USER` | Пользователь PostgreSQL (default `project_workflow`) |
| `DB_PASSWORD` | Пароль PostgreSQL |
| `AGENT_BROWSER_CDP_URL` | URL Chrome DevTools Protocol |
| `AGENT_BROWSER_DEFAULT_TIMEOUT_MS` | Таймаут браузера |
| `SERVER_PORT` | Порт приложения |

## Документация

- `docs/README.md` — обзор
- `docs/01-scope.md` — границы проекта
- `docs/02-core-architecture.md` — архитектура
- `docs/03-dependency-map.md` — маппинг Python → Java
- `docs/04-proposed-java-structure.md` — модульная структура
- `docs/05-migration-notes.md` — нетривиальные моменты
- `docs/06-vision-browser.md` — vision и browser
