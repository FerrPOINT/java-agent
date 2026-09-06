# Java-Agent TODO List — Architecture + Port + Hermes Sync + Multi-User

**Updated:** 2026-09-06 (0.1.234 — remaining M/L audit wave closed, coverage gate 0.80; see review-findings.md status update)
**Total active:** 60 items

---

## CRITICAL — Architecture (0) — ✅ ALL DONE

~~1. **c1**~~ ✅ DONE 2026-08-20 (FallbackModelCaller extracted: retry+fallback loop + 7 one-shot guards + compression recovery; runtime 1951→1346 LOC; 11 dead helper copies removed)
~~2. **c2**~~ ✅ DONE 2026-09-06 (canonical TurnExecutor.executeToolBatch: BOTH loops now dispatch tool batches through one owner — approval gate incl. fail-closed null-producer fix, /yolo + subagent-auto-approve + subagent auto-deny now streaming too, execute_code budget refund now sync too, budget-exhaustion summary now streaming too, guardrail-halt now streaming too; steer-drain/wrapup/boosted-options deduped into TurnExecutorUtils; DAR 1741→1483 LOC, dead parallel executor removed)

## HIGH — Architecture (0) — ✅ ALL DONE

~~3. **h10**~~ ✅ DONE 2026-09-06 (:shared Gradle module — SharedObjectMapper single source, ChatResponseDto wire DTO, BackendRestClientFactory; bot+cli REST clients migrated; bot's phantom backend dependency removed, reactive-streams added explicitly; bot hand-rolled JsonNode chat parsing → shared DTO; 9 shared tests)
~~4. **h12**~~ ✅ DONE 2026-09-06 (core/ports package: SessionStorePort, MessageStorePort, MemoryStorePort, PendingMemoryStorePort, SkillStorePort, SkillAuditPort, CuratorSnapshotPort, CompressionLockPort + persistence/adapter Jpa*Store delegators; 11 core classes now depend on ports only — core → persistence.repository imports 11 → 0; Pageable no longer leaks into core)

## MEDIUM — Architecture (0) — ✅ ALL DONE

~~5-15.~~ ✅ m21-m28+m31 done (m31 reverted — false finding, Dockerfile needs settings.gradle), m19/m24 were already done, m20 deferred (high-churn/low-value)

## LOW — Architecture (0) — ✅ ALL DONE

~~16-23.~~ ✅ l32 ManagedToolGateway→ManagedToolGate, l33 ImageShrinker→ImageShrinkerService, l34 BackendProperties→CliProperties, l35 codex-runtime kebab, l36 dead description() removed, l37 single-class pkgs consolidated + dead MemoryMonitor deleted, l38 double-brace→explicit bean; l39 REJECTED (metrics facade in own pkg is fine)

## PORT — From Hermes ecosystem (3 — feature candidates)

### Security/MCP (0) — ✅ ALL DONE 2026-08-20

~~MCP Tool Definition Scanner, MCP Response Scanner, Tool Argument Injection Scanner, Sliding-Window Rate Limiter, MCP Rug Pull Detection~~ — all present in core/security

### Tool UX (0) — ✅ ALL DONE

Terminal CWD echo ✅, Terminal error hints ✅, Terminal timeout clarity ✅ (partial output + enhanced hints, exceeds Hermes), Patch already-applied ✅ (p7), Search zero-match hints ✅, Write-file verification echo ✅, Read_file truncation UX ✅ (p10 remaining-lines hint), Patch multi-match detection ✅ (p11), Blocked-command recovery ✅ (p12 alternative suggestions)

### Utility (0) — ✅ ALL DONE

~~Robust JSON Extraction~~ ✅ NEW (ToolCallArgumentRepair + code-fence stripping, plugin_llm.py_FENCE_RE)

### Bot (0) — ✅ VERIFIED NOT PORTABLE 2026-08-20

~~Approval TTL~~ ✅, ~~Approval Auto-Supersede~~ ✅, ~~Post-Debounce Re-Validation~~ ✅ (fail-closed), ~~Edit-Capture Mode~~ ❌ NOT IN HERMES (speculative idea, removed), ~~Per-Action Owner Auth~~ ❌ NOT IN HERMES, ~~Permission-Aware Keyboard~~ ❌ NOT IN HERMES

## HERMES-SYNC — Bug fixes (7 remaining; 25 closed 2026-08-20)

### MCP (0) — ✅ ALL DONE

~~nextCursor pagination~~ ✅ (cursor passed; dup-cursor guard), ~~Unicode TAG strip~~ ✅, ~~tool-result _meta~~ ✅ NEW (McpTool: vendor_meta surfaced, reserved prefixes dropped — kimi-code#2600), ~~tool_call_id reuse~~ ✅ NEW (uniquifyToolCallIds, deterministic _d<n>, both runtimes), name collision ✅ (warn + dedup)

### Terminal (0) — ✅ ALL DONE

~~signal-termination exit codes~~ ✅, ~~exit_code 0 masks piped~~ ✅ (Java-native search, N/A), ~~cwd unenterable~~ ✅ (h49), ~~ANSI strip~~ ✅ NEW AnsiStrip (ECMA-48 full)

### Tools (0) — ✅ ALL DONE

process unique ID prefixes ✅, mixed valid/invalid calls ✅, UTF-16 reading ✅ (h55)

### Compression (2)

handoff prefix ✅, ~~timeout budget~~ ✅ NEW (idle 120s + ceiling 600s, min(idle,ceiling) per conversation_compression.py:789), ~~failure feedback~~ ✅ (classified ladder: json/stream 30s, net 60s, timeout 60/300/900, hard 600s), cooldown reset ✅, quota exhaustion ✅

### Agent (2)

empty-response guard ✅ (jittered backoff + separate budget 3), timezone in prompt ✅, think scrubber re-arm ✅ (reset per iteration), ~~parallel-batch path canonicalisation~~ ✅ NEW (send-path arg canonicalization in LangChain4jModelClient, memoized), LENGTH stitching ✅ NEW, dropped-toolcall recovery ✅ NEW

### Cron (0) — ✅ ALL DONE 2026-08-20

persisted-state recovery ✅ (h71), retry storm suppression ✅ (h74), execution ledger ✅ (h72), self-context ✅ (contextFrom + lastRunSessionId), nudge failing ✅ NEW (CronDeliveryPoller delivers to chat)

### Skills (0) — ✅ ALL DONE

BOM stripping ✅, curator guard ✅, ~~curator audit ledger~~ ✅ NEW (SkillMutationLedger: all 6 skill_manage actions recorded, actor derivation, telemetry-not-gate)

### Error classifier (0) — ✅ ALL DONE

connect/DNS ✅ (h80), empty-response advisory ✅ (h81), GLM token-limit ✅ (h82 + Chinese/Ollama/Together patterns 2026-08-20)

### Memory (1)

drain queued writes ✅ (h86)

### Misc (1)

AGENTS.override.md ✅, auto-title ✅, reject answer-shaped ✅, ~~session handles~~ ✅ ALREADY IMPLEMENTED (@session:profile/id parse + sessionLink generation, verified 2026-08-20), ~~approval coalesce~~ ✅ (supersede via request() + F16 producer wiring), ~~/worktree~~ ⏴ DEFERRED BY USER 2026-08-20 (не нужен для текущего сценария; при необходимости — per-session cwd дизайн + API + CLI-команда), ~~session pin/unpin~~ ❌ NOT IN HERMES (speculative, removed), silence markers ✅ NEW (Hermes marker set + canonicalisation)

## ★ NEW — MULTI-USER (0) — ✅ ALL DONE

~~mu1-mu4~~ ✅ DONE (V29 cron/skill/checkpoint/cron_execution_log user_id + V36 audit_log user_id; entities carry userId) — TODO lagged the code, verified 2026-09-06
~~mu5~~ ✅ DONE (10 repositories scope by userId: findByUserId*/AndUserId* families)
~~mu6~~ ✅ DONE (ChatRequest.userId + bot propagates runtime.getUserId(); session metadata + prompt identity)
~~mu7~~ ✅ DONE (V37 agent_users + user_api_keys; AgentUserEntity/UserApiKeyEntity; raw key returned once, SHA-256 hash stored; UserAccessService)
~~mu8~~ ✅ DONE (ApiKeyAuthFilter: per-user keys > global admin key; UserContext roles; UserAdminController.requireAdmin; 500→403 RBAC fix + ResponseStatusException handler 2026-09-06)
~~mu9~~ ✅ DONE (bot auth: allowed-users/admin-ids, SlashAccessPolicy)
~~mu10~~ ✅ DONE (requireSessionOwnership on chat paths + 6 SessionController endpoints guarded 2026-09-06: history/context/usage/reset/compress/undo; admin/no-auth unaffected)
~~mu11~~ ✅ DONE (memory store/recall scoped by userId; verified live)
~~mu12~~ ✅ DONE (CronJobService userId-first create + user-scoped list)
~~mu13~~ ✅ DONE (UsageEntity.userId; UsageTracker.recordTurn(sessionId, userId, ...))
~~mu14~~ ✅ DONE (SkillRepository findByNameAndUserId/findVisibleSkills)
E2E: scenario 36-multiuser-isolation.yaml (17 steps) — admin auth, 401/403 negatives, per-user keys, session isolation, memory isolation, key revocation; live-verified 17/17 2026-09-06

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
