# SSE streaming

Приложение поддерживает потоковую передачу ответов модели через Server-Sent Events (SSE) без использования Reactor/Flux. Это позволяет клиентам получать токены по мере генерации.

## Endpoints

### Internal

```bash
POST /api/v1/agent/chat/stream
```

Content-Type: `application/json`
Accept: `text/event-stream`

Тело:

```json
{
  "sessionId": null,
  "message": "расскажи анекдот",
  "delegationDepth": null,
  "timeoutMs": 600000
}
```

События:

| Event | Описание |
|---|---|
| `token` | Очередная порция текста |
| `tool_calls` | Модель вызвала инструменты |
| `done` | Генерация завершена |
| `error` | Ошибка |

### OpenAI-compatible

```bash
POST /v1/chat/completions
```

```json
{
  "model": "kimi-k2.6",
  "messages": [{"role": "user", "content": "hi"}],
  "stream": true
}
```

Формат чанков совместим с OpenAI streaming API:

```text
data: {"id":"chatcmpl-...","object":"chat.completion.chunk", ...}

data: [DONE]
```

## Реализация

- `ModelClient#stream(..., StreamingResponseHandler)` — абстракция для всех клиентов.
- `LangChain4jModelClient` использует `OpenAiStreamingChatModel` + `StreamingChatResponseHandler`.
- `AgentStreamingService` и `ChatCompletionsController` транслируют токены в `SseEmitter`.
- Нет `Flux`, `Mono`, `Publisher` в прикладном коде.

## Пример клиента на curl

```bash
curl -N -X POST http://localhost:8090/api/v1/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"Привет"}'
```

## Ограничения

- Стриминг не запускает tool loop: если модель запрашивает инструменты, они возвращаются как событие `tool_calls`, и клиент должен сам продолжить диалог с результатами.
- Для полного цикла с инструментами используйте синхронный `/api/v1/agent/chat`.
