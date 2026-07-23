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
| PostgreSQL | 16 |
| LangChain4j | 1.18.0 |
| MCP Java SDK | 2.0.0 |
| Flyway | 12.4.0 (Spring Boot BOM) |
| PostgreSQL JDBC | Spring Boot BOM |
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

## LLM-провайдер по умолчанию

Локальный **Ollama** (`http://localhost:11434`), модель по умолчанию `qwen2.5:3b`.

```yaml
agent:
  model:
    provider: ollama
    base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
    model-name: ${OLLAMA_MODEL:qwen2.5:3b}
```

OpenAI-compatible endpoints поддерживаются через `langchain4j-open-ai`.

## Быстрый старт

```bash
export DB_PASSWORD=***
./gradlew bootRun
```

По умолчанию приложение стартует на http://localhost:8080.

Для локальной разработки используется существующий Postgres-контейнер `project-workflow-db-1` на `localhost:5432`, база `java_agent`.

## Сборка

```bash
./gradlew build
```

## Тесты

```bash
./gradlew test
```

## Структура

- `src/main/java/` — Java-код ядра
- `src/main/groovy/` — Groovy-скрипты / DSL
- `src/main/resources/` — конфигурация и миграции Flyway
- `docs/` — архитектура и планирование
- `prototype/` — клоны репозиториев Hermes (не в git)

## Переменные окружения

| Переменная | Назначение |
|------------|------------|
| `AGENT_NAME` | Имя агента |
| `OLLAMA_BASE_URL` | URL Ollama |
| `OLLAMA_MODEL` | Модель Ollama |
| `OLLAMA_API_KEY` | API-ключ Ollama (если нужен) |
| `DB_HOST` | Хост PostgreSQL (default `localhost`) |
| `DB_PORT` | Порт PostgreSQL (default `5432`) |
| `DB_NAME` | Имя базы (default `java_agent`) |
| `DB_USER` | Пользователь PostgreSQL (default `project_workflow`) |
| `DB_PASSWORD` | Пароль PostgreSQL |
| `AGENT_VISION_BASE_URL` | URL vision-провайдера |
| `AGENT_VISION_API_KEY` | Ключ vision-провайдера |
| `AGENT_VISION_MODEL_NAME` | Модель vision-провайдера |
| `AGENT_BROWSER_CDP_URL` | URL Chrome DevTools Protocol |

## Документация

- `docs/README.md` — обзор
- `docs/01-scope.md` — границы проекта
- `docs/02-core-architecture.md` — архитектура
- `docs/03-dependency-map.md` — маппинг Python → Java
- `docs/04-proposed-java-structure.md` — модульная структура
- `docs/05-migration-notes.md` — нетривиальные моменты
- `docs/06-vision-browser.md` — vision и browser
