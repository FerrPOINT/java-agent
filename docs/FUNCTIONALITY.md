# Инвентарь функционала java-agent и контракты «как должно работать»

> Мастер-файл для полной ревизии: каждая строка = функция, её контракт и статус проверки.
> Проверка = (1) код соответствует контракту, (2) есть тест, (3) есть джавадок на кросс-пакетных контрактах.
> Статусы: ⬜ не проверено · ✅ проверено (тест+код) · 🔧 найдено расхождение → фикс.

Генезис: 2026-09-20. Источник инвентаря — код main (после 75f236ec): 36 тулзов, 44 REST-контроллера (~400 эндпоинтов, из них 140 публичных API + дашборды), 63 команды бота, 82 CLI-команды, 60+ сервисов.

---

## 1. Ядро агента (backend/core)

### FN-1.1 Агентный цикл (DefaultAgentRuntime + TurnExecutor)
Контракт: per-session ReentrantLock (concurrent turn protection); итерации LLM-вызовов с бюджетом итераций; tool-calls исполняются пакетами (ToolExecutionService, virtual threads); finish_reason LENGTH → stitched partial (Hermes сохраняет голову ответа); tool_calls с пустым массивом → re-prompt ≤3 подряд; dropped tool_call → nudge; sanitайзер удаляет осиротевшие tool-результаты; ThinkScrubber.reset() на каждой итерации LLM-вызова; дедуп tool_call id (uniquify) ДО любого потребителя.
Тесты: AgentStreamingService*, TurnExecutor*, ToolLoopGuardrail*, ThinkScrubber*.
Статус: ✅ (2026-09-20: uniquify wired, scrubber.reset, dropped-toolcall nudge — проверено код-аудитом + существующие тесты)

### FN-1.2 Стриминг SSE (AgentStreamingService, OpenAI-совместимый)
Контракт: безымянные `data:`-фреймы, error envelope, терминальный literal `data:[DONE]` (raw, не JSON-строка); события: token, tool_start/tool_end, clarify, review, done, error; rotation сессии — смена ссылки после prepareContext; интерактивный turn не висит 600с на Retry-After ≥60s от прокси (fail-fast RATE_LIMIT с биллинг-гайденсом).
Тесты: SSE формат, [DONE]帧, error-конверт.
Статус: ⬜

### FN-1.3 Контекст (DefaultContextEngine, compressor)
Контракт: preflight-оценка токенов измеряет ТЕКУЩИЙ список сообщений (не stale lastPromptTokens); trimToFit против эффективного contextLength из ModelMetadataService (не статических 16K); финальный инвариант USER-поворота в hard-tail fallback; компрессия с cooldown-лестницей 60/300/900s, failure-cooldown 600s; смена модели через /model → contextEngine.updateModel(effectiveModel) до prepareContext.
Тесты: ContextEngine*, Compressor*.
Статус: ⬜

### FN-1.4 Бюджет поворота (core/budget)
Контракт: iteration budget, tool execution записывается в бюджет; лимит итераций защищает от бесконечных tool-loop.
Статус: ⬜

### FN-1.5 Промпт (DefaultPromptBuilder, system_prompt parity)
Контракт: 3-tier cache структура как у Hermes; волатильный тир (Platform/Source/User/Model/Provider) обновляется на каждый запрос; русский язык ответов не ломается continuation-промптами.
Статус: ⬜

### FN-1.6 Reasoning echo (reasoning_content)
Контракт: agent.model.return-thinking + thinking-field-name — эхо reasoning_content для DeepSeek/Kimi/MiMo; полевые имена конфигурируемы.
Тесты: ReasoningEchoFamily*.
Статус: ⬜

### FN-1.7 Сессии/состояние (core/state, SessionController)
Контракт: fork/branch сессий; undo N turns; checkpoint/rollback (CheckpointManager wired); title service; session search (READ/SCROLL с @JsonAlias sessionId); prune с явным отказом на неподдерживаемые Hermes-фильтры (SessionPruneService).
Статус: ⬜

## 2. Инструменты (36, backend/tools)

Общий контракт: каждый Args-record с @JsonProperty("snake_case") обязан иметь @JsonAlias("camelCase"); ANSI-strip на terminal/process выводе; файловые тулзы идут через DefaultFileSafety (denylist: /etc/, /boot/, /usr/lib/systemd/, docker.sock); tool description = контракт для LLM.

