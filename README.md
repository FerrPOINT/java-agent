# Hermes Java Agent

Spring Boot 4.1 + Java 25 + Gradle 9.6.1 (Groovy DSL) + Groovy 5 — Java-порт ядра Hermes Agent.

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
| SQLite JDBC | 3.53.2.1 (Spring Boot BOM) |
| Hibernate ORM | 7.4.1.Final (Spring Boot BOM) |
| Jackson | 3.1.4 (Spring Boot BOM) |
| Reactor | 2025.0.6 (Spring Boot BOM) |
| Resilience4j | 2.4.0 |
| Picocli | 4.7.7 |
| JLine | 4.3.1 |
| Pebble | 4.1.2 |
| Testcontainers | 2.0.5 (Spring Boot BOM) |

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

## CLI

```bash
./gradlew bootRun --args='cli'
```

## Структура

- `src/main/java/` — Java-код ядра
- `src/main/groovy/` — Groovy-скрипты / DSL / тесты
- `src/main/resources/` — конфигурация и миграции Flyway
- `docs/` — архитектура и планирование
- `prototype/` — клоны репозиториев Hermes (не в git)

## Переменные окружения

- `HERMES_MODEL_API_KEY` — ключ к LLM
- `HERMES_VISION_API_KEY` — ключ к vision-модели
- `HERMES_BROWSER_CDP_URL` — URL Chrome DevTools Protocol

## Скриптовый Groovy

```bash
./gradlew -q console
```
