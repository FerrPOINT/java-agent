# Java-Agent TODO List — Architecture + Port + Hermes Sync + Multi-User

**Updated:** 2026-08-20 (post parity-audit round 2)
**Total active:** 74 items

---

## CRITICAL — Architecture (2)

1. **c1** Decompose DefaultAgentRuntime (1745 LOC) — remaining: TurnOrchestrator, FallbackController, ToolExecutionCoordinator, MemoryNudgeManager, SessionLockManager (TurnExecutor already extracted)
2. **c2** Eliminate duplicated agentic loop — extract shared TurnExecutor from DefaultAgentRuntime + AgentStreamingService (empty-response/LENGTH/dropped-toolcall recovery now aligned in both, but logic still duplicated)

## HIGH — Architecture (2)

3. **h10** Create shared module — API DTOs, SharedObjectMapper, base REST client
4. **h12** ★RESTORED: Introduce repository ports in core — justified for multi-user

## MEDIUM — Architecture (11)

5-15. (m19–m31, excluding m29/m30 cancelled) — package cleanup, moves, dead code fixes

## LOW — Architecture (8)

16-23. (l32–l39) — renames, consolidation, cosmetic

## PORT — From Hermes ecosystem (9)

### Security/MCP (0) — ✅ ALL DONE 2026-08-20
~~MCP Tool Definition Scanner, MCP Response Scanner, Tool Argument Injection Scanner, Sliding-Window Rate Limiter, MCP Rug Pull Detection~~ — all present in core/security

### Tool UX (6)
Terminal CWD echo ✅, Terminal error hints ✅, Terminal timeout clarity ⏳, Patch already-applied ⏳, Search zero-match hints ✅, Write-file verification echo ✅, Read_file truncation UX ⏳, Patch multi-match detection ✅, Blocked-command recovery ⏳

### Bot (4)
Approval TTL ⏳, Approval Auto-Supersede ⏳, Post-Debounce Re-Validation ⏳, Edit-Capture Mode ⏳, Per-Action Owner Auth ⏳, Permission-Aware Keyboard ⏳

### Utility (1)
Robust JSON Extraction ⏳ (ToolCallArgumentRepair covers most; fence-stripping pending)

## HERMES-SYNC — Bug fixes (17 remaining; 15 closed 2026-08-20)

### MCP (2)
~~nextCursor pagination~~ ✅ FIXED (cursor now passed; dup-cursor guard), ~~Unicode TAG strip~~ ✅, tool-result _meta ⏳ (SessionSearch covers), tool_call_id reuse ⏳, name collision ✅ (warn + dedup)
### Terminal (0) — ✅ ALL DONE
~~signal-termination exit codes~~ ✅, ~~exit_code 0 masks piped~~ ✅ (Java-native search, N/A), ~~cwd unenterable~~ ✅ (h49), ~~ANSI strip~~ ✅ NEW AnsiStrip (ECMA-48 full)
### Tools (3)
process unique ID prefixes ✅, mixed valid/invalid calls ✅, UTF-16 reading ✅ (h55)
### Compression (4)
handoff prefix ✅, timeout budget ⏳, cooldown reset ✅ (h60 + wiring 2026-08-20: 600s + 60/300/900 ladder), failure feedback ⏳, quota exhaustion ✅
### Agent (4)
empty-response guard ✅ (jittered backoff + separate budget 3), timezone in prompt ✅, think scrubber re-arm ✅ (reset per iteration), parallel-batch path canonicalisation ⏳, LENGTH stitching ✅ NEW (4 attempts, stitched partial kept), dropped-toolcall recovery ✅ NEW (3 consecutive, reset on success)
### Cron (0) — ✅ ALL DONE 2026-08-20
persisted-state recovery ✅ (h71), retry storm suppression ✅ (h74), execution ledger ✅ (h72), self-context ✅ (contextFrom + lastRunSessionId), nudge failing ✅ NEW (CronDeliveryPoller delivers to chat)
### Skills (3)
BOM stripping ✅, curator guard ✅, curator audit ledger ⏳
### Error classifier (0) — ✅ ALL DONE
connect/DNS ✅ (h80), empty-response advisory ✅ (h81), GLM token-limit ✅ (h82 + Chinese/Ollama/Together patterns 2026-08-20)
### Memory (1)
drain queued writes ✅ (h86)
### Misc (2)
AGENTS.override.md ✅, auto-title ✅, reject answer-shaped ✅, session handles ⏳, approval coalesce ⏳, /worktree ⏳, session pin/unpin ⏳, silence markers ✅ NEW (Hermes marker set + canonicalisation)

