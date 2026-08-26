# Changelog

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
- __pycache__ added to .gitignore, git rm --cached
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
- repomix.config.json, __pycache__/, *.pyc added to .gitignore
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