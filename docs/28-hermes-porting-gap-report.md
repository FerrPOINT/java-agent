# Сравнительный аудит: что из Hermes ещё не перенесено в java-agent

Дата: 2026-07-31  
Автор: auto-audit

## Методика

- Проверены кодовые базы `/opt/dev/hermes-workspace/hermes-agent` (Hermes Python) и `/opt/dev/java-agent` (java-agent Spring Boot).
- Сопоставлены: CLI-команды, инструменты агента, gateway/платформы, продвинутые подсистемы.
- Статусы:
  - **✅ implemented** — есть рабочий аналог в java-agent.
  - **⚠️ partial** — есть часть функционала, но не полный порт или не wiring end-to-end.
  - **❌ missing** — в java-agent отсутствует.

---

## 1. CLI-команды

java-agent CLI — это REPL со slash-командами, а не POSIX-подобный `hermes <command>`. Поэтому большинство top-level команд Hermes пока не реализованы как отдельные режимы.

| Hermes command | java-agent status | Notes |
|---|---|---|
| `chat` | ✅ | Core REPL; любой не-`/`-ввод уходит в backend как чат. |
| `model` | ✅ | `/model [name] [provider]` переключает модель. |
| `setup` | ❌ | Нет интерактивного setup-wizard. Конфигурация через `application.yml`/env. |
| `config` | ❌ | Нет просмотра/редактирования конфига из CLI. |
| `doctor` | ❌ | Нет диагностики/проверки конфига. |
| `status` | ✅ | `/status` — сессия + backend health. |
| `version` | ✅ | `/version` — версия CLI. |
| `update` | ❌ | Нет self-update. |
| `uninstall` | ❌ | — |
| `backup` | ❌ | — |
| `import` | ❌ | — |
| `logs` | ❌ | — |
| `debug` | ❌ | — |
| `dump` | ❌ | — |
| `profile` | ❌ | Нет управления профилями. |
| `completion` | ⚠️ | Есть JLine slash-completion, но нет генерации shell-completion скрипта. |
| `dashboard` | ❌ | Нет web-UI лаунчера. |
| `claw` | ❌ | — |
| `honcho` | ❌ | — |
| `gui` / `--tui` | ❌ | Нет desktop/TUI режима. |
| `security` | ❌ | — |
| `prompt-size` | ❌ | — |

---

## 2. Инструменты агента

| Hermes tool / category | java-agent class | Status | Notes |
|---|---|---|---|
| `read_file` | `ReadFileTool` | ✅ | offset/limit + path safety. |
| `write_file` | `WriteFileTool` | ✅ | overwrite/create + blocked paths. |
| `patch` | `PatchTool` | ✅ | find/replace + V4A multi-file. |
| `search_files` | `SearchFilesTool` | ✅ | regex/glob. |
| `delete_file` | `DeleteFileTool` | ✅ | есть в java-agent; в Hermes не expose'ится. |
| `terminal` | `TerminalTool` | ✅ | foreground/background + dangerous-pattern block. |
| `process` | `ProcessTool` | ✅ | list/poll/log/wait/kill/write/submit/close. |
| `read_terminal` | — | ❌ | Нет чтения live-вывода терминала. |
| `web_search` | `WebSearchTool` | ✅ | DuckDuckGo. |
| `web_extract` | `WebExtractTool` | ✅ | jsoup readable text. |
| `x_search` | — | ❌ | X/Twitter search. |
| `browser_*` (navigate/snapshot/click/type/scroll/back/press/get_images/console/dialog/cdp) | 12 классов под `tools/browser` | ✅ | полный CDP Chromium. |
| `vision_analyze` | `VisionAnalyzeTool` | ✅ | в java-agent привязан к toolset `browser`. |
| `video_analyze` | — | ❌ | — |
| `memory` | `MemoryTool` + `DatabaseMemoryProvider` | ✅ | add/replace/remove/read + staged writes. |
| `skills_list` | `SkillsListTool` | ✅ | — |
| `skill_view` | `SkillViewTool` | ✅ | — |
| `skill_manage` | `SkillManageTool` | ✅ | — |
| `todo` | `TodoTool` | ✅ | session-scoped todos. |
| `cronjob` | `CronJobTool` | ✅ | create/list/pause/resume/remove/run. |
| `text_to_speech` | `TtsTool` | ✅ | — |
| `image_generate` | `ImageGenTool` | ✅ | — |
| `delegate_task` | `DelegateTaskTool` | ✅ | HTTP delegate на `localhost:8090`. |
| `mcp` | `McpTool` + `McpLifecycleManager` | ⚠️ | один wrapper + динамические хендлеры; не такой же UX как `<server>:<tool>` у Hermes. |
| `execute_code` | `ExecuteCodeTool` | ✅ | Python-скрипт; Hermes поддерживает больше языков. |
| `send_message` | `SendMessageTool` | ⚠️ | Класс есть, но **не аннотирован `@AgentTool`**, поэтому не регистрируется автоматически. |
| `session_search` | `SessionSearchTool` | ✅ | под toolset `memory`. |
| `clarify` | `ClarifyTool` | ✅ | — |
| **kanban tools** | — | ❌ | полное отсутствие. |
| **computer_use** | — | ❌ | только browser automation. |

