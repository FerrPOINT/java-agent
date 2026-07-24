# Context compression & production hardening

## Context compression

`DefaultContextEngine` строит контекст для LLM с ограничениями, заданными в `agent.context`:

| Свойство | Default | Описание |
|---|---|---|
| `maxContextMessages` | 50 | Максимальное число сообщений |
| `maxTokens` | 16000 | Жёсткий лимит токенов |
| `targetTokens` | 12000 | Целевой лимит токенов |
| `summaryChunkTokens` | 2000 | (reserved) размер чанка для суммаризации |

Оценка токенов выполняется приблизительно: `chars / 4` + 20 на сообщение.

### Алгоритм

1. Загружается системный промпт + skills + memory recall.
2. Подгружается последняя история до `maxContextMessages`.
3. Добавляются сообщения текущего хода.
4. Если контекст превышает лимиты, удаляются старые non-system сообщения (кроме последнего пользовательского).
5. Если всё ещё превышает `maxTokens`, оставляются только system + последнее сообщение пользователя.

> Полная LLM-суммаризация истории в backlog; текущая реализация — production-ready truncation.

## Production packaging

### Docker

```bash
docker compose up --build
```

- Базовый образ: `eclipse-temurin:25-jre-noble` (Ubuntu 24.04).
- Установлены runtime-зависимости Chromium.
- `agent_chromium` volume кэширует скачанный Chromium.
- `AGENT_CHROMIUM_AUTO_INSTALL=true` позволяет приложению самостоятельно скачать Chromium.

### Важные production-настройки

```yaml
server:
  shutdown: immediate   # workaround Spring Boot 4.1.0 graceful shutdown bug
spring:
  threads:
    virtual:
      enabled: true
```

### Health checks

- `/actuator/health` — общий статус.
- `/actuator/health/db` — БД.
- `/actuator/health/model` — модель.
- `/actuator/health/browser` — Chromium/CDP.

## Безопасность

- `SafetyGuard` проверяет пути, URL, переменные окружения, секреты.
- `ApprovalGate` для опасных операций.
- Профили `prod`/`dev`/`noop` изолируют окружения.
