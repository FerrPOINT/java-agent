# Builtin tools

## Реализованные инструменты

| Tool | Toolset | Описание | Статус |
|------|---------|----------|--------|
| `read_file` | file | Чтение файла. Учитывает `agent.security.allowed-paths`. | ✅ |
| `write_file` | file | Запись файла. Блокирует опасные пути (`/etc/passwd`, `~/.ssh`, …). | ✅ |
| `search_files` | file | Поиск в файлах. | ✅ |
| `patch` | file | Патчи файлов. | ✅ |
| `terminal` | terminal | Shell-команды. `blocked-commands` конфигурируются. | ✅ |
| `process` | terminal | Управление фоновыми процессами. | ✅ |
| `execute_code` | coding | Выполнение Python-кода. | ✅ |
| `web_search` | web | Веб-поиск (ddg / searxng). | ✅ |
| `web_extract` | web | Извлечение текста из страниц. URL проверяются через `UrlSafety`. | ✅ |
| `browser_navigate` | browser | Навигация браузера по CDP. | ✅ |
| `browser_click` | browser | Клик по элементу. | ✅ |
| `browser_type` | browser | Ввод текста. | ✅ |
| `browser_press` | browser | Нажатие клавиши. | ✅ |
| `browser_scroll` | browser | Скролл. | ✅ |
| `browser_back` | browser | Назад. | ✅ |
| `browser_console` | browser | Выполнение JS в контексте страницы. | ✅ |
| `browser_dialog` | browser | Диалоги. | ✅ |
| `browser_get_images` | browser | Список изображений. | ✅ |
| `browser_snapshot` | browser | Текстовый снапшот DOM. | ✅ |
| `browser_vision` | browser | Скриншот (base64). | ✅ |
| `browser_cdp` | browser | Прямой CDP-вызов. | ✅ |
| `vision_analyze` | browser | Анализ изображения через vision-модель. | ✅ |
| `memory` | memory | Хранение фактов. | ✅ |
| `session_search` | memory | Поиск по сессиям. | ✅ |
| `skill_manage` | skills | Создание/обновление/удаление скиллов. | ✅ |
| `skill_view` | skills | Чтение скилла. | ✅ |
| `skills_list` | skills | Список скиллов. | ✅ |
| `todo` | memory | Управление todo. | ✅ |
| `clarify` | core | Уточняющий вопрос пользователю. | ✅ |
| `delegate_task` | delegate | Делегирование subagent. | ✅ |
| `mcp_tool` | core | Generic MCP-вызов (discovered tools). | ✅ |
| `send_message` | gateway | Отправка исходящего сообщения (Telegram). | ✅ |

## Toolsets по умолчанию

В `application.yml`:

```yaml
agent:
  skills:
    default-toolsets:
      - web
      - file
      - browser
      - terminal
      - coding
      - memory
      - skills
      - core
      - delegate
      - gateway
```

`mcp` не отдельный toolset — discovered MCP-инструменты доступны, если `agent.mcp.enabled: true`.

## Gateway-инструменты

- `send_message` отправляет сообщение через активный gateway-адаптер (Telegram).
- Telegram gateway поддерживает webhook и long-polling.
- Входящие сообщения маршрутизируются через `GatewayRoutingService.dispatchInbound` → `AgentRuntime`.

## Безопасность

- `agent.security.approvals-enabled: true` — запрашивает подтверждение на опасные операции.
- `agent.security.file-safety-enabled: true` — проверка путей для `read_file`/`write_file`.
- `agent.security.allowed-paths` — список разрешённых базовых директорий.
- `agent.security.blocked-commands` — паттерны shell-команд, запрещённых для `terminal`.
- `agent.security.blocked-url-hosts` — хосты, недоступные для `web_extract`/`browser_navigate`.
- `agent.security.redact-enabled: true` — замена секретов на `[REDACTED]`.
