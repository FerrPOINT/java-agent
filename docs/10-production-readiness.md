# Production readiness

## Docker

```bash
docker compose up --build
```

- PostgreSQL 16 на порту 5432.
- Backend на порту 8080.
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

## Логи

- В dev-профиле — цветной plain-text.
- В prod-профиле — JSON через Logstash encoder.

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
| `AGENT_SERVER_PORT` | 8090 | HTTP port |
| `AGENT_WORK_DIR` | ./work | Workspace для файловых операций |
