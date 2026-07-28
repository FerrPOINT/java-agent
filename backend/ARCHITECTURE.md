# java-agent backend architecture

## Stack
- Java 21 (target Java 25 LTS; JDK 21 current)
- Gradle 9.6.1 + Groovy DSL
- Spring Boot 4.1.0 + Spring Framework 7.0.8
- Spring MVC + virtual threads (`spring.threads.virtual.enabled=true`)
- LangChain4j 1.18.0 (OpenAI-compatible endpoint)
- MCP Java SDK 2.0.0
- SQLite + Flyway 12.4.0 (managed by Spring Boot BOM) for runtime; H2 for tests/noop
- Picocli 4.7.7 + JLine 4.3.1 (CLI REPL)
- Pebble 4.1.2 templates, Resilience4j 2.4.0, jsoup

## Structure
```
backend/src/main/java/com/azhukov/agent/
  api/            REST API + health indicators
  cli/            Picocli/JLine REPL
  client/         ModelClient + LangChain4j/MCP clients
  config/         AgentProperties + Spring beans
  core/           AgentRuntime, tool execution, memory, skills, context, prompt, state, security
  gateway/        Telegram/webhook adapters + routing
  persistence/    JPA entities, repositories, Flyway migrations
  security/       SSRF-safe HTTP client, safety validators, guardrails
  tools/          Built-in tool handlers: file, shell, process, web, browser (CDP), code, gateway, skills
```

## Profiles
- `dev` (default on port 8090)
- `test` (H2 in-memory, Flyway disabled)
- `noop` (dumb echo ModelClient, H2, no memory/skills, CLI smoke)
- `cli` (`--enable-native-access=ALL-UNNAMED` for JLine)

## Coverage gate (target)
- LINE ≥ 80%, per-package target packages ≥ 75% (excluding `tools/code` which requires external Python execution)
- Main test run excludes `@Tag("live")` and `@Tag("slow")` integration tests
