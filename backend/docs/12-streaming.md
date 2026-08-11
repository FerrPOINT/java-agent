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

### События (9 типов)

| Event | Описание |
|---|---|
| `token` | Очередная порция текста от модели |
| `tool_calls` | Модель вызвала один или несколько инструментов |
| `tool_start` | Начало выполнения конкретного инструмента |
| `tool_result` | Результат выполнения инструмента (превью) |
| `metadata` | Метаданные об использованной модели и контексте |
| `done` | Генерация завершена (финальное событие) |
| `error` | Ошибка |
| `interrupted` | Поток прерван пользователем |
| `retry` | Повторная попытка после ошибки |
| `continuation` | Отправлен continuation prompt при пустом ответе |

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
- При отмене (`InterruptToken.isCancelled()`) клиент выбрасывает `TurnInterruptedException`, что приводит к досрочному освобождению latch и остановке модельного стрима.
- `AgentStreamingService` транслирует токены в `SseEmitter` и запускает полный agentic loop (tool loop).
- `SseEmitter` lifecycle callbacks (`onTimeout`, `onError`, `onCompletion`) регистрируются для отмены стрима через `InterruptToken.cancel()`.
- Флаг `clientDisconnected` отслеживает отключение клиента; `send()` пропускает запись если клиент отключился.
- `InterruptToken.remove(sessionId)` вызывается после завершения стрима для освобождения map entries.
- Нет `Flux`, `Mono`, `Publisher` в прикладном коде.

## Пример клиента на curl

```bash
curl -N -X POST http://localhost:8090/api/v1/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"Привет"}'
```

## Tool loop

Стриминг **запускает полный agentic loop**: если модель запрашивает инструменты,
`AgentStreamingService` выполняет их и отправляет результаты обратно в модель,
продолжая цикл до завершения ответа или достижения лимита итераций (`agent.core.max-turns`).

События в порядке для типичного цикла с инструментами:

```
token → tool_calls → tool_start → tool_result → token → ... → metadata → done
```

Для полного цикла без стриминга (синхронный) используйте `/api/v1/agent/chat`.