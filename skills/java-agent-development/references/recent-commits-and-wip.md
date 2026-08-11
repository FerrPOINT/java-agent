# Recent Commits and Work-in-Progress — java-agent

> Session: 2026-08-11. Captures the state of the working tree and recent history for context on future sessions.

---

## Uncommitted Changes (WIP)

### Modified files (not staged)
- `CheckpointEntity.java` — +3 lines
- `CheckpointManager.java` — +2 lines
- `CronJobService.java` — +47 lines
- `CheckpointJsonSerializationTest.java` — +38 / −2 lines
- `CronJobServiceTest.java` — +82 lines

### Untracked files
- `V20__todos_nullable_session_id.sql` — Flyway migration making `session_id` nullable in todos table
- `FullApiE2ETest.java` — new end-to-end API test class

**Interpretation:** A partial feature is in progress around cron jobs and checkpoints (likely session/cronjob checkpointing), plus a new Flyway migration for nullable `session_id` in todos, and a new E2E test.

---

## Recent Commit History (newest first)

| Commit | Summary |
|--------|---------|
| `cf98472` | fix: UndoRequest.turns changed from int to Integer with effectiveTurns() default |
| `3e89f3b` | fix: 5 E2E bugs found via real API testing with kimi-k2.6 |
| `2f22380` | fix: reasoningEffort numeric→string mapping for Ollama Cloud compatibility |
| `c52c31d` | feat: full compression session rotation |
| `02a2e53` | test: branch coverage push round 2 — +594 new tests across 20 test files |
| `cfe1800` | feat: curator forked agent loop with iterative tool-use |
| `bee3aeb` | fix: MessageEntity content/toolCallArguments/toolCallId use TEXT columnDefinition — fixes H2 VARCHAR(255) overflow on system prompt persistence |
| `6824d7b` | chore: clean upstream traces from 47 source files — comments and javadoc neutralized |
| `a9045a0` | test: branch coverage push — +515 new tests across 22 test files |
| `8a41479` | feat: architecture documentation + Docker prod compose + enhanced E2E smoke |
| `d6a605b` | feat: large gap fixes from Hermes audit |
| `b9d757c` | feat: medium gap fixes from Hermes audit |
| `cc8997e` | feat: 7 quick-win gap fixes from Hermes audit |
| `c8e7a22` | feat: security hardening, streaming interrupt, checkpoint restore, session search FTS, terminal guardrails, MCP OAuth, delegate task, context compressor, skill security scanner, bot approval store |
| `cf4275f` | fix: McpServerAutoConfiguration uses @ConditionalOnProperty — bean only created when agent.mcp.server.enabled=true AND transport=sse, prevents null servlet crash |

---

## Notable Patterns from History

1. **Periodic large test-coverage pushes** are a project norm (+500–600 tests across ~20 files per push).
2. **Real LLM E2E testing** (`kimi-k2.6`) is used to find integration bugs that mocks miss.
3. **H2/PostgreSQL drift** is a recurring source of bugs; TEXT columnDefinitions are required for large fields.
4. **Configuration safety** uses `@ConditionalOnProperty` with multiple required properties to prevent null-bean crashes.
