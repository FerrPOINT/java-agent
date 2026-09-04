# Quality Audit — 2026-08-14 (Memory + Self-Improvement)

## Summary

- Total issues: 39
- CRITICAL: 5 | HIGH: 12 | MEDIUM: 13 | LOW: 9
- Fixed: 39/39 (all issues resolved)

## Issues

### CRITICAL

| # | Status | File | Line | Description | Fix |
|---|--------|------|------|-------------|-----|
| C1 | ✅ | `DatabaseMemoryProvider.java` | 70-73 | `recall(userId, "", 20)` uses FTS with empty query → returns nothing. Memory NEVER injected into system prompt | Use non-FTS query for system prompt injection |
| C2 | ✅ | `DefaultPromptBuilder.java` | 263-265 | Memory prefix fetched fresh every turn → breaks prompt caching invariant | Cache snapshot once per session like Hermes |
| C3 | ✅ | `BackgroundReviewService.java` | 214 | Review writes memory to "review-bot" user, not actual user | Pass parent userId to review session |
| C4 | ✅ | `DefaultAgentRuntime.java` | 186 | `turnsSinceMemory` never reset when `memory` tool called in foreground | Reset counter on memory tool execution |
| C5 | ✅ | `DefaultAgentRuntime.java` | 505 | Background review only sees current turn, not full conversation history | Pass full conversation history to review |

### HIGH

| # | Status | File | Line | Description | Fix |
|---|--------|------|------|-------------|-----|
| H1 | ✅ | `DatabaseMemoryProvider.java` | 22-23 | Char limits hardcoded (2200/1375), ignores AgentProperties config | Inject AgentProperties |
| H2 | ✅ | `DatabaseMemoryProvider.java` | 82-119 | `store()` and `replace()` don't trim content (inconsistent with MemoryStore + Hermes) | Trim before saving |
| H3 | ✅ | `DatabaseMemoryProvider.java` | 82-119 | No threat scanning — MemoryStore scans, DB provider doesn't (security gap) | Inject MemoryThreatScanner |
| H4 | ✅ | `MemoryTool.java` | 80,112 | `target` not validated against enum — accepts arbitrary values | Add validation |
| H5 | ✅ | `DatabaseMemoryProvider.java` | 122-178 | `replace()`/`remove()` not atomic — read-modify-write race | Wrap in @Transactional |
| H6 | ✅ | `DefaultAgentRuntime.java` | 186,553 | Nudge counters don't check if memory/skill tools are available | Check effective toolsets |
| H7 | ✅ | `DefaultAgentRuntime.java` | 329-777 | Review not triggered on budget-exhausted / max-turns paths | Fire review on those paths too |
| H8 | ✅ | `SkillPreprocessor.java` | 34-37 | Config never wired — inline shell always disabled despite config | Add @PostConstruct to read AgentProperties |
| H9 | ✅ | `DefaultAgentRuntime.java` | 1643 | `getReviewSummaryForSurface` is dead code — review summary never surfaced | Call from TurnFinalizer or response path |
| H10 | ✅ | `SkillsSyncService.java` | 70 | No manifest, no per-skill update, no deletion respect | Implement manifest-based sync |
| H11 | ✅ | `BackgroundReviewServiceTest.java` | 558-594 | False positive: `isIn()` accepts ALL 3 prompts → test always passes | Use `isEqualTo(COMBINED_REVIEW_PROMPT)` |
| H12 | ✅ | `BackgroundReviewServiceTest.java` | 443-480 | Stale-action test has empty if-body — no actual assertion | Add concrete assertion on filtered actions |

### MEDIUM

