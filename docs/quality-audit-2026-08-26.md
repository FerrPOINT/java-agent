# Генеральное ревью java-agent — 2026-08-26

## Сводка

| Severity | Кол-во |
|----------|--------|
| CRITICAL | 8 |
| HIGH | 22 |
| MEDIUM | 28 |
| LOW | 15 |
| **Итого** | **73** |

**Метрики проекта:**

- 584 main-класса, 567 test-файлов (backend 4790 + bot 1526 = 6316 тестов)
- Backend coverage: LINE 75.1%, BRANCH 61% (gate 80% НЕ проходит, gate НЕ настроен)
- 233 main-класса без прямых unit-тестов (79 с 0% покрытием)
- 10 файлов >1000 строк, 17 файлов 500-1000
- .version = 0.1.4, AGENTS.md = 0.1.66, CHANGELOG устарел на 2 недели (325 commits)

---

## CRITICAL

### C1. CI не запускает тесты telegram-bot и cli

**File:** `.github/workflows/ci.yml:39-44`
CI запускает `cd backend && ./gradlew test` — backend как standalone проект (свой settings.gradle). 1526 тестов telegram-bot и все CLI тесты НЕ запускаются в CI.
**Fix:** Добавить шаги для telegram-bot и cli через root gradlew.

### C2. Coverage gate не настроен

**File:** `backend/build.gradle:178-184`
AGENTS.md заявляет "coverage gate: LINE ≥ 80%", но `jacocoTestCoverageVerification` таск не объявлен. Реальное покрытие 75.1%.
**Fix:** Добавить `jacocoTestCoverageVerification` с `minimum = 0.80` для LINE.

### C3. ToolResultStorage: ArrayList value в ConcurrentHashMap без синхронизации

**File:** `backend/src/main/java/com/azhukov/agent/core/tool/ToolResultStorage.java`
ArrayList как value в ConcurrentHashMap, `.add()` без sync — parallel tool execution на virtual threads → race condition.
**Fix:** `ConcurrentHashMap.compute()` или `CopyOnWriteArrayList`.

### C4. StreamSession.floodFallbackBuffer: StringBuilder без синхронизации

**File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/streaming/StreamSession.java`
StringBuilder доступ из streaming callback + finalize thread без sync → corrupted data.
**Fix:** `StringBuffer` или synchronization.

### C5. SkillUtils.ENV_DETECT_CACHE — static mutable HashMap без синхронизации

**File:** `backend/src/main/java/com/azhukov/agent/core/skill/SkillUtils.java:353`
`private static final Map<String, Boolean> ENV_DETECT_CACHE = new HashMap<>()` — race condition.
**Fix:** `ConcurrentHashMap`.

### C6. .version = 0.1.4 vs AGENTS.md 0.1.66 — рассинхрон

**File:** `.version`
Makefile использует .version для сборки и тегов. AGENTS.md утверждает 0.1.66, .version = 0.1.4, parity-dashboard указывает 0.1.42. Три разные версии.
**Fix:** Синхронизировать .version с реальной версией.

### C7. prototype/ — 542MB с собственным .git

**File:** `prototype/hermes-agent/.git`
Git-ignored, но 421MB — .git от Hermes Python репо. Засоряет диск.
**Fix:** Удалить `prototype/`.

### C8. SessionCompressionHelper: @Transactional удерживает LLM вызов

**File:** `backend/src/main/java/com/azhukov/agent/service/SessionCompressionHelper.java:39-55`
`@Transactional` метод вызывает `conversationCompressor.compress()` → `modelClient.complete()`. LLM вызов (10-60s) удерживает JDBC connection → pool starvation.
**Fix:** Читать сообщения в короткой read-only транзакции, compression выполнять вне транзакции, сохранять в отдельной короткой транзакции.

---

## HIGH

### H1. CronJobService: ScheduledFuture без cancel перед put

**File:** `backend/src/main/java/com/azhukov/agent/service/CronJobService.java:361-368`
`scheduledTasks.put(id, future)` без `cancel()` → leak + двойное выполнение.

### H2. HeartbeatService: watchdogRunning check-then-act

**File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/core/HeartbeatService.java:273-285`
`if (!watchdogRunning) { watchdogRunning = true; }` — не атомарно.
**Fix:** `AtomicBoolean.compareAndSet(false, true)`.

### H3. MemoryStore: 6 synchronized методов + virtual threads pinning

