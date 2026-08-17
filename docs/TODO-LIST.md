# Java-Agent TODO List — Architecture + Port + Hermes Sync

**Created:** 2026-08-15  
**Total active:** 88 items (47 cancelled/filtered out)  
**Categories:** CRITICAL (8), HIGH (9), MEDIUM (11), LOW (8), PORT (20), HERMES-sync (32)

---

## CRITICAL — Architecture (8)

1. **c1** Decompose DefaultAgentRuntime (1847 LOC, 31 deps) → TurnOrchestrator, FallbackController, ToolExecutionCoordinator, MemoryNudgeManager, SessionLockManager
2. **c2** Eliminate duplicated agentic loop — extract shared TurnExecutor from DefaultAgentRuntime + AgentStreamingService
3. **c3** Fix SessionCrudController — delegate to service layer, not repositories directly
4. **c4** Stop returning CronJobEntity from CronJobController — create CronJobDto
5. **c5** Split BotMessageProcessor (1164 LOC, 20 deps) → UpdateDispatcher, CommandExecutionService, TextMediaProcessor, StreamingOrchestrator, BusyMessageHandler, MediaDeliveryCoordinator
6. **c6** Extract StreamSession from StreamEditor — consolidate 17 concurrent maps into per-chat state objects
7. **c7** Split BackendClient (CLI, 1888 LOC) → transport + formatter, or per-domain API classes
8. **c8** Split SlashCommandRegistry.registerAll() (800 LOC) → grouped command classes

## HIGH — Architecture (9)

9. **h9** Remove phantom project(':backend') from telegram-bot/build.gradle
10. **h10** Create shared module — API DTOs, SharedObjectMapper, base REST client
11. **h11** Consolidate duplicate security/ packages — merge security/ + core/security/
12. **h13** Break core.memory ↔ tools.memory cycle — abstract behind ToolSchemaProvider
13. **h14** Split AgentConfig (68 imports) → ModelClientConfig, MemoryConfig, SecurityConfig, etc.
14. **h15** Split AgentBackendClient (bot, 1474 LOC) → per-domain delegate classes + typed DTOs
15. **h16** Make CliState/SessionStore/DestructiveCommandConfirmation Spring beans
16. **h17** Delete ReplLoop — dead code
17. **h18** Break core.agent ↔ core.context cycle

## MEDIUM — Architecture (11)

18. **m19** Unify health/ + api/health/ packages
19. **m20** Introduce CLI sub-packages (repl, command, backend, render, state)
20. **m21** Move inline controller records (StopRequest, SteerRequest, TtsRequest) to api/dto/
21. **m22** Move OsvCheckService from client/mcp to security
22. **m23** Move MidTurnPersistenceService and MessagePersistenceService to service or persistence/service
23. **m24** Fix BotMessageProcessor double-lock dead code in handleTextOrMediaInternal
24. **m25** Make RichMessageSupport a Spring bean
25. **m26** Remove duplicate CLI commands (/handoff, /gquota, /platforms, /quit)
26. **m27** Remove System.exit(0) from CLI command lambdas — use signal/exception
27. **m28** Replace shell-out curl in ContextReferenceExpander with RestClient
28. **m31** Remove backend/settings.gradle (ignored, misleading)

## LOW — Architecture (8)

29. **l32** Rename ManagedToolGateway to avoid gateway package collision
30. **l33** Rename ImageShrinker to ImageShrinkerService
31. **l34** Rename BackendProperties to CliProperties
32. **l35** Rename codex_runtime command to codex-runtime (kebab-case)
33. **l36** Remove SlashCommand.description() dead code method
34. **l37** Consolidate single-class packages (lock, footer, monitor, reaction)
35. **l38** Replace double-brace init in BotConfig with explicit bean
36. **l39** Merge metrics package (1 file) into existing package

## PORT — From Hermes ecosystem (20)

### Security/MCP (5)
37. **p1** MCP Tool Definition Scanner — hidden instructions, invisible unicode, encoded payloads (~450 LOC)
38. **p2** MCP Response Scanner — prompt injection, exfiltration URLs in tool output (~250 LOC)
39. **p3** Tool Argument Injection Scanner — recursive arg scanning for injection (~100 LOC)
40. **p30** Sliding-Window Rate Limiter — per-tool/per-server rate limiting for MCP (~150 LOC)
41. **p31** MCP Rug Pull Detection — SHA-256 fingerprinting, alert on silent change (~120 LOC)

### Tool UX improvements (9)
42. **p4** Terminal CWD echo — show working directory after each command (~10 LOC)
43. **p5** Terminal error hints — 'command not found' → suggest install (~80 LOC)
44. **p6** Terminal timeout clarity — guide toward background=true (~5 LOC)
45. **p7** Patch already-applied no-op — check if new_string already present (~10 LOC)
46. **p8** Search zero-match hints — case-insensitive probe on 0-match (~15 LOC)
47. **p9** Write-file verification echo — first/last line preview (~10 LOC)
48. **p10** Read_file truncation UX — show remaining lines count (~5 LOC)
49. **p11** Patch multi-match detection — warn if old_string not unique (~15 LOC)
50. **p12** Blocked-command recovery hint — suggest alternative (~15 LOC)

### Bot improvements (5)
51. **p32** Approval TTL Expiration Sweep — background sweeper (~25 LOC)
52. **p33** Approval Auto-Supersede — new approval removes prior pending (~10 LOC)
53. **p34** Post-Debounce Re-Validation — re-check chat state after debounce (~10 LOC)
54. **p35** Edit-Capture Mode — next message = override for approval (~35 LOC)
55. **p36** Per-Action Owner Auth — verify callback user = approval owner (~15 LOC)
56. **p37** Permission-Aware Keyboard — conditionally include/exclude buttons (~10 LOC)

