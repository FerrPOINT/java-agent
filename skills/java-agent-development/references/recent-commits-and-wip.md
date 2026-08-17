# Recent Commits and Work-in-Progress — java-agent

> Session: 2026-08-17. Captures the state of the working tree and recent history for context on future sessions.

---

## Current State (as of commit d86c593, 2026-08-17)

- **209 commits** total, latest: `d86c593` — chore: commit bundled skills
- **490 Java source files**, **515 test files**, **6386 tests**, 0 failures
- **26 Flyway migrations** (V1–V26)
- **36 tools** implemented (browser_*, web_*, terminal, read_file, write_file, patch, search_files, memory, skill_*, todo, cronjob, delegate_task, clarify, vision_analyze, image_generate, text_to_speech, send_message, session_search, process, execute_code, delete_file, mcp_tool)
- **56 bot commands** (+ 10 aliases) in telegram-bot
- **92 CLI slash commands** in cli module
- **114 REST endpoints** in backend
- **Coverage: 85.3%** (69221/81166 lines)
- **Hermes parity: 94%** (101/107 features), 6 consciously absent (P3 platform tools)
- **BUILD SUCCESSFUL**, 0 test failures

### Key classes added since last update:
- `SteerBuffer` — mid-turn message injection buffer
- `CommentaryCallback` — intermediate tool execution messages
- `MediaDeliveryService` — automatic file delivery (images, video, audio, documents)
- `BusySessionHandler` — busy-ack + queue/steer/interrupt modes
- `MidTurnPersistenceService` — persistence of mid-turn state
- `ImageShrinker` — image compression for vision tools
- `SkillsSyncService` — skill synchronization between filesystem and DB
- `FallbackManager` — LLM model fallback chain
- `ToolCallValidator` — tool call validation and security
- `SessionLineageService` — session branching/lineage tracking

---

## Recent Commit History (newest first)

| Commit | Summary |
|--------|---------|
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

## Notable Patterns from History

1. **Periodic large test-coverage pushes** are a project norm (+500–600 tests across ~20 files per push).
2. **Real LLM E2E testing** (`kimi-k2.6`) is used to find integration bugs that mocks miss.
3. **H2/PostgreSQL drift** is a recurring source of bugs; TEXT columnDefinitions are required for large fields.
4. **Configuration safety** uses `@ConditionalOnProperty` with multiple required properties to prevent null-bean crashes.
5. **Hermes parity audits** drive feature completeness — 94% parity achieved (101/107 features), with 6 P3 platform tools consciously deferred.
6. **Memory + self-improvement** system actively audits and fixes issues (39 issues in latest pass).