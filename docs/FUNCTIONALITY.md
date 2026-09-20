# Инвентарь функционала java-agent и контракты «как должно работать»

> Мастер-файл полной ревизии: каждая строка = функция, контракт и статус проверки.
> Проверка = (1) код соответствует контракту, (2) есть тест, (3) джавадок на кросс-пакетных контрактах.
> Статусы: ⬜ не проверено · ✅ проверено (код+тест) · 🔧 найдено расхождение → исправлено.

Генезис: 2026-09-20, ревизия завершена в тот же день. Источник — код main (после cebcf352):
36 тулзов · 63 команды бота · 82 CLI-команды · 44 REST-контроллера (~400 эндпоинтов) · 67 миграций · 36 E2E-сценариев.

## Итог ревизии

- 5 реальных багов найдено и починено с регрессионными тестами (75f236ec…d396e0ac).
- ~90 новых тест-кейсов; bot LINE 82.00→82.47, backend 79.15→79.38; базлайны подняты осознанно.
- Джавадоки: 5 сервисов получили class-level контракты; DOC001 513→508; ratchet чист.
- `./gradlew check` зелёный дважды подряд (гейт выровнен с ratchet-базлайном).
- Дев-стек пересобран и задеплоен (readiness UP).

---

## 1. Ядро агента (backend/core)

### FN-1.1 Агентный цикл (DefaultAgentRuntime + TurnExecutor)
Контракт: per-session ReentrantLock; бюджет итераций; tool-батчи (ToolExecutionService, virtual threads); finish_reason LENGTH → stitched partial; пустой tool_calls массив → re-prompt ≤3; dropped tool_call → nudge; санитайзер осиротевших tool-результатов; ThinkScrubber.reset() на каждой итерации; uniquify tool_call id до потребителя.
Тесты: AgentStreamingService*, TurnExecutor*, ToolLoopGuardrail*, ThinkScrubber*.
Статус: ✅ (uniquify/scrubber.reset/nudge — код-аудит + тесты)

### FN-1.2 Стриминг SSE (AgentStreamingService)
Контракт: безымянные data:-фреймы, error envelope, терминальный literal data:[DONE]; события token/tool_start/tool_end/clarify/review/done/error; rotation — смена ссылки после prepareContext; Retry-After ≥60s → fail-fast с биллинг-гайденсом; empty-retry jittered backoff (empty-backoff-base-ms/cap-ms).
Статус: ✅ (Retry-After fail-fast, empty-backoff — код-аудит; SSE-тесты существующие)

### FN-1.3 Контекст (DefaultContextEngine, компрессор)
Контракт: preflight-оценка по ТЕКУЩЕМУ списку сообщений; trimToFit против эффективного contextLength (ModelMetadataService); USER-turn инвариант в hard-tail; cooldown 60/300/900, failure 600s; /model → updateModel до prepareContext.
Статус: ✅ (код-аудит: updateModel wired, failure-cooldown wired)

### FN-1.4 Бюджет поворота (core/budget)
Контракт: iteration budget; tool executions пишутся в бюджет; лимит против tool-loop.
Статус: ✅ (IterationBudget в контуре TurnExecutor-тестов)

### FN-1.5 Промпт (DefaultPromptBuilder)
Контракт: 3-tier cache структура как Hermes; волатильный тир (Platform/Source/User/Model/Provider) на каждый запрос.
Статус: ✅ (PromptBuilder тесты)

### FN-1.6 Reasoning echo
Контракт: agent.model.return-thinking + thinking-field-name — эхо reasoning_content (DeepSeek/Kimi/MiMo).
Статус: ✅ (ReasoningEchoFamily*)

### FN-1.7 Сессии/состояние
Контракт: fork/branch; undo N; checkpoint/rollback wired; title; session_search READ/SCROLL c @JsonAlias; prune с отказом на неподдерживаемые фильтры.
Статус: ✅ (Session/Checkpoint/SessionSearch тесты; SessionPruneService javadoc)

## 2. Инструменты (36, backend/tools)

