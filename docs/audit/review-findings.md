# Java-Agent Full Audit — Consolidated Findings

Branch: `review/full-audit`
Baseline: 6075 tests, 0 failures

## Severity Summary
- **CRITICAL: 5** (3 CORE + 1 PERSISTENCE + 1 TOOLS)
- **HIGH: 18** (7 CORE + 1 SECURITY + 2 TOOLS + 4 PERSISTENCE + 3 TELEGRAM-BOT + 3 CLIENT/MCP + 2 cross-cutting)
- **MEDIUM: 30** (9 CORE + 1 SECURITY + 4 TOOLS + 5 PERSISTENCE + 7 TELEGRAM-BOT + 8 CLIENT/MCP + 2 cross-cutting)
- **LOW: 8** (1 CORE + 1 SECURITY + 3 TOOLS + 2 PERSISTENCE + 1 CLIENT/MCP)

**Total: ~61 findings**

---

## CRITICAL

### C1. Race condition: ToolLoopGuardrail uses non-thread-safe HashMap [CORE]
**File:** `ToolLoopGuardrail.java:31-32`
`HashMap` mutated from parallel tool execution → corrupted state, lost updates, infinite loops.

### C2. Race condition: TurnState uses ArrayList/HashMap in parallel path [CORE]
**File:** `TurnState.java:30-32`
`ArrayList.add()` and `HashMap.merge()` from parallel tool execution → ConcurrentModificationException.

### C3. Memory leak: 7+ ConcurrentHashMaps for sessions never cleaned up [CORE]
**Files:** DefaultAgentRuntime, TurnStateManager, InterruptToken, DefaultContextEngine, PromptCacheTracker, ToolResultStorage, BackgroundReviewService
Session keys accumulate without limit. No eviction, no TTL, no cleanup hook.
**Status:** Fixed via SessionDeletedEvent → DefaultAgentRuntime.cleanupSession(). Gap found
in re-audit: `SessionCrudService.deleteSession()` (service path) deleted rows without
publishing the event, silently leaking the same state; publisher injected on
`fix/audit-regressions` with a regression test.

### C4. CronJobService: no @Transactional on multi-write methods [PERSISTENCE]
**File:** `CronJobService.java:49-52`
DB failure between save(job) and deleteById leaves orphaned job.

### C5. ExecuteCodeTool: reads stream AFTER waitFor → deadlock on large output [TOOLS]
**File:** `ExecuteCodeTool.java:51-58`
Process blocks on pipe buffer (64KB), waitFor times out, output lost.

---

## HIGH

### H1. Deadlock risk: ReentrantLock held during 5-min approval wait [CORE]
**File:** `DefaultAgentRuntime.java:167,725`
Lock held for 5 minutes blocks all operations for that session.

### H2. SpringToolRegistry: LinkedHashMap not thread-safe for registerDynamic [CORE]
**File:** `SpringToolRegistry.java:38`

### H3. ToolResultStorage: unbounded resultsByCallId, no cleanup [CORE]
**File:** `ToolResultStorage.java:40`

### H4. BackgroundReviewService: executor not shutdown (@PreDestroy missing) [CORE]
**File:** `BackgroundReviewService.java:68-72`

### H5. DatabaseMemoryProvider: @Transactional self-invocation [CORE]
**File:** `DatabaseMemoryProvider.java:181-183`

### H6. WriteApprovalGate: catch-all in @Transactional breaks atomicity [CORE]
**File:** `WriteApprovalGate.java:84-108`

### H7. ToolExecutionService: executor not shutdown (@PreDestroy missing) [CORE]
**File:** `ToolExecutionService.java:40`

### H8. SearXngSearchProvider: HttpClient follows redirects (SSRF bypass) [SECURITY]
**File:** `SearXngSearchProvider.java:38-40`
Default `HttpClient.Redirect.NORMAL` — redirects not checked by urlSafety.

### H9. ChromiumDownloader.unzip: Zip Slip vulnerability [TOOLS/SECURITY]
**File:** `ChromiumDownloader.java:70-91`
No validation that entryPath stays inside targetDir.

### H10. SharedObjectMapper: no JavaTimeModule — Instant fields fail JSON serialization [PERSISTENCE]
**File:** `SharedObjectMapper.java:12`
All DTOs with Instant fields will throw InvalidDefinitionException.

### H11. Controllers return JPA entities directly (violates layering) [PERSISTENCE]
**Files:** CronJobController, SkillController

### H12. N+1 query in SessionSearchTool [PERSISTENCE]
**File:** `SessionSearchTool.java:63-75`
findById called in loop for each match.
**Status:** Fixed in `SessionSearchService.discover()` — batch `findAllById` before the
demotion sort (was: 2 `findById` calls per comparator invocation over the full FTS result
set). The PR #1 integration merge lost the original dafb8c1 version of this fix; re-applied
on `fix/audit-regressions`.

### H13. Unbounded result sets — no pagination on list endpoints [PERSISTENCE]
**Files:** 9 repositories + 3 services
findAll() without Pageable on cron jobs, checkpoints, messages, memory, skills.