## ★ NEW — MULTI-USER (14)

1. **mu1** Add userId to CronJobEntity — Migration V27
2. **mu2** Add userId to SkillEntity — Migration V27
3. **mu3** Add userId to CheckpointEntity — Migration V27
4. **mu4** Add userId to AuditLogEntity — Migration V27
5. **mu5** Add userId filtering to ALL repository queries
6. **mu6** Add userId to ChatRequest DTO + propagate
7. **mu7** Per-user API keys — UserEntity + UserApiKeyEntity, Migration V28
8. **mu8** RBAC on API endpoints
9. **mu9** Bot AuthorizationService roles
10. **mu10** Session isolation
11. **mu11** Memory isolation
12. **mu12** Cron job isolation
13. **mu13** Usage tracking per-user
14. **mu14** Skill isolation

## Parity-audit round 2 (2026-08-20) — CLOSED findings

| ID | Severity | Finding | Status |
|----|----------|---------|--------|
| F1 | HIGH | LENGTH continuation lost partial content | ✅ stitched, ceiling 4 |
| F2 | HIGH | Empty-response retry: no backoff, shared counter | ✅ separate budget 3, jittered 5-60s interruptible |
| F3 | HIGH | Dropped-toolcall recovery absent | ✅ 3 consecutive, reset on success |
| F4 | MED | DefaultAgentRuntime empty retries: no backoff, EN nudges, '(empty)' pollution | ✅ aligned (streaming path); runtime nudges EN kept (prompt language parity TBD) |
| F5 | MED | Silence markers: missing SILENT/NO REPLY, no canonicalisation | ✅ full Hermes set + 64 cap + edge-punct |
| F6 | MED | retryConsumer baked retry text into stream | ✅ transient display only (accumulated untouched) |
| F7 | LOW | P2.S6 replyTo/threadId not plumbed | ✅ StreamSession.messageThreadId + startStream overload + progress routing |
| F8 | HIGH | Terminal ANSI strip absent | ✅ AnsiStrip ECMA-48 in TerminalTool |
| F9 | MED | SessionSearch ANSI regex incomplete | ✅ uses AnsiStrip |
| F10 | MED | Cron nudge only in logs | ✅ delivered via CronDeliveryPoller |
| F11 | HIGH | MCP pagination refetched page 1 ×100 | ✅ cursor passed, dup guard, nextCursor() public API |
| F12 | HIGH | Compression failure cooldown dead code | ✅ wired 600s + 60/300/900 ladder |
| F13 | MED | ErrorClassifier missing GLM/Chinese/Ollama patterns | ✅ 7 patterns added |
| F14 | MED | ThinkScrubber not reset between iterations | ✅ reset at each LLM call |
| F15 | HIGH | Cron output never reached user | ✅ CronDeliveryPoller + lastRunSessionId + delivered mark (V30) |

## Restored from cancelled (8 — justified by multi-user)

|| ID | Was cancelled because | Restored because ||
(h72, h77, h75, h76, h85, h88, h94, h95 — see git history; h75/h76 now DONE via delivery poller)

## Still cancelled (38)

|| Category | Count | Reason ||
(Skill evolution 6, compression eval 7, Python-specific 6, Hermes-arch-specific 5, niche 4, duplicate/reverted 3, not multi-user 4, cosmetic 3)
