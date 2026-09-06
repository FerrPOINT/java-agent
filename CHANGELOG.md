# Changelog

## Session 2026-09-06 — 0.1.234 (release-hardening: full audit fix-wave + parity + coverage gate 80%)

### Fixed — concurrency & lifecycle

- **M2**: `DefaultAgentRuntime` fallback/active-client state was shared mutable fields on a singleton bean — concurrent sessions overwrote each other's fallback chain. Now per-turn `TurnModelState` in a `ThreadLocal`, cleared in `finally`.
- **M3**: `interruptibleSleep` polled `Thread.interrupted()` which silently clears the interrupt flag — plain `Thread.sleep` now propagates interruption losslessly.
- **M11**: `ProcessTool.getRecentOutput` derived its slice from a separately tracked counter that could diverge from the ring buffer — now derived from the deque itself.
- **M27/M28/M29**: MCP lifecycle — reconnect path closes the stale client before replacing it; `refreshTools` remove-without-close leak fixed; `closeAll` gets a bounded drain (`awaitTermination` 10s) before pool replacement instead of abandoning in-flight tool calls.
- **M24**: `TelegramClient.lastCallConflict` global mutable flag raced across parallel API calls — `getUpdates` now rethrows 409 as a typed `TelegramApiException` and the polling loop keys off the exception, not the side-channel flag.
- **M26**: `handleCommand` ran unsynchronized against `handleTextOrMedia` — commands (`/new`, `/model`, `/checkpoint`) now take the same per-chat lock as message handling.
- **L12**: `BotLockManager.scopeHash` truncated `hashCode()` to 24 bits (colliding lock files for distinct bot tokens) — full SHA-256 now.

### Fixed — data & API hygiene

- **M16**: session DTO timestamps surfaced as `null` — entity `createdAt/updatedAt` are now snapshotted into domain metadata by `SessionEntityMapper` and read back by `DomainDtoMapper`.
- **M17**: session delete materialized the entire transcript just to delete it — bulk `deleteBySessionId`.
- **M18**: `undoTurns` loaded the whole transcript to pick turn indices — distinct-turn projection.
- **M19**: `getContext` loaded the transcript for counts — SQL aggregates (`countBy`, `sum(length)`, distinct tool names).
- **M7**: memory fact queries were unbounded — capped by `agent.memory.max-facts-per-query`.
- **M21**: think-block scrubbing now applies to the sync/fallback commentary path (streaming already scrubbed).
- **M23**: long-polling no longer reconnect-loops on unrecoverable auth failures (401/404 terminal by default).
- **M31**: streaming timeout abandoned the caller but kept consuming — late tokens/completions/errors after timeout are dropped (`abandoned` flag), timeout result surfaces deterministically.
- **M33**: `ErrorClassifier` matched HTTP status codes as substrings ("400" inside "14002") — standalone-token matching now.
- **M35**: `CredentialPool` stored raw provider error strings (could contain key fragments) — sanitized to classified reasons.
- **M36**: `McpOAuthManager` JSON string scanner dropped `\uXXXX` escapes to literal "uXXXX" — proper unicode decoding.
- **L3**: TTS/ImageGen artifacts in cache roots accumulate forever — `MediaArtifactCleanup` scheduled sweep (TTL `agent.media.artifact-ttl-hours`, default 24h).
- **CliStateApplier yolo bug** (found by new tests): rebuilding `ChatRequest` dropped `serviceTier/yoloMode/verboseMode/footerEnabled` — `/yolo` requests lost their bypass flag after CLI-state merge.

### Fixed — Hermes tool-schema parity

- `terminal`: advertises `notify_on_complete`/`watch_patterns` (previously only `notify`).
- `clarify`: primary surface is top-level `question`/`choices`/`multi_select` (required=`[question]`) with `questions` as the batch extra — matching `CLARIFY_SCHEMA`.
- `memory`: `old_text`/`new_text` documented as required for replace/remove operations.

### Tests & coverage

- Backend coverage **79.09% → 80.01%**; JaCoCo line gate raised **0.75 → 0.80**.
- ~30 new branch/regression test classes covering every fix above (6588 backend tests total, 0 failures).
- Delegation error paths, web-extract guards, git-review scopes, cron not-found branches, dashboard theme/checkpoint/stats branches, skills-hub helpers, context-compressor string bounds.

## Session 2026-09-05 — 0.1.233 (post-audit cleanup)

### Fixed

- **M32**: fallback-model token usage is now reported to the turn usage collector (`FallbackModelClient` usage sink wired from `DefaultAgentRuntime` and `AgentStreamingService`) — fallback completions were previously invisible to `/usage`, `/credits` and `usage_log`.
- **L9**: `OsvCheckService` now checks the HTTP status before parsing the OSV response — a 429/5xx JSON error body can no longer be silently read as a "clean" verdict (fail-open semantics preserved, but explicit).
- **Dead code revived**: `OsvCheckService` was never instantiated despite config plumbing (`agent.mcp.osv-check-enabled`) — now wired into `McpLifecycleManager` stdio launch; MCP stdio packages with OSV `MAL-*` malware advisories are refused (Hermes `osv_check.py` parity).
- **Test flake**: ported the lost 2026-09-01 audit suite stabilization (`maxParallelForks=1`, `forkEvery=750`, heap 4g) that was dropped in the PR#3 merge — fixes the `OpenAiRunsControllerTest` strict-stubs flake.