**File:** `backend/src/main/java/com/azhukov/agent/core/memory/MemoryStore.java:77-254`
**Fix:** `ReentrantLock` или `StampedLock`.

### H4. McpLifecycleManager: synchronized(clients) × 4 блока

**File:** `backend/src/main/java/com/azhukov/agent/client/mcp/McpLifecycleManager.java:136,149,411,523`
**Fix:** `ConcurrentHashMap` + `compute()`.

### H5. DefaultAgentRuntime: synchronized(this) + virtual threads

**File:** `backend/src/main/java/com/azhukov/agent/core/agent/DefaultAgentRuntime.java:122`

### H6. BotMessageProcessor: ReentrantLock.lock() без tryLock во время LLM streaming

**File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/core/BotMessageProcessor.java`
Держит lock минуты во время LLM streaming.
**Fix:** `tryLock()` с timeout или не держать lock во время I/O.

### H7. TypingManager.resumeTyping: synchronized + HTTP call к Telegram API

**File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/typing/TypingManager.java`
synchronized блок содержит HTTP вызов к Telegram API → pinning + bottleneck.

### H8. PairingService.validateCode: @Transactional(readOnly=true) + repository.save()

**File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/auth/PairingService.java`
`readOnly=true` + `save()` — write молча ломается при прямом вызове.

### H9. CdpClient.connect(): synchronized + HTTP 120s timeout

**File:** `backend/src/main/java/com/azhukov/agent/tools/browser/CdpClient.java`
synchronized блок с HTTP timeout 120s → virtual thread pinned на 2 минуты.

### H10. Process deadlocks: stdout/stderr не drained параллельно (4 файла)

**Files:**

- `CronJobService.java:711-765` — stdout reader → EOF → stderr (sequential)
- `SkillPreprocessor.java:183-192` — waitFor → readAllBytes (deadlock при полном pipe)
- `ShellHookManager.java:480-490` — waitFor → readAllBytes
- `CodingWorkspaceSnapshot.java:316-326` — waitFor → readAllBytes
- `DefaultContextReferenceService.java:323-326` — sequential readAllBytes
**Fix:** `redirectErrorStream(true)` или concurrent gobbler threads до `waitFor()`.

### H11. CronJobService: no_agent execution failures silent-swallowed

**File:** `backend/src/main/java/com/azhukov/agent/service/CronJobService.java:481-489,573-587`
`executeNoAgentJob()` ловит все исключения, ненулевой exit code только логируется, job помечается success.
**Fix:** Возвращать ExecutionResult, выставлять `lastStatus=error`.

### H12. TerminalTool: auto-checkpoint failure silent-swallowed

**File:** `backend/src/main/java/com/azhukov/agent/tools/terminal/TerminalTool.java:80-86`
Checkpoint failure логируется warn, опасная команда всё равно выполняется, caller не знает.
**Fix:** Возвращать ToolResult.fail или предупреждение.

### H13. ChromiumDownloader: InputStream leak на non-200

**File:** `backend/src/main/java/com/azhukov/agent/tools/browser/ChromiumDownloader.java:47-52`
`response.body()` не закрывается при non-200 (throw до try-with-resources).
**Fix:** `try (InputStream in = response.body())` до проверки status.

### H14. 8 мёртвых Gradle dependencies

**Files:** `backend/build.gradle`, `telegram-bot/build.gradle`

- `pebble:4.1.2` — 0 импортов, 0 шаблонов
- `commons-lang3:3.17.0` — 0 импортов
- `commons-io:2.18.0` — 0 импортов (все 3 модуля)
- `commons-imaging:1.0.0-alpha5` — 0 импортов
- `langchain4j-ollama:1.18.0` — 0 импортов
- `resilience4j-circuitbreaker:2.4.0` — 0 ссылок
- `resilience4j-spring6:2.4.0` — 0 ссылок
- `flexmark:0.64.8` — только в telegram-bot
**Fix:** Удалить или подтвердить использование.

### H15. TestRunner.class — stray compiled class в корне (git-tracked)

**File:** `TestRunner.class`
**Fix:** `git rm TestRunner.class`.

### H16. LOGGING_AUDIT_REPORT.md, review-findings.md — audit-отчёты в корне

**Files:** `LOGGING_AUDIT_REPORT.md`, `review-findings.md`
Git-tracked временные отчёты не в docs/.
**Fix:** Переместить в `docs/audit/` или удалить.

### H17. **pycache** не в .gitignore

**File:** `e2e/__pycache__/run_e2e.cpython-311.pyc`
**Fix:** Добавить `__pycache__/` и `*.pyc` в .gitignore, `git rm --cached`.

### H18. docker-compose.yml — дубликат docker-compose.prod.yml

**Fix:** Удалить, оставить только prod.yml + local.yml.

### H19. AGENTS.md/CHANGELOG ссылаются на `AgentLoopExecutor` — класс не существует

**File:** `AGENTS.md`, `CHANGELOG.md`, `docs/architecture/adr/ADR-008`
Переименован в `TurnExecutor`, документация не обновлена.

### H20. 3 файла мокают DomainDtoMapper (нарушение AGENTS.md)

**Files:**

- `AgentControllerTest.java:62`
- `AgentControllerPhase2Test.java:58`
- `AgentControllerBranchCoverageTest.java:85`
AGENTS.md: "Мапперы: `Mappers.getMapper(X.class)` (не mock)".
**Fix:** Заменить `@Mock` на `Mappers.getMapper(DomainDtoMapper.class)`.

### H21. 8 @SpringBootTest без @Tag("slow") — запускаются в unit suite

**Files:**

- `JavaAgentApplicationTests.java`, `AgentControllerStreamingTest.java`
- `AgentControllerStreamingLazyInitTest.java`, `ToolExecutionServiceRetryTest.java`
- `AgentConfigNoopProfileTest.java`, `SecurityConfigTest.java`
- `LangChain4jModelClientLiveTest.java`, `TelegramBotApiClientLiveTest.java`
**Fix:** Добавить `@Tag("slow")`.

### H22. Layering violations: controllers работают с entities напрямую

**Files:**

- `SkillController.java:85` — возвращает `List<SkillAuditLogEntity>`
- `SessionCrudController.java:145,221,284` — загружает entities из repositories
- `CheckpointController.java:80` — ручной entity→DTO mapping в controller
**Fix:** Вынести в service + MapStruct mapper.

---

## MEDIUM

### M1-M10. God classes (10 файлов >1000 LOC)

| # | File | LOC | Рекомендация |
|---|------|-----|--------------|
| M1 | StreamEditor.java | 1824 | → StreamTransport, StreamRateLimiter, DraftStreamEditor, ThinkTagFilter |
| M2 | DefaultAgentRuntime.java | 1559 | → SessionTurnLockManager, RuntimeModelCaller, MemorySyncCoordinator (27 deps!) |
| M3 | BackendClient.java (CLI) | 1559 | → SessionApiClient, AgentApiClient, MemoryApiClient, SkillApiClient |
| M4 | AgentStreamingService.java | 1515 | → StreamingTurnPersistence, StreamingRetryCoordinator, StreamEventPublisher |
| M5 | DefaultPromptBuilder.java | 1484 | → SkillPromptSectionBuilder, MemoryPromptSectionBuilder, SystemPromptCache |
| M6 | DefaultContextCompressor.java | 1304 | → CompressionLockManager, SummaryGenerator, SessionRotationService |
| M7 | TurnExecutor.java | 1181 | → ModelResponseProcessor, ToolBatchCoordinator, InterruptedTurnHandler |
| M8 | CuratorService.java | 1180 | → CuratorAuditService, SkillReviewService, SkillBackupCoordinator |
| M9 | CronJobService.java | 1025 | → CronScheduler, CronExecutor, CronDeliveryService |
| M10 | TelegramClient.java | 1020 | → TelegramMessageApi, TelegramMediaApi, TelegramRateLimiter |

### M11. Множественные конструкторы (нарушение AGENTS.md) — 5 критичных

- `DefaultPromptBuilder.java:382` — 7 конструкторов (9-аргументный Spring)
- `LangChain4jModelClient.java:53` — 6 конструкторов
- `MemoryStore.java:37` — 5 конструкторов
- `TelegramLongPollingService.java:47` — конструктор создаёт executor
- `TelegramClient.java:58` — конструктор создаёт ScheduledExecutorService

### M12. Long parameter lists (>5 params)

- `TurnExecutor.java:99` — 12 constructor params
- `DefaultPromptBuilder.java:412` — 9 params
- `Session.java:30` — 7 record components
- `CronJobService.java:160,232` — 6 params each
- `ToolExecutionService.java:65` — 6 method params

### M13. Deep nesting (>4 levels)

- `MessageApiClient.java:237-249` — 14 уровней nesting
- `AgentStreamingService.java:471,495,616` — 10-11 уровней
- `SkillsHubService.java:202` — 10 уровней

### M14. BackgroundJobEntity: единственная @Entity без @Data

**File:** `backend/src/main/java/com/azhukov/agent/persistence/entity/BackgroundJobEntity.java:11`
Ручные getters/setters вместо Lombok. 20 других entities имеют @Data.

### M15. CheckpointManager: @Transactional + filesystem I/O

**File:** `backend/src/main/java/com/azhukov/agent/service/CheckpointManager.java:130-214`
`@Transactional` удерживает connection при recursive filesystem scan, hashing, чтении файлов.
**Fix:** Filesystem сборку вне транзакции, persistence в короткой транзакции.

### M16. DefaultContextCompressor: async LLM task без отмены при timeout

**File:** `backend/src/main/java/com/azhukov/agent/core/context/DefaultContextCompressor.java:1187-1199`
`CompletableFuture.supplyAsync()` + `future.get(timeout)` — после timeout task продолжается.
**Fix:** `future.cancel(true)` при timeout, bounded executor.

### M17. StreamSession: volatile++ non-atomic (floodStrikes, draftFailures)

**File:** `telegram-bot/src/main/java/com/azhukov/agent/bot/streaming/StreamSession.java`
`volatile int floodStrikes++` и `volatile int draftFailures++` — не атомарны.
**Fix:** `AtomicInteger`.

### M18. EventHookRegistry/ShellHookManager: ArrayList values в ConcurrentHashMap

**Files:** `EventHookRegistry.java`, `ShellHookManager.java`
`ConcurrentHashMap<String, ArrayList<...>>` — `.add()` без sync.

### M19. No .env.example

Нет шаблона env vars для новых разработчиков.

### M20. 34 "legacy" маркера в коде

`DatabaseSkillManager.findLegacyMdFiles()`, `SkillBundleService` legacy compat, `DefaultToolGuardrails` legacy overloads, `ChatResponse` legacy constructor, `BusySessionHandler` dual busyMode/busyInputMode, 3 DTOs с legacy JSON shape.

### M21. Truncate duplication: 8 реализаций

`McpToolDefinitionScanner.java:243`, `ToolArgumentInjectionScanner.java:141`, `McpResponseScanner.java:196`, `ToolFingerprintStore.java:147`, `DelegateTaskTool.java:854`, `ShellHookManager.java:736`, `SessionSearchService.java:471`, `ToolCallArgumentRepair.java:187`
**Fix:** `TextTruncator` utility.

### M22. SkillsSyncService: @PostConstruct startup bean, 0 программных ссылок

**File:** `backend/src/main/java/com/azhukov/agent/core/skill/SkillsSyncService.java`

### M23. ApprovalGate: @Component без injection (superseded by WriteApprovalGate?)

**File:** `backend/src/main/java/com/azhukov/agent/core/agent/ApprovalGate.java`

### M24. ProfileProperties: getProfile() не вызывается нигде

**File:** `agent.profile.name`, `agent.profile.base-dir` — dead config.

### M25. CHANGELOG: 325 commits без записей, устаревшие цифры

Описывает V1-V23 миграции (актуально V32), 8 контроллеров (актуально 19), "23 Flyway миграции" (актуально 30).

### M26. CLI version hardcoded as "v0.0.1-SNAPSHOT"

**File:** `cli/src/main/java/com/azhukov/agent/cli/CliReplRunner.java:93`

### M27. 85 файлов с verify() без clearInvocations()

Только 1 файл из 85 использует `clearInvocations()` — риск false positives.

### M28. 37 тестов "does not throw" без содержательных assertions

Основные: `TelegramLongPollingServiceNpeTest` (4), `LangChain4jModelClientNullContentTest` (3), `SkillBundleServiceBranchTest` (2).

---

## LOW

### L1. Markdown lint CI — continue-on-error: true

**File:** `.github/workflows/ci.yml:108`

### L2. Makefile: 2 цели не задокументированы в help

`parity-dashboard`, `skill-update`

### L3. docs/TODO.md: 8 пунктов закрыты, но простыня исторических записей

**Fix:** Оставить сводку, детали в CHANGELOG.

### L4. 4 пары файлов в docs/ с одинаковыми номерами (07, 22, 28, 29)

### L5. 20 @SuppressWarnings("unchecked") — type safety подавлена

### L6. JAR plain артефакты: `*-plain.jar` в build/

**Fix:** `jar.enabled = false` в build.gradle.

### L7. Dockerfile.slim — единственная ссылка в CI smoke test

### L8. repomix.config.json в корне

**Fix:** Добавить в .gitignore.

### L9. Magic numbers: 200/197, 120, 117, 80, 57 без констант

`MessagePersistenceService.java:109`, `SkillsHubService.java:104`, `SkillSecurityScanner.java:299`, `SkillCommandService.java:75`, `SkillUtils.java:602`

### L10. Manual role literals "user"/"assistant"/"tool" повторены

`MessagePersistenceService.java:40,46,54,60,66` + API mappers/controllers.
**Fix:** `Role` enum + centralized mapping.

### L11. CuratorService: 3 конструктора + `new ObjectMapper()` в каждом

### L12. JPA entities с convenience constructors

`CronExecutionLogEntity.java:53`, `SkillAuditLogEntity.java:49` — static factory лучше.

### L13. SkillsHubService + ContextReferenceExpander: локальные HttpClient не закрываются

Java 25 `HttpClient` implements `AutoCloseable`, но `HttpClient.newHttpClient()` без close.

### L14. EnvironmentProbe: broad catch(Exception) поглощает InterruptedException

**File:** `backend/src/main/java/com/azhukov/agent/core/prompt/EnvironmentProbe.java:111-145`
Не восстанавливает interrupt flag.

### L15. Три разные версии в разных документах

AGENTS.md: 0.1.66, README: ~2905 тестов, .version: 0.1.4, parity-dashboard: 0.1.42, actual tests: 6767.

---

## Приложение A: Пакеты с низким покрытием

| Пакет | LINE | BRANCH |
|-------|------|--------|
| service.imagegen | 0% | 0% |
| tools.vision | 0% | 0% |
| persistence.repository | 0% | n/a |
| core.agent | 47% | 36% |
| persistence.entity | 41% | 20% |
| tools.delegate | 53% | 34% |
| tools.browser | 55% | 41% |
| api | 67% | 45% |
| tools.cron | 67% | 57% |
| gateway | 73% | 58% |

## Приложение B: HIGH-priority классы без тестов (26)

SessionController, AgentChatController, MemoryController, KanbanController, SessionCrudController, RuntimeSettingsController, SkillController, McpController, OpenAiImageGenProvider, VisionAnalyzeTool, TelegramBotApiClient, ShellHookManager, SkillManager, FallbackController, EmptyResponseGuard, MemoryNudgeManager, ContextReferenceService, FileSafetyValidator, CommandApprovalManager, ToolGuardrails, TranscriptionService, BotLifecycleManager, CommandHandler, WebhookController, BotHealthController, InputHistoryManager

## Приложение C: Файлы сабагентов

- Trash audit: `/opt/dev/java-agent/TRASH_AUDIT_REPORT.md`
- Concurrency audit: `/opt/dev/concurrency-audit-report.md`
- Documentation audit: `docs/DOCUMENTATION_AUDIT_REPORT.md`
- Этот отчёт: `docs/quality-audit-2026-08-26.md`

---

## Рекомендуемый порядок действий

1. **CI fix** (C1) — добавить telegram-bot + cli тесты в CI
2. **Coverage gate** (C2) — настроить jacocoTestCoverageVerification
3. **Race conditions** (C3, C4, C5, H1, H2) — ToolResultStorage, StreamSession, SkillUtils, CronJobService, HeartbeatService
4. **@Transactional + LLM** (C8, M15) — SessionCompressionHelper, CheckpointManager
5. **Process deadlocks** (H10) — 5 файлов с sequential pipe reads
6. **Удалить мусор** (C7, H15, H16, H17, H18) — prototype, TestRunner.class, stray .md, **pycache**, docker-compose dup
7. **Мёртвые deps** (H14) — 8 неиспользуемых зависимостей
8. **Virtual thread pinning** (H3-H9) — заменить synchronized на locks
9. **Missing tests** (H20-H22) — mock mapper violations, @SpringBootTest без @Tag, layering violations
10. **Version sync** (C6, M25, L15) — .version, CHANGELOG, AGENTS.md
11. **God classes** (M1-M10) — постепенное разбиение
12. **Конструкторы** (M11) — множественные конструкторы → @RequiredArgsConstructor
