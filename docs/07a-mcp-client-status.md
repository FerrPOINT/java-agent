# MCP клиент в java-agent-backend

## Текущее состояние

| Возможность | Статус | Примечание |
|-------------|--------|------------|
| Подключение к HTTP/SSE MCP-серверу | ✅ | `McpLifecycleManager` использует `HttpClientSseClientTransport` из `mcp-core:2.0.0`. |
| Подключение к stdio MCP-серверу | ✅ | `StdioClientTransport` + `ServerParameters` запускает процесс. |
| Автоматический discovery инструментов | ✅ | При старте вызывается `tools/list` и инструменты регистрируются в `SpringToolRegistry` как `<server>__<tool>`. |
| Вызов MCP-инструмента через tool loop | ✅ | Модель видит discovered tools и вызывает их напрямую. Также остаётся generic `mcp_tool`. |
| Graceful shutdown | ✅ | `ContextClosedEvent` закрывает клиентов и stdio-процессы. |
| Инспекция подключённых серверов | ✅ | `GET /api/v1/mcp/servers` возвращает серверы, transport и список инструментов. |
| Конфигурация по умолчанию | ❌ отключена | `agent.mcp.enabled: false`, `agent.mcp.servers: []`. |

## Конфигурация в `application.yml`

```yaml
agent:
  mcp:
    enabled: true
    servers:
      - name: fs
        transport: stdio
        command: npx
        args:
          - -y
          - @modelcontextprotocol/server-filesystem
          - /tmp
      - name: weather
        transport: sse
        baseUrl: http://localhost:3001/sse
```

- `transport: stdio` — запускает subprocess с `command`/`args`.
- `transport: sse` — подключается к `baseUrl`.

## Примеры использования

### HTTP/SSE MCP-сервер

```bash
curl -s http://localhost:8090/api/v1/mcp/servers
```

Ответ:

```json
[
  {"name":"stdio-test","baseUrl":"","transport":"stdio","toolCount":1,"toolNames":["multiply"]}
]
```

### Вызов через чат

```bash
curl -X POST http://localhost:8090/api/v1/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionName":"mcp-smoke","message":"Use stdio-test__multiply to calculate 7*8."}'
```

## Известные ограничения

- Профиль `noop` не вызывает инструменты, поэтому MCP в нём неактивен.
- stdio серверы не перезапускаются автоматически после падения.
- Каждый discovered tool регистрируется с префиксом `<server>__`.