### Utility (1)
57. **p20** Robust JSON Extraction from LLM output — balanced-brace parser (~40 LOC)

## HERMES-SYNC — Bug fixes from Hermes commits last month (32)

### MCP (5)
58. **h42** MCP tool_call_id reuse — keep results when server reuses IDs (0b8fd04b)
59. **h43** MCP nextCursor pagination — follow in discovery, non-string = end (6030ca8c, a8ec4153)
60. **h44** MCP tool-result _meta — surface to model, minus protocol-reserved keys (c031fec3)
61. **h45** Strip invisible Unicode TAG chars from MCP content (8bbda8ff)
62. **h46** MCP name collision — prefer server-native tool over generated utility (d6f18cd7)

### Terminal (3)
63. **h47** Signal-termination exit codes — interpret for the model (204302bd)
64. **h48** exit_code 0 masks piped failure — warn (8ad05541)
65. **h49** cwd unenterable fallback — not just missing (71252f0d)

### Tools (3)
66. **h51** Process tool unique ID prefixes — accept in lookups (2e4d771c)
67. **h53** Mixed valid/invalid tool calls — execute valid in mixed batches (348e9912)
68. **h55** UTF-16 text file reading — transcode to UTF-8 (341d5aeb)

### Compression (5)
69. **h57** Handoff prefix — affirm tool use stays active (c7205040)
70. **h58** Timeout budget — fallback candidates get own timeout (bd7e4802)
71. **h60** Failure cooldown reset — reset on runtime switch (bcce7007)
72. **h61** Failure feedback — harden + preserve missing-key history (1e895f4c, 577beeb9)
73. **h62** Quota exhaustion — preserve messages when summary quota exhausted (c72f4576, 202ad1b8)

### Agent core (4)
74. **h63** Empty-response guard — stop re-billing deterministic empty responses (ac06c2ff, d10f8724)
75. **h65** Timezone in system prompt — include timezone and UTC offset (da392043)
76. **h67** Think scrubber boundary re-arm — after stream flush (a569f244)
77. **h69** Parallel-batch path canonicalisation — prevent same-file concurrent mutation (9a21d0e3)

### Cron (2)
78. **h71** Persisted-state recovery — re-arms recurring job stuck in stale error (122bfad5)
79. **h74** Retry storm suppression — stop when gateway deliberately stopped (48221569)

### Skills (2)
80. **h78** Curator guard background review against manually authored skills (62364122)
81. **h79** SKILL.md BOM stripping — strip UTF-8 BOM before parsing frontmatter (a4ecb3da)

### Error classifier (3)
82. **h80** Connect/DNS failure on generic exception types (75336301)
83. **h81** Empty-response advisory → stop triggering compression (032a424f)
84. **h82** GLM token-limit → classify as context overflow (174fc958)

### Memory (1)
85. **h86** Drain queued writes on shutdown (c356752b)

### Misc (4)
86. **h90** AGENTS.override.md — support context override file (a8d5e16c)
87. **h91** Auto-title — avoid overwriting manual titles + atomic write (f725cf83, d05cd7c1)
88. **h93** Reject answer-shaped auto-title output (d5167831)

---

## Cancelled (47 — not applicable)

| ID | Reason |
|----|--------|
| h12 | Over-engineering: DDD ports pattern for Spring Boot JPA |
| m29 | Documentation task, both gateway impls serve different purposes |
| m30 | Cosmetic: mixed v1/v2 is fine |
| p13 | Too niche, low value |
| p14-p19 | Skill evolution system: 1140 LOC unrequested massive feature |
| p21 | Session mining: 300 LOC niche, depends on conversation history |
| p22-p28 | Compression eval harness: 525 LOC testing tool, not a feature |
| p29 | Cross-Server Attack Detection: only with multiple MCP servers |
| p38-p40 | LLM utilities: not critical for current priorities |
| h41 | MCP stateless protocol: SDK handles versioning |
| h50 | Duplicate of p8 |
| h52 | Tool call dedup: REVERTED in Hermes, was problematic |
| h54 | Reject masked verification: unclear, low priority |
| h56 | Compression rotation tail clone: Python-specific |
| h59 | Pruned-skill reload: Hermes skill system specific |
| h64 | Background review cost controls: refinements, not bugs |
| h66 | Worker finalization: Hermes worker model, Java uses virtual threads |
| h68 | UTF-16 surrogates in guardrail hashing: edge case |
| h70 | Cron EMFILE: Java has higher FD limits |
| h72 | Cron execution ledger: over-engineering |
| h73 | Cron stale claim reap: Hermes claim system specific |
| h75 | Cron self-context: new feature, not requested |
| h76 | Cron nudge failing jobs: new feature, not requested |
| h77 | Curator audit ledger: over-engineering for current scale |
| h83 | Delegation stateless channel: Hermes delegation model |
| h84 | Delegation pinned provider: Hermes delegation model |
| h85 | Approval coalesce: gateway approval flow specific |
| h87 | Memory fail-fast external prefetch: not applicable |
| h88 | State DB handles: Java GC + connection pool handles this |
| h89 | Config parse literals: Java uses YAML, different parsing |
| h92 | Quoted shell metacharacters: Hermes allowlist specific |
| h94 | /worktree: new feature, not requested |
| h95 | Session pin/unpin: new feature, not requested |
| h96 | /rollback hand-edits: low priority behavior change |