### Файлы и код
- FN-2.1 read_file — постраничное чтение с номерами строк, авто-извлечение (ipynb/docx/pdf) — ⬜
- FN-2.2 write_file — полная замена, отказ без полного чтения изменённого файла, FileSafety — ⬜
- FN-2.3 patch — fuzzy find-replace (9 стратегий), эскалационная подсказка после 3 not-found подряд — ⬜
- FN-2.4 delete_file — FileSafety — ⬜
- FN-2.5 search_files — grep-режим (content) и glob-режим (files) — ⬜
- FN-2.6 terminal — персистентный cwd/env; foreground мгновенно завершается; background=true + process; запрет пайпов tail/head для exit_code; PTY; test-scope contract (generic/focused/broad) — ⬜
- FN-2.7 process — poll/wait/kill фоновых — ⬜
- FN-2.8 execute_code — персистентная session_kernel (V60), без локального fallback — ⬜

### Web/Media
- FN-2.9 web_search — SearXNG self-hosted, лимит результатов — ⬜
- FN-2.10 web_extract — markdown/PDF; honest 400 когда бекенд не сконфигурен — ⬜
- FN-2.11 vision_analyze — URL/data:/локальный путь, crop region — ⬜
- FN-2.12 image_generate — ImageGen provider — ⬜
- FN-2.13 text_to_speech — TTS провайдеры, MEDIA: — ⬜
- FN-2.14 browser_* (12 тулзов) — CDP-бэкенд, snapshot ref-ids, dialog/press/type/scroll/vision/console/cdp/get_images; provider router + extension backend; disabled-контроллер честно 503/400 — ⬜ (покрыты BrowserToolWrapperTest + BrowserServiceTest + live)

### Память/знания
- FN-2.15 memory — operations batch атомарно, лимит на ФИНАЛЬНЫЙ результат; nudge-интервал AGENT_MEMORY_NUDGE_INTERVAL — ⬜
- FN-2.16 skills_list / skill_view / skill_manage — dedup повторного просмотра (mtime+size fingerprint), сброс на компресии — ⬜
- FN-2.17 session_search — @JsonAlias, null-guard, READ/SCROLL — ⬜
- FN-2.18 todo — схема byte-identical Hermes TODO_SCHEMA, merge-семантика — ⬜
- FN-2.19 clarify — блокирующий, Telegram inline-keyboard, TTL 3600s, NO_TOOL_TIMEOUT, session-keyed stream bridge — ⬜

### Оркестрация
- FN-2.20 delegate_task — изолированные сабагенты, batch-коалесцинг завершений (claimNextBatch, one target per batch, batch fencing) — ⬜
- FN-2.21 cronjob — сжатый инструмент управления cron (action=list/add/...) — ⬜
- FN-2.22 send_message — platform:chat_id[:thread], гарантия доставки — ⬜
- FN-2.23 mcp_tool — вызов тулзов MCP-сервера, пагинация listTools с cursor, _meta форматирование — ⬜

## 3. Сервисы backend

- FN-3.1 HeartbeatService — session-scoped recurring, IDLE-only, real user message wins, ticks coalesce; /loop LOOP_COMPLETE семантика — ⬜
- FN-3.2 CronJobService (2495 строк!) — cron jobs, delivery poller, blueprint, suggestions (consent-first, latched dismiss) — ⬜
- FN-3.3 DelegatedTaskRunService + DelegateCompletionBatcher — WP-1 коалесцинг — ⬜
- FN-3.4 ProfileRuntimeRegistry + ProfileConfigWriter/EnvStore — WP-4 revision-трекинг рантайма, audited env writes — ⬜
- FN-3.5 McpConfigStore + McpOAuthFlowService — WP-3 конфиг-стор, single-flight schema cache, last-known-good, OAuth Code+PKCE AES-GCM — ⬜
- FN-3.6 OpenAiRunService + StateMachine + StalledRunMonitor — WP-6 durable Runs, restart-safe replay — ⬜
- FN-3.7 ConsoleTaskService + PtySessionService — WP-9 durable console, PTY ring buffer + cursor reconnect, 24h TTL — ⬜
- FN-3.8 AttachmentArtifactService — WP-11 дедуп хэшем, safe cache root, no blobs — ⬜
- FN-3.9 DeliveryLedgerSweeper + DeliveryWorkItemService + OutboundReceiptService — WP-1 sweep_recoverable parity, bounded attempts — ⬜
- FN-3.10 SkillHubInstaller — WP-5 staged install + rollback, capability toolsets — ⬜
- FN-3.11 CheckpointManager — wired (round 35), файловые чекпоинты — ⬜
- FN-3.12 UsageTracker + TurnUsageCollector + CreditsDto — /credits — ⬜
- FN-3.13 Curator (auto-curated kanban) — agent.curator.* — ⬜
- FN-3.14 RuntimeConfigService — /codex_runtime смена модели без рестарта — ⬜
- FN-3.15 GatewayConfigWriter + GatewayHomeChannelService — set_home — ⬜