---

## 3. Gateway / платформы

| Hermes feature | java-agent status | Notes |
|---|---|---|
| Telegram bot | ✅ | отдельный `telegram-bot` модуль, 56 команд. |
| WhatsApp | ❌ | нет адаптера. |
| Slack | ❌ | нет адаптера. |
| Discord | ❌ | нет адаптера. |
| Generic webhook | ❌ | только Telegram webhook controller. |
| Session handling | ✅ | `SessionResolver` + `SessionEntity` + `BotSessionStore`. |
| Busy mode queue/interrupt | ✅ | `BusySessionHandler`; race condition в `drainQueue` исправлена. |
| `/goal` + auto-continue | ⚠️ | Команды `/goal`/`/subgoal` есть, но judge model + auto-continue loop отсутствуют. |
| `/resume` / `/pause` | ⚠️ | `/resume` списка сессий есть; глобальной паузы хода нет. |
| Voice mode (Telegram) | ✅ | `/voice` + backend TTS + voice-сообщения. |
| Fast mode (Telegram) | ⚠️ | `/fast` хранит флаг в сессии бота, но **не передаёт `fastMode` в backend chat request**. Для CLI работает. |

---

## 4. Продвинутые подсистемы

| Subsystem | Hermes | java-agent | Status / Notes |
|---|---|---|---|
| Memory provider/manager | `MemoryProvider` + `MemoryManager` + plugins | `DatabaseMemoryProvider` + `MemoryManager` + `BackgroundReviewService` | ✅ оба есть. |
| Skills hub / bundles / curator / backup | Full stack | `SkillsHubService`, `SkillBundleService`, `CuratorService`, `CuratorBackupService` | ✅ оба есть. |
| Checkpoints | `tools/checkpoint_manager.py` | `CheckpointManager` + `CheckpointEntity` + REST | ✅ оба есть. |
| MCP | MCP client + Hermes-as-MCP-server | MCP client + `McpController` | ⚠️ partial; Hermes также выступает MCP-сервером. |
| Browser/CDP | Full CDP toolset | Full CDP toolset + auto-download Chromium | ✅ оба есть. |
| Security/guardrails/approval | `tool_guardrails`, `file_safety`, `redact`, `approval` | `DefaultToolGuardrails`, `ApprovalQueue`, `DefaultFileSafety`, `DefaultUrlSafety`, `DefaultRedactor` | ✅ оба есть. |
| Cron jobs | `cron/scheduler.py` | `CronJobService` + `CronJobController` | ✅ оба есть. |
| ACP | Full ACP server + Copilot client | только упоминание в `docs/17-cli-mcp-porting.md` | ❌ missing |
| Kanban | Full kanban subsystem | только env-переменные для skill matching | ❌ missing |
| Computer-use / desktop automation | Full `computer_use` tool | только browser-based | ❌ missing |
| MOA, Feishu, Spotify, HomeAssistant, Yuanbao | есть/упоминаются | нет | ❌ missing |

---

## 5. Ключевые провалы / приоритеты

### P0 — без этого java-agent не заменит Hermes в повседневной работе

1. **Telegram fast mode wiring** — `ChatRequest` из telegram-bot не передаёт `fastMode`/`reasoningEffort`/`voiceMode` в backend. CLI работает, бот — нет.
2. **Goal auto-continuation** — `/goal` в telegram-bot — stub; нет judge model и loop продолжения.
3. **`SendMessageTool` не зарегистрирован** — класс есть, но не `@AgentTool`.

### P1 — большое расхождение с Hermes

4. **Top-level CLI commands** — `setup`, `config`, `doctor`, `update`, `uninstall`, `backup`, `import`, `logs`, `debug`, `dump`, `profile`, `security`, `prompt-size`.
5. **Other platforms** — WhatsApp, Slack, Discord, generic webhook.
6. **Kanban subsystem** — отсутствует полностью.
7. **Computer-use / desktop automation** — отсутствует.

### P2 — нишевые инструменты

8. `read_terminal`, `x_search`, `video_analyze`, transcription/voice mode tool, MOA, Feishu, HomeAssistant.
9. **ACP** — запланирован в docs, не реализован.

---

## 6. Что перенесено хорошо

- Core agent runtime, chat, streaming.
- File/terminal/process/web/browser/vision tools.
- Memory, skills, todos, cron, checkpoints.
- TTS, imagegen, delegate, MCP client, security/guardrails.
- Telegram bot base + CLI REPL + backend endpoints.

Вывод: java-agent — это уже функциональный порт ядра Hermes, но с существенными пробелами в CLI surface, multi-platform gateway, goal subsystem и ряде продвинутых подсистем (ACP, kanban, computer-use).
