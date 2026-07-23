# MCP клиент в java-agent-backend

## Что найдено

Классы MCP расположены в двух пакетах:

- `src/main/java/com/azhukov/agent/client/mcp/McpLifecycleManager.java`
- `src/main/java/com/azhukov/agent/tools/mcp/McpTool.java`

### Состояние реализации

| Возможность | Статус | Примечание |
|-------------|--------|------------|
| Подключение к HTTP/SSE MCP-серверу | ✅ частично | `McpLifecycleManager.ensureConnected(name, baseUrl)` использует `HttpClientSseClientTransport` из `mcp-core:2.0.0`. |
| Запуск stdio MCP-сервера из `agent.mcp.servers` | ❌ не реализовано | В `application.yml` серверы задаются через `command`/`args`, но в коде нет запуска процесса и stdio → SSE моста. |
| Автоматический discovery инструментов в tool loop | ❌ не реализовано | В `SpringToolRegistry` регистрируется только generic `mcp_tool`. Модель сама должна вызывать его с параметрами `serverName`, `toolName`, `arguments`. |
| Вызов MCP-инструмента | ✅ частично | `McpTool.execute(...)` парсит аргументы и вызывает `McpLifecycleManager.executeTool(...)`. Работает только если клиент уже подключён. |
| Конфигурация по умолчанию | ❌ отключена | В `application.yml`: `agent.mcp.enabled: false`, `agent.mcp.servers: []`. |

### Конфигурация в `application.yml`

```yaml
agent:
  mcp:
    enabled: ${AGENT_MCP_ENABLED:false}
    servers: []   # пустой список по умолчанию
```

Профиль `noop` отключает память (`agent.memory.enabled=false`) и навыки (`agent.skills.enabled=false`) и переключает модель на `noop`, но **не отключает** сам `McpLifecycleManager` и `McpTool` — они остаются Spring-бинами.

### Запуск с профилем `cli,noop`

```bash
cd /opt/dev/java-agent/backend
JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED' \
  java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=cli,noop
```

При запуске:
- Spring Boot стартует в `WebApplicationType.NONE`.
- Активны профили `cli`, `noop`.
- `NoOpModelClient` возвращает `NoOp response: <user input>`.
- Инструменты **не вызываются** в профиле `noop`, потому что модель не генерирует `toolCalls`.
- Прямая попытка ввода JSON (`{"serverName":"fs",...}`) просто эхируется NoOp-моделью; никакой `mcp_tool` не исполняется.

## Проверка с реальным MCP-сервером

Пример `npx @modelcontextprotocol/server-filesystem` — это **stdio-сервер**. В системе установлен `mcp-server-filesystem` (npm), но он работает только через stdio:

```bash
mcp-server-filesystem /tmp
# Secure MCP Filesystem Server running on stdio
```

Клиент в `McpLifecycleManager` умеет только HTTP/SSE, поэтому напрямую к stdio-серверу он **не подключается**. Нужен stdio-to-SSE bridge (например, MCP Inspector, `mcp-proxy`, `supergateway` или собственная обёртка).

## Что нужно для полноценной интеграции

1. **Запуск и мост stdio → SSE**  
   Добавить компонент, который запускает процесс по `command`/`args` из `agent.mcp.servers` и транслирует stdio в HTTP/SSE. Альтернатива: запускать MCP-сервер, который сам exposes HTTP/SSE, и указывать его `baseUrl` в конфиге.

2. **baseUrl в конфигурации сервера**  
   Сейчас `ServerProperties` содержит `name`, `command`, `args`, `env`, `timeoutSeconds`, но нет поля `baseUrl`. Нужно добавить его для HTTP/SSE серверов.

3. **Авто-подключение на старте**  
   `McpLifecycleManager` не подключает сконфигурированные серверы автоматически. Нужно вызывать `ensureConnected(name, baseUrl)` для серверов с `baseUrl` при старте приложения.

4. **Discovery инструментов в tool loop**  
   `McpTool` зарегистрирован как один generic инструмент `mcp_tool`. Для полноценной работы нужно при подключении сервера вызывать `listTools()` и регистрировать каждый MCP-инструмент как отдельный `ToolDefinition` (или динамически расширять схему `mcp_tool`).

5. **Включение MCP**  
   Установить `AGENT_MCP_ENABLED=true` и заполнить `agent.mcp.servers`.

6. **Учёт `mcp` в default toolsets**  
   В `AgentProperties.SkillsProperties.defaultToolsets` `mcp` отсутствует. Инструмент `mcp_tool` привязан к toolset `core`, который уже есть в списке, поэтому generic wrapper доступен. Если MCP станет отдельным toolset, добавить его в список.

7. **Профиль `noop` и tool loop**  
   В `noop` модель никогда не вызывает инструменты. Для ручной проверки `mcp_tool` нужен профиль с реальной LLM (например, `dev` + Ollama) или отдельный тестовый эндпоинт/API.

## Пример целевой конфигурации

```yaml
agent:
  mcp:
    enabled: true
    servers:
      - name: fs
        baseUrl: http://localhost:3001/sse   # для HTTP/SSE сервера
        # или command/args для stdio-сервера + bridge
        command: npx
        args:
          - -y
          - @modelcontextprotocol/server-filesystem
          - /tmp
```

## Сводка

- MCP SDK (`mcp:2.0.0`) подключён, `McpLifecycleManager` компилируется и может работать с HTTP/SSE MCP-серверами.
- В профиле `cli,noop` MCP не используется, потому что модель — NoOp и не вызывает инструменты.
- Прямой вызов `mcp_tool` из CLI невозможен: CLI передаёт весь ввод модели; исполнение инструментов происходит только при `toolCalls` от LLM.
- Для интеграции с `npx @modelcontextprotocol/server-filesystem` требуется:
  1. stdio-to-SSE мост;
  2. поле `baseUrl` в `McpProperties.ServerProperties`;
  3. реализация запуска/перезапуска серверов;
  4. discovery MCP-инструментов в `SpringToolRegistry`.
