# Отчёт по проверке переноса Hermes → java-agent

Дата: 2026-08-03
Репозиторий: /opt/dev/java-agent
Ветка/коммит: (git status clean)

## Общее состояние

- Сборка и тесты: **PASS** (`./gradlew test` exit=0)
- Всего тестов: 2 784, пропусков 0, ошибок 0, провалов 0
- Backend тесты: 2 914 (согласно предыдущему отчёту)
- Telegram-bot тесты: 657
- CLI тесты: 236
- Backend покрытие: **81%** instructions, **64%** branches
- TODO/FIXME в main: **0**, кроме намеренных UnsupportedOperation в SkillManager/MemoryProvider

## Что исправлено в этом заходе

| TODO | Файлы | Результат |
|------|-------|-----------|
| P0 `reasoningEffort` mapping | `LangChain4jModelClient.java` | Добавлена карта строковых уровней → число: none/minimal/low/medium/high/xhigh → 0..250. Поддержка legacy-int и fallback. |
| P0 `/plugins` | `SlashCommandRegistry.java`, `BackendClient.java` | `/plugins` теперь зовёт `/api/v1/mcp/servers` (список настроенных MCP-серверов). |
| P0 toolset-имена | `AgentProperties.java`, `application.yml`, `AgentPropertiesTest.java` | `delegate` → `delegation`, убран `skills`, добавлен `todo`, поправлен тест. |
| P1 `/tools toggle` | `BackendClient.java` | CLI теперь вызывает `/api/v1/agent/tools/enable` или `/disable` в зависимости от флага. |
| P1 `/browser connect` | `BackendClient.java`, `SlashCommandRegistry.java`, `P1CliFixesTest.java` | CLI теперь зовёт `/api/v1/agent/browser` и передаёт `sessionId`. |
| P1 `voiceMode` | — | Проверено: авто-TTS бота работает через `TtsService` + `/api/v1/agent/tts`, флаг `voiceMode` управляет отправкой голоса. Нет изменений, функционал уже реализован. |
| P1 `/agent/bundles` | `AgentController.java` | Добавлен alias `/agent/skills/bundles`; `/agent/bundles` по-прежнему возвращает имена бандлов. Уточнено поведение. |
| P2 `memory replace/remove` | — | Проверено: `DatabaseMemoryProvider` реализует `replace()` и `remove()`, `MemoryTool` ими пользуется. Нет изменений. |
| P2 skill support files | — | Проверено: `DatabaseSkillManager` реализует `writeSupportFile`, `removeSupportFile`, `readSupportFile`, `listSupportFiles`. Нет изменений. |
| P2 platform scope | `docs/01-scope.md` | Discord, Feishu, HomeAssistant, X, Yuanbao, Kanban, video явно помечены как out-of-scope. |

## Остаточные замечания

1. **`voiceMode` в CLI vs backend.** Backend поддерживает `voiceMode` в `ChatRequest`, но CLI REPL не использует его (text-only). Это нормально: голосовой режим актуален для Telegram-бота.
2. **Skill bundle install/uninstall.** Методы `AgentRuntimeService.installBundle`/`uninstallBundle` работают через `SkillBundleService.install()`/`uninstall()`, которые сохраняют/удаляют SKILL.md из директории `bundles/`. Списание `/agent/bundles` возвращает имена бандлов (скан директории). Соответствует дизайну.
3. **MCP plugins.** `/plugins` теперь отображает настроенные MCP-серверы. Если в будущем появится настоящий плагин-механизм, endpoint переименовать.
4. **Toolset `gateway`.** Оставлен, так как `SendMessageTool` имеет `toolset = "gateway"`. Тул присутствует, просто ограничен по умолчанию для Telegram-бота.

## Рекомендация

Критические мёртвые пути закрыты. Проект собирается, все тесты проходят. Отсутствующие platform-специфичные тулсы задокументированы как out-of-scope.