| # | Status | File | Line | Description | Fix |
|---|--------|------|------|-------------|-----|
| M1 | ✅ | `AgentProperties.java` | 171-172 | `maxFactsPerUser` and `maxFactsPerQuery` configured but never enforced | Enforce in store/recall or remove |
| M2 | ✅ | `MemoryStore.java` | 253-255 | `invalidateSnapshot()` clears ALL sessions on any write — breaks frozen-per-session | Don't invalidate on writes |
| M3 | ✅ | `MemoryTool.java` | 181-195 | Response format: plain text vs Hermes structured JSON | Align format |
| M4 | ✅ | `DatabaseMemoryProvider.java` | 239-255 | `read()` includes `[category]` prefixes and `§ MEMORY` header — MemoryStore doesn't | Standardize format |
| M5 | ✅ | `MemoryTool.java` | 221 | `target` not marked `required=true` in schema (Hermes marks it required) | Add `required = true` |
| M6 | ✅ | `DatabaseMemoryProvider.java` | 94 | Dedup uses `contains(fact)` without trimming | Trim before dedup |
| M7 | ✅ | `MemoryTool.java` | 83-112 | Provenance computed but never persisted to DB | Add metadata column |
| M8 | ✅ | `DefaultAgentRuntime.java` | 114 | Nudge counters not hydrated from history on restart | Count prior user turns on first turn |
| M9 | ✅ | `BackgroundReviewService.java` | 58 | MAX_REVIEW_TURNS=5 vs Hermes 16, not configurable | Increase to 8-10, make configurable |
| M10 | ✅ | `DefaultAgentRuntime.java` | 553 | `itersSinceSkill` incremented only on tool-call path, not every iteration | Move to top of loop |
| M11 | ✅ | `BackgroundReviewServiceTest.java` | 271-279 | `clearFlag` test doesn't create a summary first → trivially passes | Create summary then clear |
| M12 | ✅ | `MemoryStoreTest.java` | 63-80 | `getSnapshot_frozenPerSession` doesn't verify invalidation works | Add invalidation + verify update |
| M13 | ✅ | `DatabaseSkillManagerBranchTest.java` | 548-574 | Collision test uses same content → no actual collision tested | Use different content for DB vs filesystem |

### LOW

| # | Status | File | Line | Description | Fix |
|---|--------|------|------|-------------|-----|
| L1 | ✅ | `MemoryTool.java` | 92 | `case "read"` handled but not in schema enum — dead code | Remove case or add to enum |
| L2 | ✅ | `MemoryStore.java` | 257-274 | `formatBlock` format differs from Hermes (no separator box, no usage stats) | Align format |
| L3 | ✅ | `MemoryTool.java` | 211 | Error response only appends usage if error contains "limit"/"chars"/"exceed" | Always append usage or handle drift separately |
| L4 | ✅ | `DefaultAgentRuntime.java` | 1612 | `getOrDefault` creates throwaway AtomicInteger on every call | Use get() with null check |
| L5 | ✅ | `AgentProperties.java` | 215 | Skill nudge default 15 vs Hermes 10 | Align to 10 |
| L6 | ✅ | `DefaultAgentRuntime.java` | 675 | `skill_manage` counter reset AFTER execution, not before (Hermes resets BEFORE) | Move reset before execution |
| L7 | ✅ | `SkillSecurityScannerBranchTest.java` | 337-348 | Fork bomb test: `if (!findings.isEmpty())` may silently pass | Use `assertThat(findings).isNotEmpty()` |
| L8 | ✅ | `DatabaseSkillManagerBranchTest.java` | 426-437 | Trust level test: `verify(repo).save(any())` — doesn't verify what was saved | Use ArgumentCaptor |
| L9 | ✅ | `MemoryManagerTest.java` | 24 | `@MockitoSettings(strictness = LENIENT)` hides unused stubs | Switch to STRICT_STUBS |

## Status Legend

- ❌ — Found, not yet fixed
- 🔧 — Fix in progress
- ✅ — Fixed and verified (build passes)

## Change Log

- 2026-08-14: Audit created, 39 issues found (5 CRITICAL, 12 HIGH, 13 MEDIUM, 9 LOW)
- 2026-08-14: Fixed agent runtime + background review issues (C3, C4, C5, H6, H7, H9, M8, M9, M10, L4, L5, L6)
- 2026-08-14: Fixed memory provider + tool issues (C1, C2, H1, H2, H3, H4, H5, H8, H10, H11, H12, M1, M2, M3, M4, M5, M6, M7, M11, M12, M13, L1, L2, L3, L7, L8, L9). All 39/39 issues resolved. Build passes (`./gradlew build -x slowTest` → BUILD SUCCESSFUL).