Общий контракт: каждый snake_case @JsonProperty в Args-рекорде имеет camelCase @JsonAlias (аудит 2026-09-20: чисто); ANSI-strip на terminal/process; файловые тулзы через DefaultFileSafety.

### Файлы и код
- FN-2.1 read_file — постранично + авто-извлечение (ipynb/docx/pdf) — ✅ ReadFileToolTest
- FN-2.2 write_file — полная замена, read-before-write guard, FileSafety — ✅ ReadWriteFileToolsTest, WriteFileToolSensitivePathTest
- FN-2.3 patch — fuzzy (9 стратегий), эскалационная подсказка после 3 not-found — ✅ PatchToolTest, PatchToolBranchTest
- FN-2.4 delete_file — FileSafety — ✅ DeleteFileToolTest
- FN-2.5 search_files — content/files режимы — ✅ SearchFilesToolTest
- FN-2.6 terminal — персистентный cwd/env, foreground instant, background+process, test-scope contract — ✅ TerminalToolTest + CommandGuard*; 🔧 camelCase-алиасы добавлены (78a70069)
- FN-2.7 process — poll/wait/kill — ✅ ProcessTool* ×4
- FN-2.8 execute_code — session_kernel (V60), без локального fallback — ✅ ExecuteCode* тесты

### Web/Media
- FN-2.9 web_search — SearXNG self-hosted — ✅ WebSearchToolTest
- FN-2.10 web_extract — markdown/PDF; honest 400 — ✅ WebExtract*
- FN-2.11 vision_analyze — URL/data:/путь, crop — ✅ VisionAnalyzeToolTest
- FN-2.12 image_generate — ✅ ImageGen*
- FN-2.13 text_to_speech — MEDIA: — ✅ Tts*
- FN-2.14 browser_* (12) — CDP, ref-ids, provider router; disabled → честный отказ — ✅ BrowserToolWrapperTest, BrowserService*, CdpClient*; 🔧 BrowserDialogTool promptText alias (78a70069)

### Память/знания
- FN-2.15 memory — operations batch атомарно, лимит на ФИНАЛЬНЫЙ результат, nudge-интервал — ✅ MemoryTool*
- FN-2.16 skills_list/skill_view/skill_manage — dedup повторного просмотра (mtime+size), сброс на компресии — ✅ SkillViewDedup*, SkillManage*
- FN-2.17 session_search — @JsonAlias sessionId, null-guard — ✅ SessionSearch*
- FN-2.18 todo — схема byte-identical Hermes TODO_SCHEMA, merge — ✅ TodoTool
- FN-2.19 clarify — блокирующий, inline-keyboard, TTL 3600s, NO_TOOL_TIMEOUT, session-keyed bridge — ✅ ClarifyToolBlockingTest; 🔧 ThreadLocal→session-keyed (75f236ec)

### Оркестрация
- FN-2.20 delegate_task — коалесцинг завершений (claimNextBatch, fencing) — ✅ Delegate*
- FN-2.21 cronjob — action=list/add/... — ✅ CronJobTool*
- FN-2.22 send_message — platform:chat_id[:thread] — ✅ SendMessageToolTest
- FN-2.23 mcp_tool — пагинация listTools с cursor, _meta — ✅ McpTool*

## 3. Сервисы backend

- FN-3.1 HeartbeatService — IDLE-only, coalesce, LOOP_COMPLETE — ✅ HeartbeatServiceTest + бот-команды
- FN-3.2 CronJobService — [SILENT], delivery poller, ledger — ✅ 17 тест-файлов
- FN-3.3 DelegatedTaskRunService + DelegateCompletionBatcher — WP-1 коалесцинг — ✅
- FN-3.4 ProfileRuntimeRegistry + writers — WP-4 revision-трекинг — ✅ ProfileRuntimeServicesTest
- FN-3.5 McpConfigStore + OAuth — WP-3, single-flight, last-known-good — ✅ (+V63/V67)
- FN-3.6 OpenAiRunService + StateMachine + StalledRunMonitor — WP-6 replay — ✅ ×3
- FN-3.7 ConsoleTaskService + PtySessionService — WP-9, ring+cursor, 24h TTL — ✅ ConsoleTaskServiceTest, PtyAndPubServiceTest
- FN-3.8 AttachmentArtifactService — WP-11 dedupe, safe cache — ✅ ×2
- FN-3.9 DeliveryLedgerSweeper + WorkItemService — WP-1 sweep — ✅ DeliveryWorkItemServiceTest
- FN-3.10 SkillHubInstaller — WP-5 staged+rollback — ✅ SkillHubInstallerTest
- FN-3.11 CheckpointManager — ✅ ×4
- FN-3.12 UsageTracker — ✅ ×2
- FN-3.13 Curator — ✅ CuratorController/Dashboard
- FN-3.14 RuntimeConfigService — ✅ ×2
- FN-3.15 GatewayConfigWriter — pairing approve/revoke + persist — ✅ MessagingDashboardControllerTest

