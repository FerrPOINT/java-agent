# Java Agent — Prototype Documentation

## Модули и документация

| Модуль | Ответственность |
|---|---|
| `backend` | Agent runtime, HTTP/OpenAI API, модели, MCP, инструменты и PostgreSQL |
| `telegram-bot` | Входящие обновления, пользовательские команды и доставка через Telegram |
| `cli` | Standalone REPL и HTTP-клиент backend |
| `shared` | Общие контракты и чтение HTTP-потока |

Актуальные границы описаны в [карте архитектуры](architecture/README.md).
Настройки перечислены в [справочнике](default-settings.md), а поддерживаемые
команды и варианты установки — в [README репозитория](../README.md).
Нумерованные документы о переносе описывают этапы, на которых были написаны.

## 5. Agent Name

The agent name is configurable via `agent.name` (defaults to `Джава агент`).

## 6. Build & Run

```bash
cd backend
./gradlew test
./gradlew bootJar

# Dev with real LLM
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev

# NoOp / offline
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=noop

# CLI REPL (connects to the backend)
java --enable-native-access=ALL-UNNAMED \
  -jar ../cli/build/libs/cli-0.0.1-SNAPSHOT.jar \
  --backend.url=http://localhost:8090
```

Health endpoints:

- `GET http://localhost:8090/actuator/health`
- `GET http://localhost:8090/api/v1/health`

Chat endpoint:

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"message":"echo OK"}' \
  http://localhost:8090/api/v1/agent/chat
```

Telegram gateway:

- Webhook: `POST /api/v1/telegram/webhook`
- Long-polling: enabled via `AGENT_GATEWAY_TELEGRAM_LONG_POLLING_ENABLED=true`

## 7. Next Steps

1. Read `01-scope.md` to confirm boundaries.
2. Read `02-core-architecture.md` for the runtime data flow.
3. Read `03-dependency-map.md` for the Java stack choices.
4. Read `04-proposed-java-structure.md` for the package skeleton.
5. Read `05-migration-notes.md` for tricky parts.
6. Read `06-vision-browser.md` for vision and browser porting details.
7. Read `07-mcp-client-status.md` for MCP configuration.
8. Read `08-browser-vision.md` for HTTP examples.
9. Read `09-builtin-tools.md` for the implemented tool matrix and security defaults.
10. Read `10-production-readiness.md` for Docker/systemd/health/telegram.
11. Read `UI_SHELL_STANDARD.md` before designing a first-party web console.
12. Read `default-settings.md` before changing configuration defaults or selecting environment overrides.
13. Read `web-search-resilience.md` for provider fallback policy, keyless limits, and operational controls.

## Source

- This project: <https://github.com/FerrPOINT/java-agent>
