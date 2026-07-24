# Browser + Vision

## Endpoints

| Endpoint | Метод | Описание |
|----------|-------|----------|
| `/api/v1/agent/chat` | POST | Универсальный чат с tool loop. Vision-запрос можно выполнить через инструмент `vision_analyze`. |
| `/api/v1/agent/vision` | POST | Dedicated endpoint: скриншот URL → vision-анализ. |

## `POST /api/v1/agent/vision`

Тело запроса:
```json
{
  "url": "https://example.com",
  "prompt": "What is shown on the page?",
  "waitSeconds": 2
}
```

- `url` — адрес страницы.
- `prompt` — вопрос к модели.
- `waitSeconds` — ожидание после navigate (необязательно).

### Пример

```bash
curl -X POST http://localhost:8090/api/v1/agent/vision \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","prompt":"What is shown on the page?"}'
```

## Vision через чат

```bash
curl -X POST http://localhost:8090/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "sessionName": "vision-chat",
    "message": "Go to https://example.com, take a screenshot and describe what you see."
  }'
```

Агент сам вызовет `browser_navigate`, `browser_vision` и `vision_analyze`.

## Настройки

```yaml
agent:
  browser:
    cdp-url: http://localhost:9222
  vision:
    provider: openai-compatible
    base-url: https://ollama.com/v1
    api-key: ${OLLAMA_API_KEY}
    model-name: kimi-k2.6
```

`vision_analyze` сначала пытается использовать auxiliary-модель, если она настроена и `use-auxiliary-first: true`.
