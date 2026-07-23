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
| LangChain4j | 1.18.0 |
| MCP Java SDK | 2.0.0 |
| Flyway | 12.4.0 (Spring Boot BOM) |
| SQLite JDBC | 3.53.2.0 (Spring Boot BOM) |
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

## Быстрый старт

```bash
./gradlew bootRun
```

По умолчанию приложение стартует на http://localhost:8080.

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

- `AGENT_NAME` — имя агента
- `AGENT_MODEL_API_KEY` — ключ к LLM
- `AGENT_VISION_API_KEY` — ключ к vision-модели
- `AGENT_BROWSER_CDP_URL` — URL Chrome DevTools Protocol

## Документация

- `docs/README.md` — обзор
- `docs/01-scope.md` — границы проекта
- `docs/02-core-architecture.md` — архитектура
- `docs/03-dependency-map.md` — маппинг Python → Java
- `docs/04-proposed-java-structure.md` — модульная структура
- `docs/05-migration-notes.md` — нетривиальные моменты
- `docs/06-vision-browser.md` — vision и browser
