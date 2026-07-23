# Hermes Java Agent

Spring Boot 3.4 + Kotlin + Gradle-приложение, Java-порт ядра Hermes Agent.

## Стек

- Java 21
- Kotlin 2.1
- Spring Boot 3.4
- Spring Web / WebFlux / WebSocket / Validation / Data JPA / Actuator
- LangChain4j (OpenAI, Ollama)
- SQLite + Flyway
- Pebble (шаблоны)
- MCP Java SDK
- Resilience4j
- Picocli / JLine (CLI)

## Структура

```text
src/main/kotlin/com/ferrpoint/hermes/
├── HermesJavaAgentApplication.kt
├── api/                 # REST controllers
├── config/              # @ConfigurationProperties
├── agent/               # AgentRuntime, Session
├── model/               # Message, ToolCall, ToolResult
├── client/              # ModelClient и адаптеры
├── tool/                # ToolRegistry, ToolExecutor
├── tools/builtin/       # read_file, terminal, web_search, vision_analyze
├── tools/browser/       # CDP браузер
├── memory/              # MemoryManager
├── skill/               # SkillManager
├── prompt/              # PromptBuilder
├── context/             # ContextEngine
└── security/            # PathSecurity, Redactor
```

## Запуск

```bash
./gradlew bootRun
```

## Проверка

```bash
curl http://localhost:8080/api/v1/health
```

## Документация

См. папку `docs/`.

## Upstream

https://github.com/NousResearch/hermes-agent