### Dependencies

- Dependabot fleet merged: lombok 1.18.48 (backend/bot/cli), springdoc 3.1.0, commons-compress 1.28.0, commons-lang3 3.20.0, groovy 5.1.1 (json/test), gradle-wrapper 9.7.1, slf4j 2.0.18.

### Tests

- backend 6487/0 (+8: OsvCheckServiceHttpResponseTest 3, McpLifecycleManagerOsvGateTest 2, FallbackModelClientUsageTest 3), telegram-bot 1716/0, cli 333/0.

## Session 5 — General Audit Fixup (2026-08-26, 0.1.140)

### Critical

- CI now runs telegram-bot and cli tests (previously only backend)
- JaCoCo coverage verification gate added (LINE ≥ 75%)
- ToolResultStorage: ArrayList → synchronizedList (race condition fix)
- StreamSession.floodFallbackBuffer: StringBuilder → StringBuffer (thread safety)
- SkillUtils.ENV_DETECT_CACHE: HashMap → ConcurrentHashMap (race condition fix)
- .version synced to 0.1.140 (was 0.1.4)
- prototype/ directory removed (542MB with stray .git)
- SessionCompressionHelper: LLM call extracted from @Transactional (pool starvation fix)

### High — Concurrency

- CronJobService: cancel old ScheduledFuture before put (leak fix)
- HeartbeatService: AtomicBoolean.compareAndSet (check-then-act fix)
- MemoryStore: synchronized → ReentrantLock (virtual thread pinning)
- McpLifecycleManager: synchronized → ReentrantLock (4 blocks)
- DefaultAgentRuntime: synchronized(this) → ReentrantLock
- BotMessageProcessor: lock() → tryLock(300s) during LLM streaming
- TypingManager: HTTP call moved outside synchronized block
- PairingService: readOnly=true removed (was breaking save())
- CdpClient: synchronized → ReentrantLock with tryLock(130s)
- Process deadlocks fixed in 5 files (CronJobService, SkillPreprocessor, ShellHookManager, CodingWorkspaceSnapshot, DefaultContextReferenceService)
- CronJobService: no_agent failures now set lastStatus=error
- TerminalTool: auto-checkpoint failure now warns in ToolResult
- ChromiumDownloader: InputStream leak on non-200 fixed (try-with-resources)

### High — Cleanup

- 8 dead Gradle deps removed (pebble, commons-lang3, commons-io, commons-imaging, langchain4j-ollama, resilience4j-circuitbreaker, resilience4j-spring6, flexmark from bot)
- TestRunner.class removed from git
- LOGGING_AUDIT_REPORT.md, review-findings.md → docs/audit/
- `__pycache__` added to .gitignore, git rm --cached
- docker-compose.yml duplicate deleted
- AgentLoopExecutor → TurnExecutor in docs
- 3 test files: @Mock DomainDtoMapper → Mappers.getMapper()
- 8 @SpringBootTest files: @Tag("slow") added

### Medium

- BackgroundJobEntity: @Data added (was only entity without it)
- StreamSession: volatile++ → AtomicInteger (floodStrikes, draftFailures)
- ShellHookManager: ArrayList in CHM → synchronizedList
- DefaultContextCompressor: future.cancel(true) on timeout
- EnvironmentProbe: InterruptedException catch + interrupt flag restore
- .env.example created with all key environment variables
- CliReplRunner: hardcoded version → build properties (springBoot.buildInfo)
- MessagePersistenceService: magic numbers → constants, role literals → Role enum
- SkillsHubService: HttpClient try-with-resources
- docs/TODO.md: consolidated to summary

### Low

- Markdown lint CI: continue-on-error removed
- Makefile: parity-dashboard, skill-update targets documented in help
- repomix.config.json, `__pycache__`/, *.pyc added to .gitignore
- jar.enabled = false (plain jar artifact elimination)
- Coverage gate set to 0.75 (to be raised to 0.80 after test coverage improvements)

## Session 4 — Architecture, Security, and Documentation Overhaul

### API Key Authentication

- Added `ApiKeyAuthFilter` for stateless API key authentication
- API key configured via `agent.security.api-key` property, validated on all non-public endpoints
- Health, actuator, webhook, and swagger-ui endpoints exempt from auth

### Controller Split (8 controllers)

- Split monolithic `AgentController` (~100 endpoints) into 8 focused controllers:
  - `AgentChatController` — chat, streaming, steer, stop, approvals, TTS, transcription
  - `SessionController` — session lifecycle, compression, undo, model switching, snapshots
  - `MemoryController` — memory CRUD, pending memory approval
  - `SkillController` — skill listing, reload, bundle management
  - `CheckpointController` — checkpoint create, list, diff, restore, delete
  - `RuntimeSettingsController` — config, reasoning, tools, goals, credits, codex runtime
  - `KanbanController` — todo/kanban board
  - `CuratorController` — curator status, run, pause, resume