### H14. Telegram streaming: content > 4096 lost (streamingMaxChars=32768) [TELEGRAM-BOT]
**File:** `BotProperties.java:76`, `StreamEditor.java`
Content 4097-32767 chars → Telegram 400 → content lost.
**Status:** NOT lost in the PR #1 integration merge (initial line-diff audit flagged it, but
main re-implements the fix as an init()-time clamp of `streamingMaxChars` to 4096 — see
`StreamEditor.init()`; covered by `StreamEditorOversizedContentTest`). No action needed.

### H15. Telegram MessageSplitter: prefix (1/N) exceeds 4096 [TELEGRAM-BOT]
**File:** `MessageSplitter.java:50-56`

### H16. Telegram splitAndFormat: MarkdownV2 escaping expands beyond 4096 [TELEGRAM-BOT]
**File:** `MessageSplitter.java:94-104`

### H17. MCP: client/subprocess leak on init failure [CLIENT/MCP]
**File:** `McpLifecycleManager.java:130-142`

### H18. MCP: connect() blocks all servers when one hangs [CLIENT/MCP]
**File:** `McpLifecycleManager.java:126-143`

### H19. Streaming: double handler.onError() on timeout [CLIENT/MCP]
**File:** `LangChain4jModelClient.java:251-289`

### H20. Streaming: token usage not accounted [CLIENT/MCP]
**File:** `LangChain4jModelClient.java:235-249`

### H21. Retry: 16× retry on non-idempotent POST [CLIENT/MCP]
**File:** `LangChain4jModelClient.java:92`, `DefaultAgentRuntime.java:929`

### H22. API auth disabled by default in production [CROSS-CUTTING]
**File:** `application.yml:254`, `ApiKeyAuthFilter.java:55-58`
No docker-compose sets AGENT_SECURITY_API_KEY.

### H23. Dockerfile/CI: gradle-wrapper.jar excluded by .gitignore [CROSS-CUTTING]
**File:** `.gitignore` (`*.jar`), `Dockerfile:5`, `ci.yml:21-22`

---

## MEDIUM (summary — see subagent reports for details)

### CORE
- M1. DefaultContextCompressor: volatile++ non-atomic
- M2. DefaultAgentRuntime: fallbackManager/activeModelClient cross-session contamination
- M3. interruptibleSleep: Thread.interrupted() clears flag
- M4. ApprovalQueue: race between finally-remove and signalLatch
- M5. MemoryManager: ArrayList without sync on reads
- M6. ToolExecutionService: timeout doesn't cancel underlying task
- M7. DatabaseMemoryProvider: unbounded query — all facts in memory
- M8. DefaultAgentRuntime: recursive tryActivateFallback
- M9. DefaultContextEngine: estimateTokens returns stale value

### SECURITY
- M10. DefaultRedactor: credit card regex ReDoS risk

### TOOLS
- M11. ProcessTool: lineCount/AtomicInteger desync with deque
- M12. ShellHookManager: readAllBytes after waitFor blocks
- M13. PatchTool: isBlocked called before normalize — ../ bypass
- M14. DelegateTaskTool: future.cancel doesn't interrupt runTurn

### PERSISTENCE
- M15. TIMESTAMP vs TIMESTAMPTZ in V27/V12 migrations
- M16. DomainDtoMapper: createdAt/updatedAt always null
- M17. SessionCrudController: unbounded message load for delete
- M18. AgentRuntimeService: undoTurns load-all + filter
- M19. AgentRuntimeService: getContext load-all for metadata

### TELEGRAM-BOT
- M20. HTML parse-mode injection — LLM output not escaped
- M21. Think blocks visible in fallback path
- M22. ReconnectWatcher.resetBackoff never called
- M23. Infinite reconnect for 401 (non-recoverable)
- M24. 409 conflict detection race
- M25. BotLockManager TOCTOU: TRUNCATE before tryLock
- M26. handleCommand not serialized per-chat

### CLIENT/MCP
- M27. MCP: scheduleReconnect overwrites without closing old
- M28. MCP: refreshTools/McpToolHandler remove without close
- M29. MCP: closeAll without awaitTermination
- M30. MCP: pagination doesn't pass cursor
- M31. Streaming: unclosed stream on timeout
- M32. FallbackModelClient: token usage not accounted
- M33. ErrorClassifier: substring match HTTP codes
- M34. toJsonSchema: all properties as string
- M35. CredentialPool: raw error message in lastErrorReason
- M36. McpOAuthManager: naive JSON parser without unicode-escape

---

## LOW (summary)

- L1. InterruptToken: ThreadLocal leak in pooled virtual threads
- L2. ExecuteCodeTool: temp file leak (deleteOnExit only)
- L3. TtsTool/ImageGenTool: /tmp files without cleanup
- L4. ChromiumLauncher: process not destroyed on timeout
- L5. DelegateTaskTool: childExecutor not shutdown
- L6. MessageMapper: toDomain loses toolCalls list
- L7. SkillEntity: missing description field vs V2 migration
- L8. Dead test exclusions in build.gradle (8 valid tests hidden)
- L9. OsvCheckService: no HTTP status check
- L10. MCP: stale tools not deregistered on reconnect
- L11. McpLifecycleManager: TokenUsage.of() dead code
- L12. Telegram: @botname suffix not trimmed, case-sensitive lookup, lock hash collisions, PID recycling