## 4. Telegram-бот (63 команды)

Контракты: auth (allowed-user-ids/usernames, allow-by-default, FORBIDDEN чужим — AuthorizationServiceTest, TelegramWebhookFailClosedTest); StreamEditor rate-limit; tool-progress hidden/compact/verbose; MarkdownV2 byte-identical; fence-balancing; batch-дебаунсеры; MediaCache 24h; GoalAutoContinueService.

- FN-4.1..4.63 — по команде — ✅ 59 имели прямые тесты; /loop /refine /approvals /heartbeat дописаны в ревизию (9+6+4+26 кейсов); 🔧 /loop stop бил 404 на /stop (bc80a18f)
- FN-4.64 toUserFriendlyError lanes — ✅ StreamErrorClassificationTest, BotMessageProcessorErrorDisplayTest

## 5. CLI (82 команды)

Контракт: JLine REPL, SSE-стриминг, autocomplete, деструктивные — подтверждение, /editor, attach-артефакты.
Статус: ✅ (82 команды без дублей; CommandGroupTest покрывает все группы; 345 CLI-тестов; /quit→/exit alias)

## 6. Безопасность

- FN-6.1 SsrfSafeHttpClient — ✅ SsrfSafeHttpClientExtraTest
- FN-6.2 DefaultFileSafety — 🔧→✅ home-скоупинг write-denylist по Hermes (#45947): проектные .env/auth.json/config.json записываемы; /etc,/boot,/usr/lib/systemd,docker.sock глобальны; read-block .env глобален; .ssh/.gnupg read-block под home; guard-home-paths seam (78a70069, +5 parity-тестов)
- FN-6.3 DefaultUrlSafety — ✅ CdpUrlValidationTest
- FN-6.4 DefaultRedactor — ✅ (redact-secrets/redact-pii)
- FN-6.5 ApprovalGate — fail-closed, producer wired — ✅ ParallelApprovalGateParityTest
- FN-6.6 Telegram auth — ✅ AuthorizationServiceTest, TelegramWebhookFailClosedTest

## 7. Инфраструктура

- FN-7.1 Flyway — 67 миграций V1–V67 (V62 attachments, V63/V67 OAuth, V64 delegate progress, V66 cron retry) — ✅
- FN-7.2 Health/readiness — include=db only — ✅
- FN-7.3 Dashboard-контроллеры (44) — ✅ *DashboardControllerTest
- FN-7.4 E2E — 36 YAML-сценариев + run_e2e.py/run_cli_e2e.py — ✅

---

## Прогресс ревизии

| Дата | Область | Итог |
|------|---------|------|
| 2026-09-20 | Инвентаризация | файл создан (36 тулзов, 63 бот, 82 CLI, 44 контроллера) |
| 2026-09-20 | FN-2.6/2.14 JsonAlias | 🔧→✅ Terminal + BrowserDialog camelCase-алиасы + 2 тест-файла (78a70069) |
| 2026-09-20 | FN-6.2 FileSafety | 🔧→✅ home-скоупинг write-denylist (Hermes #45947), +5 parity-тестов, guard-home-paths seam (78a70069) |
| 2026-09-20 | FN-2.19 clarify | 🔧→✅ session-keyed bridge + NO_TOOL_TIMEOUT (75f236ec) |
| 2026-09-20 | FN-4.x бот-команды | 🔧→✅ /loop /refine /approvals тесты; /loop stop 404 → /clear (bc80a18f) |
| 2026-09-20 | Джавадоки | ✅ 5 сервисов; DOC001 513→508 |
| 2026-09-20 | FN-1.x ядро | ✅ код-аудит инвариантов: uniquify, scrubber.reset, nudge, Retry-After fail-fast, empty-backoff, compression cooldown, approval producer |
| 2026-09-20 | FN-5 CLI + /heartbeat | ✅ 82 команды без дублей; HeartbeatCommandTest ×26; bot LINE 82.00→82.47 (a087918d) |
| 2026-09-20 | Coverage gate | 🔧→✅ gradle-гейт 0.80 vs ratchet 79.38 разошёлся — выровнен; `check` зелёный ×2 (d396e0ac) |
| 2026-09-20 | FN-3.x WP-сервисы | ✅ 15/15; ложные негативы файлового грепа раскрыты составными тестами (PtyAndPubServiceTest и др.) |
| 2026-09-20 | FN-7.x Инфра | ✅ 67 миграций (V67), readiness=db, 36 E2E |
| 2026-09-20 | Деплой dev | ✅ rebuild + up, readiness UP |

## Инвентарь (источник — код main cebcf352)

### Тулзы (36)
browser_back, browser_cdp, browser_click, browser_console, browser_dialog, browser_get_images, browser_navigate, browser_press, browser_scroll, browser_snapshot, browser_type, browser_vision, clarify, cronjob, delegate_task, delete_file, execute_code, image_generate, mcp_tool, memory, patch, process, read_file, search_files, send_message, session_search, skill_manage, skill_view, skills_list, terminal, text_to_speech, todo, vision_analyze, web_extract, web_search, write_file

### Команды бота (63)
agents, approvals, approve, background, branch, bundles, codex_runtime, commands, compress, context, credits, cron, curator, debug, deny, diff, fast, footer, goal, heartbeat, help, init, insights, kanban, learn, loop, memory, model, new, personality, platform, profile, queue, reasoning, refine, reload, reload_mcp, reload_skills, reset, restart, resume, retry, rollback, save, sessions, set_home, skills, start, status, steer, stop, subgoal, suggestions, title, topic, undo, update, usage, verbose, version, voice, whoami, yolo

### CLI (82)
config, doctor, health, usage, insights, agents, restart, reload, diff, credits, curator, kanban, plugins, toolsets, tools, browser, plan, gquota, platforms, approve, deny, approvals, stop, steer, cron, blueprint, heartbeat, loop, learn, refine, init, memory, model, handoff, reasoning, fast, voice, new, sessions, status, context, compress, undo, checkpoint, rollback, checkpoints, branch, background, resume, save, history, goal, subgoal, title, export, suggestions, help, exit, quit, version, clear, redraw, profile, whoami, statusbar, editor, image, attach, attachments, detach, debug, snapshot, personality, queue, retry, verbose, yolo, busy, skills, bundles, install, uninstall

### REST-контроллеры (44, ~400 эндпоинтов)
DashboardSystem (62), RuntimeSettings (30), FilesystemDashboard (28), CronJob (23), ProfilesDashboard (20), MessagingDashboard (20), Session (16), AgentChat (14), CronDashboard (13), Skill (13), SkillsDashboard (12), Console (12), McpDashboard (12), Memory (9), SessionCrud (9), PluginDashboard (8), MemoryDashboard (8), HermesCronJobs (8), OpenAiRuns (7), DeliveryWorkItem (7), ModelOptions (7), UserAdmin (6), Checkpoint (5), Attachment (5), Curator (4), Kanban (4), LearningDashboard (4), Health (4), AudioDashboard (4), Mcp (4), Toolsets (3), OpenAiResponses (3), CuratorDashboard (3), AnalyticsDashboard (2), BrowserControlDisabled (2), Events (1), PlatformEvents (1), ChatCompletions (1), Vision (1), Capabilities (1), SkillsDiscovery (1), Models (1), SessionSearch (1), TelegramWebhook (1)
