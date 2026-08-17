# Recent Commits and Work-in-Progress — java-agent

> Session: 2026-08-17. Captures the state of the working tree and recent history for context on future sessions.

---

## Current State (as of commit e7197a1, 2026-08-17)

- **213 commits** total, latest: `e7197a1` — fix: all 32 audit findings — Phase 1-6
- **494 Java source files**, **515 test files**, **6393 tests**, 0 failures
- **26 Flyway migrations** (V1–V26)
- **36 tools** implemented (browser_*, web_*, terminal с PTY mode, read_file, write_file, patch, search_files, memory, skill_*, todo, cronjob, delegate_task, clarify, vision_analyze, image_generate, text_to_speech, send_message, session_search, process, execute_code, delete_file, mcp_tool)
- **56 bot commands** (+ 10 aliases) in telegram-bot
- **92 CLI slash commands** in cli module
- **132 REST endpoints** in backend
- **Coverage: 85.3%** (69221/81166 lines)
- **Hermes parity: 94%** (101/107 features), 6 consciously absent (P3 platform tools)
- **BUILD SUCCESSFUL**, 0 test failures

### Key classes added/modified in audit fix (commit e7197a1):

**Terminal & Process:**
- `TerminalTool` — added `pty` (bool) and `workdir` (string) params, PTY mode via `script -qec`, per-command working directory, concurrent output reader thread, `notifyPostExecution` hook call
- `ProcessTool` — `ConcurrentLinkedDeque` for output buffer, PTY spawn support, `notifyOnExit` callback for background processes, `watchPatterns` for output pattern matching

**Browser (12 tools):**
- `BrowserTypeTool` — XSS fix via `JSON.stringify()` for selector+text, `clear` param now works
- `BrowserPressTool` — injection fix via `JSON.stringify()` for key param
- `BrowserDialogTool` — rewritten to use CDP `Page.handleJavaScriptDialog` (was broken, used nonexistent `window.__agent_dialog`)
- `BrowserSnapshotTool` — rewritten to use CDP `Accessibility.getFullAXTree` (was hardcoded JS)
- `BrowserNavigateTool` — `waitSeconds` param now passed through to service
- `BrowserScrollTool` — bounds validation for x/y scroll values
- `BrowserService` — shared `ObjectMapper` instance (was allocating per call), reconnect support
- `CdpClient` — `reconnect()` method, waitForEvent leak fix

**Delegation:**
- `DelegateTaskTool` — blocks `core` and `code` toolsets (was missing), subagent interrupt/pause support, per-task timeout override in batch mode

**Agent Runtime:**
- `DefaultAgentRuntime` — per-session `ReentrantLock` (was no concurrent turn protection), `shutdownNow()` for memory sync, `countPriorUserMessages` via messageRepository
- `ContextEngine` — `countPriorUserMessages` method (replaces expensive full context preparation)

**Context:**
- `DefaultContextCompressor` — image placeholder in compression (was text-only), sessionRepository made final
- `DefaultContextEngine` — delegated user message counting to `ContextEngine.countPriorUserMessages`

**MCP:**
- `McpLifecycleManager` — schema change detection (compares full inputSchema JSON, not just names), health monitoring, atomic McpServerState updates

**Memory & Skills:**
- `MemoryProvider` — provenance `Map` parameter in `store()`, `replace()`, `remove()` signatures
- `MemoryTool` — passes provenance to provider
- `SkillManageTool` — `absorbed_into` in update action, `SkillManageArgs` converted to record
- `SkillViewTool` — configurable skill directory (was hardcoded "skills")
- `DatabaseSkillManager` — filesystem search + frontmatter name match strategies implemented

**Prompt & Config:**
- `DefaultPromptBuilder` — parent directory walk for context files (AGENTS.md, CLAUDE.md, .cursorrules), configurable SOUL.md path via `AgentProperties`
- `AgentProperties` — `soulMdPath` config property

---

## Recent Commit History (newest first)

| Commit | Summary |
|--------|---------|
| `e7197a1` | fix: all 32 audit findings — Phase 1-6 (CRITICAL+HIGH+MEDIUM+LOW) |
| `94e8503` | docs: deep code quality audit — 32 findings (1 CRITICAL, 5 HIGH, 16 MEDIUM, 10 LOW) |
| `32df345` | feat: terminal PTY mode (pty=true) via script command |
| `0391f59` | docs: update all documentation + deep Hermes parity audit (165 features, 22 gaps found) |
| `d86c593` | chore: commit bundled skills (arxiv, plan, simplify-code, systematic-debugging, test-driven-development) |
| `572be53` | Memory + self-improvement audit: 39 issues found and fixed (5 CRITICAL, 12 HIGH, 13 MEDIUM, 9 LOW) |
| `e4eceec` | Hermes parity: S1-S5 features + P1/P2 fixes + quality audit (45 issues fixed) |
| `914bf1a` | Fix iteration budget + context 0% display |
| `29b96b8` | Fix all 31 remaining audit issues + write tests for each |
| `8c3e3cf` | Align Telegram output with Hermes: 12 behavior fixes |
| `3861923` | Match Hermes Telegram output + fix remaining audit bugs |
| `0bf3f1e` | Fix 6 CRITICAL + 10 HIGH bugs from thorough audit |
| `35628ac` | Fix text-cannot-be-null + SSE error handling + 34 logging fixes |
| `abe4735` | Fix health indicators (UP when not configured), SSE LazyInitializationException, CDP URL validation |
| `827ee98` | Fix slow tests + write 8 new test suites for new components |
| `5a5f981` | Fix session history: bot now captures and reuses backend session ID |
| `8d5939a` | Retry hardening: fix 4 HIGH + 6 MEDIUM retry issues |
| `6847f43` | Fix LLM retry, 409 conflict handling, and disable backend long-polling |
| `68850c5` | Enterprise hardening: 27 fixes across architecture, security, DB, ops, docs |

---

## Code Quality Audit Status

All 32 findings from the deep code-level quality audit (`docs/31-code-quality-audit.md`) have been resolved:

| Severity | Count | Status |
|----------|-------|--------|
| CRITICAL | 1 | ✅ Fixed (BrowserTypeTool XSS — JSON.stringify) |
| HIGH | 5 | ✅ All fixed (workdir, BrowserPress injection, snapshot AXTree, delegate blocked toolsets, per-session lock) |
| MEDIUM | 16 | ✅ All fixed (see audit doc for details) |
| LOW | 10 | ✅ All fixed (see audit doc for details) |

37 files changed, +722/-175 lines in commit `e7197a1`.

---

## Notable Patterns from History

1. **Periodic large test-coverage pushes** are a project norm (+500–600 tests across ~20 files per push).
2. **Real LLM E2E testing** (`kimi-k2.6`) is used to find integration bugs that mocks miss.
3. **H2/PostgreSQL drift** is a recurring source of bugs; TEXT columnDefinitions are required for large fields.
4. **Configuration safety** uses `@ConditionalOnProperty` with multiple required properties to prevent null-bean crashes.
5. **Hermes parity audits** drive feature completeness — 94% parity achieved (101/107 features), with 6 P3 platform tools consciously deferred.
6. **Memory + self-improvement** system actively audits and fixes issues (39 issues in latest pass).
7. **Deep code quality audits** compare implementation against Hermes reference — 32 findings identified and all fixed in one commit.