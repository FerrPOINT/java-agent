# Builtin tools

## Реализованные инструменты

| Tool | Toolset | Описание | Статус |
|------|---------|----------|--------|
| `read_file` | file | Чтение файла | ✅ |
| `write_file` | file | Запись файла | ✅ |
| `search_files` | file | Поиск в файлах | ✅ |
| `patch` | file | Патчи файлов | ✅ |
| `terminal` | terminal | Shell-команды | ✅ |
| `process` | terminal | Управление фоновыми процессами | ✅ |
| `execute_code` | coding | Выполнение Python-кода | ✅ |
| `web_search` | web | Веб-поиск | ✅ |
| `web_extract` | web | Извлечение текста из страниц | ✅ |
| `browser_navigate` | browser | Навигация браузера | ✅ |
| `browser_click` | browser | Клик по элементу | ✅ |
| `browser_type` | browser | Ввод текста | ✅ |
| `browser_press` | browser | Нажатие клавиши | ✅ |
| `browser_scroll` | browser | Скролл | ✅ |
| `browser_back` | browser | Назад | ✅ |
| `browser_console` | browser | Выполнение JS | ✅ |
| `browser_dialog` | browser | Диалоги | ✅ |
| `browser_get_images` | browser | Список изображений | ✅ |
| `browser_snapshot` | browser | Текстовый снапшот | ✅ |
| `browser_vision` | browser | Скриншот | ✅ |
| `vision_analyze` | browser | Анализ изображения | ✅ |
| `memory` | memory | Хранение фактов | ✅ |
| `session_search` | memory | Поиск по сессиям | ✅ |
| `skill_manage` | skills | Создание/обновление/удаление скиллов | ✅ |
| `skill_view` | skills | Чтение скилла | ✅ |
| `skills_list` | skills | Список скиллов | ✅ |
| `clarify` | core | Уточняющий вопрос | ✅ |
| `delegate_task` | delegate | Делегирование subagent | ✅ |
| `mcp_tool` | core | Generic MCP-вызов | ✅ |

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
```

`mcp` не отдельный toolset — discovered MCP-инструменты всегда доступны, если `agent.mcp.enabled: true`.
