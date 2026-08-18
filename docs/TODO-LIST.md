# Java-Agent TODO List — Architecture + Port + Hermes Sync + Multi-User

**Updated:** 2026-08-18  
**Total active:** 88 items (38 cancelled/filtered out)  

---

## CRITICAL — Architecture (8)

1. **c1** Decompose DefaultAgentRuntime (1892→1745 LOC, partial) — ThinkBlockProcessor extracted (-147 LOC). Further: TurnOrchestrator, FallbackController, ToolExecutionCoordinator, MemoryNudgeManager, SessionLockManager
2. **c2** Eliminate duplicated agentic loop — extract shared TurnExecutor from DefaultAgentRuntime + AgentStreamingService
3. ~~**c3** Fix SessionCrudController — delegate to service layer~~ ✅ DONE (087aca3)
4. ~~**c4** Stop returning CronJobEntity from CronJobController~~ ✅ DONE (087aca3)
5. ~~**c5** Split BotMessageProcessor~~ ✅ DONE (087aca3)
6. ~~**c6** Extract StreamSession from StreamEditor~~ ✅ DONE (087aca3)
7. ~~**c7** Split BackendClient (CLI)~~ ✅ DONE (087aca3)
8. ~~**c8** Split SlashCommandRegistry~~ ✅ DONE (087aca3)

## HIGH — Architecture (10)

9. ~~**h9** Remove phantom project(':backend')~~ ✅ DONE (5d4e26b)
10. **h10** Create shared module — API DTOs, SharedObjectMapper, base REST client
11. ~~**h11** Consolidate duplicate security/ packages~~ ✅ DONE (5d4e26b)
12. **h12** ★RESTORED: Introduce repository ports in core — justified for multi-user
13. ~~**h13** Break core.memory ↔ tools.memory cycle~~ ✅ DONE (2e37f4c)
14. ~~**h14** Split AgentConfig into domain-specific @Configuration classes~~ ✅ DONE (a2ece7b)
15. ~~**h15** Split AgentBackendClient (bot, 1474 LOC) → per-domain delegate classes~~ ✅ DONE (f8610ca)
16. ~~**h16** Make CliState/SessionStore/DestructiveCommandConfirmation Spring beans~~ ✅ DONE (70faf14)
17. ~~**h17** Delete ReplLoop — dead code~~ ✅ DONE (087aca3)
18. ~~**h18** Break core.agent ↔ core.context cycle~~ ✅ DONE (5d4e26b)

## MEDIUM — Architecture (11)

19-29. (m19–m31, excluding m29/m30 cancelled) — package cleanup, moves, dead code fixes

## LOW — Architecture (8)

30-37. (l32–l39) — renames, consolidation, cosmetic

## PORT — From Hermes ecosystem (20)

### Security/MCP (5)
MCP Tool Definition Scanner, MCP Response Scanner, Tool Argument Injection Scanner, Sliding-Window Rate Limiter, MCP Rug Pull Detection

### Tool UX (9)
Terminal CWD echo, Terminal error hints, Terminal timeout clarity, Patch already-applied, Search zero-match hints, Write-file verification echo, Read_file truncation UX, Patch multi-match detection, Blocked-command recovery

### Bot (6)
Approval TTL, Approval Auto-Supersede, Post-Debounce Re-Validation, Edit-Capture Mode, Per-Action Owner Auth, Permission-Aware Keyboard

### Utility (1)
Robust JSON Extraction

## HERMES-SYNC — Bug fixes (32)

### MCP (5): tool_call_id reuse, nextCursor pagination, tool-result _meta, Unicode TAG strip, name collision
### Terminal (3): signal-termination exit codes, exit_code 0 masks piped, cwd unenterable
### Tools (3): process unique ID prefixes, mixed valid/invalid calls, UTF-16 reading
### Compression (5): handoff prefix, timeout budget, cooldown reset, failure feedback, quota exhaustion
### Agent (4): empty-response guard, timezone in prompt, think scrubber re-arm, parallel-batch path canonicalisation
### Cron (4): persisted-state recovery ★, retry storm suppression, execution ledger ★RESTORED, self-context ★RESTORED, nudge failing ★RESTORED
### Skills (3): BOM stripping, curator guard, curator audit ledger ★RESTORED
### Error classifier (3): connect/DNS, empty-response advisory, GLM token-limit
### Memory (1): drain queued writes
### Misc (5): AGENTS.override.md, auto-title, reject answer-shaped, session handles ★RESTORED, approval coalesce ★RESTORED, /worktree ★RESTORED, session pin/unpin ★RESTORED

## ★ NEW — MULTI-USER (14)

1. **mu1** Add userId to CronJobEntity — cron jobs must be user-scoped. Migration V27.
2. **mu2** Add userId to SkillEntity — personal vs shared skills. Migration V27.
3. **mu3** Add userId to CheckpointEntity — checkpoints user-scoped. Migration V27.
4. **mu4** Add userId to AuditLogEntity — audit logs user-scoped. Migration V27.
5. **mu5** Add userId filtering to ALL repository queries — every query must filter by userId
6. **mu6** Add userId to ChatRequest DTO + propagate through AgentRuntimeService → all entities inherit user ID
7. **mu7** Per-user API keys — UserEntity + UserApiKeyEntity, replace single global key. Migration V28.
8. **mu8** RBAC on API endpoints — admin vs user role, endpoint-level access control
9. **mu9** Bot AuthorizationService — admin/user/visitor roles, command-level access
10. **mu10** Session isolation — users only see/access own sessions
11. **mu11** Memory isolation — users only read/write own memory
12. **mu12** Cron job isolation — users only manage own cron jobs
13. **mu13** Usage tracking per-user — aggregation endpoint with per-user breakdown
14. **mu14** Skill isolation — shared vs personal, enforce in CuratorService/DatabaseSkillManager

## Restored from cancelled (8 — justified by multi-user)

| ID | Was cancelled because | Restored because |
|----|----------------------|------------------|
| h12 | Over-engineering DDD ports | Multi-user needs per-user query abstraction in core |
| h72 | Over-engineering cron ledger | Multi-user needs per-user cron execution audit trail |
| h77 | Over-engineering curator audit | Multi-user needs per-user skill change attribution |
| h75 | New feature, not requested | Multi-user: each user's cron jobs need own context |
| h76 | New feature, not requested | Multi-user: notify owning user about failing jobs |
| h85 | Gateway approval specific | Multi-user: simultaneous approvals from different users |
| h88 | Java GC handles DB | Multi-user: more session contention, need handle cleanup |
| h94 | New feature, not requested | Multi-user: each user needs isolated git worktree |
| h95 | New feature, not requested | Multi-user: users need to organize own session lists |

## Still cancelled (38)

| Category | Count | Reason |
|----------|-------|--------|
| Skill evolution system | 6 | 1140 LOC research-grade optimization pipeline — separate project |
| Compression eval harness | 7 | 525 LOC testing tool — not a feature |
| Python-specific | 6 | EMFILE, UTF-16 surrogates, rotation tail clone, config parsing, worker model, pruned-skill reload |
| Hermes-architecture-specific | 5 | Delegation stateless/pinned, memory prefetch, config literals, shell metacharacters |
| Niche/low value | 4 | Cross-server detection, structured LLM call, concurrent dispatch, token-overlap |
| Duplicate/reverted | 3 | h50=p8, h52 reverted, h41 SDK handles |
| New feature, not multi-user relevant | 4 | /rollback hand-edits, session mining, skill_view dedup, reject masked verification |
| Cosmetic/docs | 3 | API versioning, gateway/telegram docs, background review cost controls |