## 4. Telegram-бот (63 команды)

Контракты: auth (allowed-user-ids/usernames, allow-by-default, FORBIDDEN чужим); streaming через StreamEditor с rate-limit; tool progress режимы hidden/compact/verbose; MarkdownV2-конвертер byte-identical Hermes _escape_mdv2 (буллеты как экранированный текст); fence-balancing при чанкинге; batch-дебаунсеры фото/текста; media cache 24h; GoalAutoContinueService; /model клавиатура; YOLO/approvals; voice mode.
- FN-4.1..FN-4.63 — по одной строке на команду (см. inventory в конце файла) — ⬜
- FN-4.64 StreamingOrchestrator.toUserFriendlyError — каждая refuse/error-ветка backend → lane + тест — ⬜

## 5. CLI (82 команды)

Контракт: JLine REPL, SSE-стриминг, autocomplete, деструктивные команды через подтверждение, /editor, /image-/attach- артефакты.
- FN-5.1..FN-5.82 — по строке на команду — ⬜

## 6. Безопасность

- FN-6.1 SsrfSafeHttpClient — блок private/local IP — ⬜
- FN-6.2 DefaultFileSafety — denylist префиксов + docker.sock; НЕ блокировать project .env запись — ⬜
- FN-6.3 DefaultUrlSafety — ⬜
- FN-6.4 DefaultRedactor — redact-secrets / redact-pii тумблеры — ⬜
- FN-6.5 ApprovalGate — fail-closed (timeout ≠ approve), requiresApproval ДО isPending — ⬜
- FN-6.6 Telegram auth — env-конфиг, webhook FORBIDDEN — ⬜

## 7. Инфраструктура

- FN-7.1 Flyway 30 миграций (V1–V62: profile registry, MCP store, OAuth, Runs, console tasks, attachments) — ⬜
- FN-7.2 Health/readiness — только db — ⬜
- FN-7.3 Dashboard-контроллеры (44 контроллера, ops ledger) — ⬜
- FN-7.4 E2E: 28 HTTP + 35 CLI сценариев — ⬜

---

## Прогресс ревизии

| Дата | Область | Итог |
|------|---------|------|
| 2026-09-20 | Инвентаризация | файл создан (36 тулзов, 63 бот-команды, 82 CLI, 44 контроллера) |
| 2026-09-20 | FN-2.6/2.14 JsonAlias | 🔧→✅ TerminalTool (notifyOnComplete/watchPatterns), BrowserDialogTool (promptText): camelCase-алиасы добавлены + TerminalToolArgAliasesTest, BrowserDialogToolArgAliasesTest |
| 2026-09-20 | FN-6.2 FileSafety | 🔧→✅ home-скоупинг write-denylist (Hermes build_write_denied_paths/#45947): проектные .env/auth.json/config.json записываемы; /etc,/boot,/usr/lib/systemd,docker.sock глобальны; read-block .env глобален; .ssh/.gnupg read-block под home; +5 parity-тестов; guard-home-paths config seam (78a70069) |
| 2026-09-20 | FN-2.19 clarify | ✅ предварительно: session-keyed bridge + NO_TOOL_TIMEOUT закоммичены (75f236ec); тесты ClarifyToolBlockingTest зелёные |
| 2026-09-20 | FN-4.x бот-команды | 🔧→✅ /loop /refine /approvals покрыты тестами (22 кейса); найден и починен баг: /loop stop шлёт POST /stop, которого нет у бекенда → 404 «No loop set.» при живом лупе (bc80a18f) |
| 2026-09-20 | Джавадоки сервисов | ✅ AgentRuntimeService, AgentStreamingService, CronJobService, DelegatedTaskRunService, OpenAiRunService получили class-level контракты; DOC001 513→508, ratchet чист |

(детальный inventory тулзов/команд/эндпоинтов — в конце файла)
