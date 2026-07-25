# Production readiness

## Docker

```bash
docker compose up --build
```

- PostgreSQL 16 на порту 5432.
- Backend на порту 8080 (prod) / 8090 (dev).
- Chromium автоустанавливается при первом старте, если `agent.chromium.auto-install: true`.
- Рабочая директория монтируется из `${AGENT_WORK_DIR:-./work}`.

## Systemd

```bash
sudo cp java-agent.service /etc/systemd/system/
sudo mkdir -p /etc/java-agent
sudo cp .env /etc/java-agent/env
sudo systemctl daemon-reexec
sudo systemctl enable --now java-agent
```

## Health

Actuator endpoints (Spring Boot 4.1 health API):
- `/actuator/health` — aggregate
- `/actuator/health/browser` — CDP connection
- `/actuator/health/model` — LLM ping
- `/actuator/health/db` — datasource
- `/actuator/health/readiness` — `db`
- `/actuator/health/liveness` — `livenessState`

## Логи

- В dev-профиле — цветной plain-text.
- В prod-профиле — JSON через Logstash encoder.

## Rate limiting

- `RateLimitFilter` (Bucket4j) защищает HTTP-эндпоинты.
- Лимиты конфигурируются в `application.yml` через `agent.rate-limit.*`.

## Audit log

- Каждый входящий запрос к `/api/v1/agent/chat` и OpenAI-compatible gateway записывается в `audit_log`.
- Хранится: request body, user/session, timestamp, статус.

## Конфигурация окружения

| Переменная | Значение по умолчанию | Описание |
|------------|----------------------|----------|
| `SPRING_PROFILES_ACTIVE` | — | `dev`, `prod`, `cli`, `noop` |
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | java_agent | Database |
| `DB_USER` | project_workflow | User |
| `DB_PASSWORD` | — | Password |
| `OLLAMA_API_KEY` | — | API key для Ollama Cloud |
| `TELEGRAM_BOT_TOKEN` | — | Bot token для Telegram gateway |
| `AGENT_GATEWAY_TELEGRAM_ALLOWED_USER_IDS` | — | Разрешённые Telegram user IDs |
| `AGENT_GATEWAY_TELEGRAM_ALLOWED_USERNAMES` | — | Разрешённые Telegram usernames |
| `AGENT_GATEWAY_TELEGRAM_ALLOW_BY_DEFAULT` | false | Разрешить всем |
| `AGENT_SERVER_PORT` | 8090 | HTTP port |
| `AGENT_WORK_DIR` | ./work | Workspace для файловых операций |

## Telegram gateway

- Webhook: `AGENT_GATEWAY_TELEGRAM_WEBHOOK_ENABLED=true` + `AGENT_GATEWAY_TELEGRAM_WEBHOOK_URL`.
- Long-polling: `AGENT_GATEWAY_TELEGRAM_LONG_POLLING_ENABLED=true`.
- Авторизация по ID/username: `AGENT_GATEWAY_TELEGRAM_ALLOWED_USER_IDS` / `ALLOWED_USERNAMES`.
