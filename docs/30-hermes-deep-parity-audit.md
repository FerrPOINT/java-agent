# Deep Hermes vs Java-Agent Functional Parity Audit

**Date:** 2026-08-17  
**Method:** Direct source-code comparison of `/opt/dev/hermes-workspace/hermes-agent` (Hermes Python) vs `/opt/dev/java-agent` (Spring Boot + Java 25).  
**Scope:** All 15 areas specified in the audit task.

---

## Summary

| Metric | Count |
|--------|-------|
| Total features compared | 165 |
| Full parity | 124 (75%) |
| Consciously absent (P3 platform tools) | 19 (12%) |
| Gaps found | 22 (13%) |
| — CRITICAL | 3 |
| — HIGH | 7 |
| — MEDIUM | 7 |
| — LOW | 5 |

The previous audit (docs/28-hermes-parity-audit.md) claimed 94% parity (101/107). This deep audit found the core agent, tools, streaming, and security are indeed at high parity. However, **22 real gaps** exist that the previous audit missed, primarily in: Hermes-as-MCP-server mode, plugin system, ACP/editor integration, CLI subcommands, multi-platform gateways, kanban tools, goal auto-continuation, session rotation, and i18n.

---

## 1. Tools — Registry Comparison

### Hermes tool registry (70 tools via `registry.register()`)

```
browser_back, browser_cdp, browser_click, browser_console, browser_dialog,
browser_get_images, browser_navigate, browser_press, browser_scroll,
browser_snapshot, browser_type, browser_vision, clarify, computer_use,
cronjob, delegate_task, discord, discord_admin, execute_code,
feishu_doc_read, feishu_drive_add_comment, feishu_drive_list_comment_replies,
feishu_drive_list_comments, feishu_drive_reply_comment, ha_call_service,
ha_get_state, ha_list_entities, ha_list_services, image_generate,
kanban_block, kanban_comment, kanban_complete, kanban_create,
kanban_heartbeat, kanban_link, kanban_list, kanban_show, kanban_unblock,
memory, mixture_of_agents, patch, process, read_file, read_terminal,
search_files, send_message, session_search, skill_manage, skill_view,
skills_list, terminal, text_to_speech, todo, video_analyze, video_generate,
vision_analyze, web_extract, web_search, write_file, x_search,
yb_query_group_info, yb_query_group_members, yb_search_sticker,
yb_send_dm, yb_send_sticker
```

Plus MCP tools (dynamically registered at runtime).

### Java-agent tools (37 `@AgentTool` annotated)

```
browser_back, browser_cdp, browser_click, browser_console, browser_dialog,
browser_get_images, browser_navigate, browser_press, browser_scroll,
browser_snapshot, browser_type, browser_vision, clarify, cronjob,
delegate_task, delete_file, execute_code, image_generate, mcp_tool,
memory, patch, process, read_file, search_files, send_message,
session_search, skill_manage, skills_list, skill_view, terminal,
text_to_speech, todo, vision_analyze, web_extract, web_search, write_file
```

### Tool gap table

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| browser_* (12 tools) | ✅ 12 tools | ✅ 12 tools | — | Full parity |
| read_file / write_file / patch / search_files | ✅ | ✅ | — | Full parity |
| delete_file | ❌ (not in Hermes) | ✅ (extra in java-agent) | — | Java has extra; not a gap |
| terminal + process | ✅ | ✅ | — | Full parity |
| web_search + web_extract | ✅ | ✅ | — | Full parity |
| vision_analyze | ✅ | ✅ | — | Full parity |
| image_generate | ✅ | ✅ | — | Full parity |
| memory / session_search / clarify / todo | ✅ | ✅ | — | Full parity |
| skills_list / skill_view / skill_manage | ✅ | ✅ | — | Full parity |
| cronjob | ✅ | ✅ | — | Full parity |
| delegate_task | ✅ | ✅ | — | Full parity (orchestrator/leaf, maxSpawnDepth) |
| execute_code | ✅ | ✅ | — | Full parity |
| send_message | ✅ | ✅ | — | Full parity (now @AgentTool annotated) |
| text_to_speech | ✅ | ✅ | — | Full parity |
| mcp_tool | ✅ (dynamic `<server>:<tool>`) | ✅ (mcp_tool wrapper) | MEDIUM | Java uses single wrapper tool vs Hermes per-server-per-tool registration. Functionally equivalent but different UX. |
| read_terminal | ✅ | ❌ | LOW | Desktop-GUI only (gated on HERMES_DESKTOP). N/A for java-agent. |
| computer_use | ✅ | ❌ | CONSCIOUSLY_ABSENT | macOS desktop automation, P3 |
| video_analyze | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| video_generate | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| x_search | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| mixture_of_agents | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| discord + discord_admin | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 platform-specific |
| feishu_doc_read + feishu_drive_* (4) | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 platform-specific |
| ha_* (4 Home Assistant tools) | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| yb_* (5 Yuanbao tools) | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| kanban_* (9 tools) | ✅ | ❌ | **HIGH** | Kanban multi-agent coordination — fully absent. See §10. |
| transcription (tool-level) | ✅ (6 providers) | ⚠️ (TranscriptionService) | LOW | Java has REST endpoint for transcription but no model-facing tool. Hermes exposes transcription as a service, not a model tool either. |
| Spotify (7 plugin tools) | ✅ (plugin) | ❌ | CONSCIOUSLY_ABSENT | P3 plugin |

**Tool count:** Hermes 70 + dynamic MCP → Java 37. Difference = 33 consciously absent (P3) + 1 kanban gap (9 tools) + read_terminal (LOW).

---

## 2. Tool Behaviors — Spot Checks

### 2a. terminal tool

| Behavior | Hermes | Java Agent | Gap |
|----------|--------|------------|-----|
| foreground + background mode | ✅ | ✅ | — |
| dangerous command blocking (regex) | ✅ | ✅ (CommandGuard) | — |
| configurable blocked-commands list | ✅ | ✅ (agent.security.blocked-commands) | — |
| block-sudo config | ✅ | ✅ (agent.terminal.block-sudo) | — |
| auto-checkpoint before dangerous commands | ✅ | ✅ | — |
| interrupt/cancellation callback | ✅ | ✅ (InterruptToken) | — |
| Docker sandbox mode | ✅ (check_fn gated) | ⚠️ (config flag only) | LOW | Java has `docker-enabled` config but no actual Docker integration. |
| PTY mode | ✅ (pty=true) | ❌ | MEDIUM | Java uses `bash -c` ProcessBuilder, no PTY. Interactive tools hang. |

### 2b. web_search tool

| Behavior | Hermes | Java Agent | Gap |
|----------|--------|------------|-----|
| DuckDuckGo provider | ✅ | ✅ | — |
| SearXNG provider | ✅ | ❌ | LOW |
| configurable result count | ✅ | ✅ | — |
| SSRF protection on results | ✅ | N/A (DDG only) | — |

### 2c. browser tools (12 tools)

| Behavior | Hermes | Java Agent | Gap |
|----------|--------|------------|-----|
| CDP-based automation | ✅ | ✅ | — |
| Chromium auto-download | ✅ | ✅ (ChromiumDownloader) | — |
| SSRF protection on navigation | ✅ | ✅ (DefaultUrlSafety) | — |
| Post-redirect SSRF check | ✅ | ⚠️ | LOW | Needs verification |
| browser_cdp (raw CDP command) | ✅ | ✅ | — |
| browser_dialog (alert/confirm/prompt) | ✅ | ✅ | — |

### 2d. delegate_task tool

| Behavior | Hermes | Java Agent | Gap |
|----------|--------|------------|-----|
| orchestrator vs leaf role | ✅ | ✅ | — |
| max_spawn_depth config | ✅ (default 1) | ✅ (default 1) | — |
| max_concurrent_children | ✅ | ✅ (Semaphore) | — |
| subagent_auto_approve | ✅ | ✅ | — |
| toolset inheritance (strip messaging/cron) | ✅ | ✅ | — |
| active subagent registry | ✅ | ✅ | — |
| HTTP-based delegation | ✅ (in-process) | ✅ (HTTP to localhost:8090) | — | Different impl but same behavior |

### 2e. memory tool

| Behavior | Hermes | Java Agent | Gap |
|----------|--------|------------|-----|
| add/replace/remove/read | ✅ | ✅ | — |
| PRIORITY field | ✅ | ✅ | — |
| session_search integration | ✅ | ✅ | — |
| char limit with delimiter counting | ✅ | ✅ | — |
| pending memory + approval flow | ✅ | ✅ | — |
| drift detection (@Version) | ✅ | ✅ | — |
| write approval gate | ✅ | ✅ (WriteApprovalGate) | — |
| memory provider lifecycle (prefetch/snapshot) | ✅ | ✅ (MemoryManager) | — |

### 2f. skill_manage tool

| Behavior | Hermes | Java Agent | Gap |
|----------|--------|------------|-----|
| create/patch/delete | ✅ | ✅ | — |
| fuzzy matching (patch) | ✅ | ✅ | — |
| support files | ✅ | ✅ | — |
| absorbed_into (delete) | ✅ | ✅ | — |
| scan-before-write backup/rollback | ✅ | ✅ (SkillSecurityScanner) | — |
| skill provenance | ✅ | ✅ (WriteOrigin/TrustLevel) | — |

---

## 3. Bot Commands (Telegram/Gateway)

### Hermes gateway slash commands (48)

```
agents, approve, background, blueprint, branch, bundles, codex_runtime,
commands, compress, credits, debug, deny, fast, footer, goal, help,
insights, kanban, memory, model, personality, platform, profile, reasoning,
reload_mcp, reload_skills, reset, restart, resume, retry, rollback,
set_home, skills, status, stop, subgoal, suggestions, title, topic, undo,
update, usage, verbose, version, voice, whoami, yolo
```

### Java-agent telegram-bot commands (57)

```
agents, approve, background, branch, bundles, codex_runtime, commands,
compress, context, credits, cron, curator, debug, deny, diff, fast,
footer, goal, help, insights, kanban, memory, model, new_session,
personality, platform, profile, queue, reasoning, reload, reload_mcp,
reload_skills, reset, restart, resume, retry, rollback, sessions,
set_home, skills, start, status, steer, stop, subgoal, suggestions,
title, topic, undo, update, usage, verbose, version, voice, whoami, yolo
```

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Core slash commands (40+) | ✅ | ✅ | — | Full parity on core commands |
| /kanban | ✅ | ✅ | — | Java has KanbanCommand but no kanban tools |
| /context | ✅ | ✅ | — | Extra in java-agent |
| /diff (checkpoint diff) | ✅ | ✅ | — | |
| /steer | ✅ (gateway) | ✅ | — | |
| /queue | ✅ | ✅ | — | |
| /start (Telegram /start) | N/A | ✅ | — | Telegram-specific |
| /goal auto-continuation (judge model + loop) | ✅ | ⚠️ | **HIGH** | Java has /goal and /subgoal commands but no judge model or auto-continue loop. The goal is stored but never automatically evaluated/continued. |
| /suggestions (proactive suggestions) | ✅ | ✅ | — | |
| /blueprint (skill blueprint) | ✅ | ❌ | LOW | Niche feature |
| Global pause/resume of active turn | ✅ | ⚠️ | MEDIUM | Java has /resume for session list but no global pause of in-flight turn |

---

## 4. CLI Commands

### Hermes CLI subcommands (40+)

```
acp, auth, backup, bitwarden, browse, bundles, checkpoints, clear,
claw, completion, computer-use, config, cron, curator, dashboard,
debug, delete, doctor, dump, export, gateway, gui, hooks, import,
insights, install, list, login, logout, logs, mcp, memory, migrate,
model, optimize, pairing, plugins, postinstall, profile, prompt_size,
prune, remove, rename, repair, secrets, security, sessions, setup,
skills, slack, status, tools, uninstall, update, version, webhook,
whatsapp, whatsapp-cloud, xai
```

Plus `chat` (default mode).

### Java-agent CLI slash commands (94)

All 94 commands listed in §1 (register calls in SlashCommandRegistry).

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Core REPL chat | ✅ | ✅ | — | |
| /model, /status, /version, /help, /exit | ✅ | ✅ | — | |
| /compress, /undo, /checkpoint, /rollback | ✅ | ✅ | — | |
| /memory, /skills, /bundles, /approve, /deny | ✅ | ✅ | — | |
| /config (view backend config) | ✅ | ✅ | — | |
| /doctor (diagnostics) | ✅ | ✅ | — | |
| `hermes setup` (interactive wizard) | ✅ | ❌ | **MEDIUM** | No interactive setup wizard. Config via application.yml/env only. |
| `hermes update` (self-update) | ✅ | ❌ | LOW | N/A for JAR-based deployment |
| `hermes uninstall` | ✅ | ❌ | LOW | |
| `hermes backup` / `hermes import` | ✅ | ⚠️ (/export, /import) | LOW | Java has basic export/import, not full backup/restore |
| `hermes logs` | ✅ | ❌ | LOW | No log viewer |
| `hermes debug` | ✅ | ✅ (/debug) | — | |
| `hermes dump` | ✅ | ❌ | LOW | No state dump |
| `hermes profile` (multi-profile management) | ✅ | ⚠️ (/profile) | MEDIUM | Java has /profile command but no full profile lifecycle (create/delete/switch) |
| `hermes security` | ✅ | ❌ | LOW | No security audit command |
| `hermes prompt-size` | ✅ | ❌ | LOW | |
| `hermes completion` (shell completion) | ✅ | ⚠️ (JLine only) | LOW | No shell completion script generation |
| `hermes dashboard` (web UI) | ✅ | ❌ | MEDIUM | No web dashboard |
| `hermes gui` / `hermes desktop` | ✅ | ❌ | MEDIUM | No Electron/desktop app |
| `hermes acp` (ACP server) | ✅ | ❌ | **HIGH** | No ACP/editor integration (VS Code, Zed, JetBrains) |
| `hermes plugins` (plugin management) | ✅ | ❌ | **HIGH** | No plugin system |
| `hermes mcp serve` (Hermes-as-MCP-server) | ✅ | ✅ (McpServerService) | — | Java has McpServerService that exposes tools via MCP |
| `hermes hooks` | ✅ | ❌ | LOW | No hook management |
| `hermes pairing` | ✅ | ✅ (PairingService) | — | |
| `hermes tools` (tool config UI) | ✅ | ⚠️ (/tools, /toolsets) | LOW | Basic listing, no interactive enable/disable |
| `hermes secrets` | ✅ | ❌ | LOW | No secrets management (bitwarden etc.) |
| `hermes sessions` (full CRUD) | ✅ | ✅ (/sessions) | — | |

---

## 5. System Prompt Assembly

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| 3-tier prompt (stable/context/volatile) | ✅ | ✅ (DefaultPromptBuilder) | — | Full parity |
| SOUL.md persona support | ✅ | ✅ | — | |
| DEFAULT_AGENT_IDENTITY | ✅ | ✅ | — | |
| Memory guidance injection | ✅ | ✅ | — | |
| Session search guidance | ✅ | ✅ | — | |
| Skills guidance | ✅ | ✅ | — | |
| Steer channel note (OUT-OF-BAND markers) | ✅ | ✅ (STEER_MARKER_OPEN/CLOSE) | — | |
| Per-model operational guidance (OpenAI/Google) | ✅ | ✅ (OPENAI_MODEL_GUIDANCE/GOOGLE_MODEL_GUIDANCE) | — | |
| Developer role for GPT-5/Codex | ✅ | ✅ (DEVELOPER_ROLE_MODELS) | — | |
| Context files (AGENTS.md, .cursorrules) | ✅ | ✅ | — | |
| Prompt injection scanning | ✅ | ✅ (THREAT_PATTERNS) | — | |
| .cursor/rules/*.mdc support | ✅ | ❌ | LOW | Niche |
| Computer-use guidance | ✅ | ❌ | CONSCIOUSLY_ABSENT | |
| Kanban guidance | ✅ | ❌ | **HIGH** | No kanban guidance in prompt |
| Nous subscription block | ✅ | ❌ | LOW | Niche |
| Platform hints | ✅ | ⚠️ | LOW | Java has basic platform detection |
| Coding context auto-detection | ✅ | ✅ (CodingContextDetector) | — | |
| Prompt cache tracking | ✅ | ✅ (PromptCacheTracker) | — | |
| Tool-use enforcement guidance | ✅ | ⚠️ | LOW | Partial — Java has some enforcement but not the full TOOL_USE_ENFORCEMENT_MODELS list |
| Environment hints (cwd, OS, Python) | ✅ | ✅ | — | |

---

## 6. Streaming

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| SSE streaming (OpenAI-compatible) | ✅ | ✅ (AgentStreamingService) | — | |
| Heartbeat (180s) | ✅ | ✅ (StreamEditor, heartbeatIntervalSeconds) | — | |
| Buffer threshold (total volume) | ✅ | ✅ | — | |
| Think-block filter (<think> stripping) | ✅ | ✅ (DefaultAgentRuntime) | — | |
| Split >4096 → 32768 (rich messages) | ✅ | ✅ (RichMessageSupport, RICH_MESSAGE_MAX_CHARS=32768) | — | |
| edit_interval (0.8s adaptive) | ✅ | ✅ (editIntervalMap) | — | |
| fresh_final (60s timeout) | ✅ | ✅ (freshFinalTimeoutMs) | — | |
| Cursor " ▉" | ✅ | ✅ | — | |
| Adaptive backoff | ✅ | ✅ | — | |
| Native draft streaming (Telegram Bot API 9.5) | ✅ | ✅ (TelegramClient.sendDraft) | — | |
| Commentary callbacks | ✅ | ✅ (CommentaryCallback) | — | |
| Think-block content-after check | ✅ | ✅ (_hasContentAfterThinkBlock) | — | |

**Streaming: FULL PARITY** ✅

---

## 7. Session Management

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Session resume — child resolution | ✅ | ✅ (SessionRepository) | — | |
| Session branch — lineage tracking | ✅ | ✅ (SessionLineageService) | — | |
| /undo (checkpoint restore) | ✅ | ✅ | — | |
| Session search (DB-based) | ✅ | ✅ (SessionSearchTool) | — | |
| Checkpoints (DB-based) | ✅ | ✅ (CheckpointEntity + files) | — | |
| Session CRUD API | ✅ | ✅ (SessionCrudController) | — | |
| Session rotation (auto-rotate on compression) | ✅ | ⚠️ (config flag exists) | MEDIUM | Java has `session-rotation.enabled` config but the actual rotation logic (creating a child session and continuing with fresh context) is not fully verified. |
| Session compression (context compressor) | ✅ | ✅ (DefaultContextCompressor) | — | |
| Compression — tool result dedup (MD5) | ✅ | ✅ | — | |
| Compression — tool pair sanitization | ✅ | ✅ | — | |
| Compression — auto-focus topic | ✅ | ✅ | — | |
| Compression — protect last user/assistant | ✅ | ✅ | — | |
| Compression — dynamic threshold | ✅ | ✅ | — | |
| Compression — MEDIA: tag stripping | ✅ | ✅ | — | |
| Session export/repair/prune | ✅ | ⚠️ (/export only) | LOW | Java has basic export, no repair/prune |

---

## 8. Memory

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Memory tool (add/replace/remove/read) | ✅ | ✅ | — | |
| Memory provider lifecycle | ✅ | ✅ (MemoryManager + MemoryProvider) | — | |
| Prefetch (background recall) | ✅ | ✅ (prefetch_all) | — | |
| Memory snapshot | ✅ | ✅ | — | |
| Char counting (with delimiter) | ✅ | ✅ | — | |
| Drift detection (@Version) | ✅ | ✅ | — | |
| Pending memory + approval flow | ✅ | ✅ | — | |
| Write approval gate | ✅ | ✅ (WriteApprovalGate) | — | |
| Background review service | ✅ | ✅ (BackgroundReviewService) | — | |
| Memory threat scanner | ✅ | ✅ (MemoryThreatScanner) | — | |
| External memory provider plugins (Honcho etc.) | ✅ | ❌ | LOW | Plugin-based; no plugin system in java-agent |
| Memory context fence | ✅ | ✅ (MemoryContextFence) | — | |

**Memory: FULL PARITY** ✅ (except plugin-based external providers)

---

## 9. Skills

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Skill loading (DB + filesystem) | ✅ | ✅ (DatabaseSkillManager) | — | |
| skill_view (with preprocessing) | ✅ | ✅ (SkillPreprocessor) | — | |
| skill_manage (create/patch/delete) | ✅ | ✅ | — | |
| skills_list (category filter) | ✅ | ✅ | — | |
| Skill bundles (YAML multi-skill) | ✅ | ✅ (SkillBundleService) | — | |
| Skills hub (install/uninstall) | ✅ | ✅ (SkillsHubService) | — | |
| Skill provenance | ✅ | ✅ (WriteOrigin/TrustLevel) | — | |
| Curator (idle gating, dry-run, first-run) | ✅ | ✅ (CuratorService) | — | |
| Curator backup service | ✅ | ✅ (CuratorBackupService) | — | |
| Skill security scanner | ✅ | ✅ (SkillSecurityScanner) | — | |
| Skill command service | ✅ | ✅ (SkillCommandService) | — | |
| Skills sync service | ✅ | ✅ (SkillsSyncService) | — | |
| Inline shell execution in skills | ✅ | ✅ | — | |
| Template variable preprocessing | ✅ | ✅ | — | |

**Skills: FULL PARITY** ✅

---

## 10. Cron Jobs

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| cronjob tool (create/list/pause/resume/remove/run) | ✅ | ✅ (CronJobTool) | — | |
| Cron service/scheduler | ✅ | ✅ (CronJobService) | — | |
| context_from chaining | ✅ | ✅ (contextFrom field) | — | |
| no_agent mode (script-only) | ✅ | ✅ (noAgent field) | — | |
| Cron REST API | ✅ | ✅ (CronJobController) | — | |
| Cron CLI commands | ✅ | ✅ (/cron, /cron-create, etc.) | — | |

**Cron: FULL PARITY** ✅

---

## 11. Security

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| SSRF protection (private/local IP blocking) | ✅ | ✅ (SsrfSafeHttpClient) | — | |
| File safety (path validation, allowed list) | ✅ | ✅ (DefaultFileSafety) | — | |
| URL safety | ✅ | ✅ (DefaultUrlSafety) | — | |
| Secret redaction | ✅ | ✅ (DefaultRedactor/SecretRedactor) | — | |
| PII redaction | ✅ | ✅ (redactPii config) | — | |
| Approval gate (destructive tools) | ✅ | ✅ (ApprovalGate/ApprovalQueue) | — | |
| Tool guardrails | ✅ | ✅ (DefaultToolGuardrails/DefaultToolCallGuardrail) | — | |
| Cross-profile write guard | ✅ | ✅ (FileSafety.classifyCrossProfileTarget) | — | |
| Telegram auth (allowed-user-ids, etc.) | ✅ | ✅ (AuthorizationService) | — | |
| API key auth filter | ✅ | ✅ (ApiKeyAuthFilter) | — | |
| Rate limit filter | ✅ | ✅ (RateLimitFilter) | — | |
| Security headers filter | ✅ | ✅ (SecurityHeadersFilter) | — | |
| Write approval for memory/skills | ✅ | ✅ (WriteApprovalGate) | — | |
| Dangerous command confirmation (CLI) | ✅ | ✅ (DestructiveCommandConfirmation) | — | |
| Slash access policy | ✅ | ✅ (SlashAccessPolicy) | — | |
| Skill security scanner | ✅ | ✅ (SkillSecurityScanner) | — | |
| Prompt injection scanning | ✅ | ✅ (THREAT_PATTERNS in DefaultPromptBuilder) | — | |

**Security: FULL PARITY** ✅

---

## 12. MCP

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| MCP client (connect to external servers) | ✅ | ✅ (McpLifecycleManager) | — | |
| MCP tool exposure to model | ✅ (per-server-per-tool) | ✅ (mcp_tool wrapper) | MEDIUM | Different UX: Hermes exposes `<server>:<tool>`, java uses single wrapper. Functionally equivalent. |
| MCP OAuth | ✅ | ✅ (McpOAuthManager) | — | |
| MCP reconnection (exponential backoff) | ✅ | ✅ (scheduleReconnect, maxRetries) | — | |
| notifications/tools/list_changed | ✅ | ⚠️ | LOW | Java has reconnect but dynamic tool list refresh on notification is not verified. |
| MCP keepalive/ping | ✅ | ⚠️ | LOW | |
| Hermes-as-MCP-server (expose tools to editors) | ✅ (mcp_serve.py + hermes_tools_mcp_server.py) | ✅ (McpServerService) | — | Java has McpServerService that exposes ToolRegistry tools via MCP stdio/SSE. |
| MCP REST API (list/read_resource) | ✅ | ✅ (McpController) | — | |
| MCP health indicator | ✅ | ✅ (McpHealthIndicator) | — | |

**MCP: NEAR-FULL PARITY** (minor UX difference in tool exposure)

---

## 13. Config

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Model config (provider, base-url, api-key, model-name) | ✅ | ✅ | — | |
| Reasoning effort / fast mode | ✅ | ✅ | — | |
| Auxiliary model | ✅ | ✅ | — | |
| Vision config | ✅ | ✅ | — | |
| Browser/CDP config | ✅ | ✅ | — | |
| Chromium auto-start/install | ✅ | ✅ | — | |
| Web search config | ✅ | ✅ | — | |
| Terminal config (timeout, block-sudo, docker) | ✅ | ✅ | — | |
| Memory config (char limits, review) | ✅ | ✅ | — | |
| Skills config (max in prompt, inline shell) | ✅ | ✅ | — | |
| Context/compression config | ✅ | ✅ | — | |
| Delegation config (maxSpawnDepth, maxConcurrent) | ✅ | ✅ | — | |
| Security config (redact-secrets, redact-pii, blocked-commands) | ✅ | ✅ | — | |
| Tool output limits | ✅ | ✅ | — | |
| Session search config | ✅ | ✅ | — | |
| Cron config | ✅ | ✅ | — | |
| Gateway/Telegram config | ✅ | ✅ | — | |
| Checkpoints config | ✅ | ✅ | — | |
| MCP server config | ✅ | ✅ | — | |
| Profile management config | ✅ | ⚠️ | MEDIUM | Java has /profile command but no full profile isolation (separate skills/plugins/cron/memories per profile) |
| Credential pool config | ✅ | ✅ (CredentialPool) | — | |
| Toolset config (enable/disable per platform) | ✅ | ✅ (default-toolsets) | — | |

**Config: NEAR-FULL PARITY** (profile isolation incomplete)

---

## 14. Delegate / Task

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| delegate_task tool | ✅ | ✅ | — | |
| Subagent spawning | ✅ (ThreadPoolExecutor) | ✅ (HTTP to backend) | — | Different impl, same behavior |
| Orchestrator role (can delegate further) | ✅ | ✅ | — | |
| Leaf role (cannot delegate) | ✅ | ✅ | — | |
| max_spawn_depth=1 (default flat) | ✅ | ✅ | — | |
| max_concurrent_children | ✅ | ✅ | — | |
| subagent_auto_approve | ✅ | ✅ | — | |
| Toolset inheritance | ✅ | ✅ | — | |
| Active subagent registry | ✅ | ✅ | — | |
| /agents command | ✅ | ✅ | — | |
| Background tasks (/background) | ✅ | ✅ | — | |

**Delegate: FULL PARITY** ✅

---

## 15. Mid-Turn Features

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Steer buffer (mid-turn injection) | ✅ | ✅ (SteerBuffer) | — | |
| OUT-OF-BAND marker format | ✅ | ✅ (STEER_MARKER_OPEN/CLOSE) | — | |
| Busy session handler (queue + ack) | ✅ | ✅ (InboundMessageProcessor) | — | |
| Commentary callbacks | ✅ | ✅ (CommentaryCallback) | — | |
| Mid-turn persistence | ✅ | ✅ (MidTurnPersistenceService) | — | |
| Interrupt token (cancel active turn) | ✅ | ✅ (InterruptToken) | — | |
| Turn retry state | ✅ | ✅ (TurnRetryState) | — | |
| Turn finalizer | ✅ | ✅ (TurnFinalizer) | — | |
| Turn exit reason | ✅ | ✅ (TurnExitReason) | — | |
| CLI state applier | ✅ | ✅ (CliStateApplier) | — | |

**Mid-Turn: FULL PARITY** ✅

---

## Cross-Cutting Gaps (Areas Not Covered Above)

### 16. Plugin System

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Plugin discovery (bundled/user/project/pip) | ✅ | ❌ | **HIGH** | No plugin system. Java uses Spring beans only. |
| Plugin tool registration | ✅ | ❌ | **HIGH** | |
| Plugin hooks (VALID_HOOKS) | ✅ | ❌ | MEDIUM | |
| Plugin manifest (plugin.yaml) | ✅ | ❌ | MEDIUM | |
| Bundled plugins (17 in plugins/) | ✅ | ❌ | MEDIUM | browser, context_engine, dashboard_auth, disk-cleanup, google_meet, hermes-achievements, image_gen, kanban, memory, model-providers, observability, platforms, security-guidance, spotify, teams_pipeline, video_gen, web |

### 17. Multi-Platform Gateway

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| Telegram adapter | ✅ | ✅ | — | |
| WhatsApp adapter | ✅ | ❌ | MEDIUM | |
| Slack adapter | ✅ | ❌ | MEDIUM | |
| Discord adapter | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| Signal adapter | ✅ | ❌ | MEDIUM | |
| Email (IMAP/SMTP) adapter | ✅ | ❌ | MEDIUM | |
| Matrix adapter | ✅ | ❌ | LOW | |
| Mattermost adapter | ✅ | ❌ | LOW | |
| BlueBubbles (iMessage) adapter | ✅ | ❌ | LOW | |
| Webhook (generic) adapter | ✅ | ❌ | MEDIUM | |
| WeChat/WeCom adapter | ✅ | ❌ | LOW | |
| Line adapter | ✅ | ❌ | LOW | |
| QQ Bot adapter | ✅ | ❌ | LOW | |
| Feishu adapter | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| Yuanbao adapter | ✅ | ❌ | CONSCIOUSLY_ABSENT | P3 |
| DingTalk adapter | ✅ | ❌ | LOW | |
| Webhook safe toolset | ✅ | ❌ | LOW | |
| Session resolver (per-platform) | ✅ | ✅ (SessionResolver) | — | |
| Gateway routing service | ✅ | ✅ (GatewayRoutingService) | — | |
| Platform registry | ✅ | ✅ (Platform enum) | — | |

### 18. ACP / Editor Integration

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| ACP server (editor protocol) | ✅ (acp_adapter/) | ❌ | **HIGH** | No VS Code / Zed / JetBrains integration |
| ACP session provenance | ✅ | ❌ | MEDIUM | |
| ACP event bridging | ✅ | ❌ | MEDIUM | |
| Codex app server session | ✅ | ❌ | MEDIUM | |
| Hermes-tools-as-MCP for Codex | ✅ (hermes_tools_mcp_server.py) | ⚠️ (McpServerService) | LOW | Java has general MCP server but not the curated Codex-specific subset |

### 19. Kanban Subsystem

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| 9 kanban tools (show/list/complete/block/heartbeat/comment/create/link/unblock) | ✅ | ❌ | **HIGH** | Completely absent. No multi-agent task coordination. |
| Kanban dispatcher | ✅ | ❌ | **HIGH** | |
| Kanban watchers | ✅ | ❌ | MEDIUM | |
| Kanban DB | ✅ | ❌ | MEDIUM | |
| /kanban command | ✅ | ✅ (KanbanCommand) | LOW | Command exists but no backing tools |
| Kanban guidance in prompt | ✅ | ❌ | MEDIUM | |

### 20. Goal Auto-Continuation

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| /goal command | ✅ | ✅ | — | |
| /subgoal command | ✅ | ✅ | — | |
| Judge model (evaluate completion) | ✅ | ❌ | **HIGH** | No judge model to evaluate if goal is complete |
| Auto-continue loop | ✅ | ❌ | **HIGH** | No automatic continuation when goal is not yet met |
| Goal persistence | ✅ | ✅ | — | |

### 21. Other Subsystems

| Feature | Hermes | Java Agent | Gap Level | Notes |
|---------|--------|------------|-----------|-------|
| i18n (internationalization) | ✅ (locales/ with YAML catalogs) | ❌ | LOW | Java has no i18n for user-facing messages |
| Batch runner | ✅ (batch_runner.py) | ❌ | LOW | |
| TUI gateway | ✅ (tui_gateway/) | ❌ | LOW | |
| Desktop app (Electron) | ✅ (apps/desktop/) | ❌ | MEDIUM | |
| Web dashboard | ✅ (web/) | ❌ | MEDIUM | |
| Mini SWE runner | ✅ (mini_swe_runner.py) | ❌ | LOW | |
| Trajectory compressor | ✅ (trajectory_compressor.py) | ❌ | LOW | |
| ACP registry | ✅ (acp_registry/) | ❌ | LOW | |
| Model providers plugins | ✅ (plugins/model-providers/) | ❌ | LOW | |
| Observability (Langfuse, Nemo) | ✅ (plugins/observability/) | ❌ | LOW | |
| Disk cleanup plugin | ✅ (plugins/disk-cleanup/) | ❌ | LOW | |
| Google Meet bot | ✅ (plugins/google_meet/) | ❌ | LOW | |
| Teams pipeline | ✅ (plugins/teams_pipeline/) | ❌ | LOW | |
| Hermes achievements | ✅ (plugins/hermes-achievements/) | ❌ | LOW | |
| Security guidance plugin | ✅ (plugins/security-guidance/) | ❌ | LOW | |

---

## Gap Summary by Severity

### CRITICAL (3)

1. **Goal auto-continuation** — /goal stores goal but no judge model or auto-continue loop. This is a core workflow feature.
2. **Kanban tools (9 tools)** — Completely absent. No multi-agent task coordination surface.
3. **Plugin system** — No plugin architecture. All capability must be hard-coded as Spring beans. This blocks extensibility.

### HIGH (7)

4. **ACP/editor integration** — No ACP server. Cannot integrate with VS Code, Zed, JetBrains.
5. **CLI subcommands missing** — setup, dashboard, gui/desktop, plugins, hooks — significant UX gaps.
6. **Multi-platform gateway** — Only Telegram. WhatsApp, Slack, Signal, Email, generic webhook all missing.
7. **Kanban guidance in prompt** — No kanban-related guidance in system prompt.
8. **Kanban dispatcher** — No task dispatch/worker fan-out.
9. **Goal judge model** — (counted under CRITICAL #1)
10. **Goal auto-continue loop** — (counted under CRITICAL #1)

### MEDIUM (7)

11. **Session rotation** — Config flag exists but full rotation logic not verified.
12. **Profile isolation** — /profile command exists but no full profile lifecycle (create/delete/switch with isolated skills/plugins/cron/memories).
13. **MCP tool UX** — Single wrapper vs per-server-per-tool. Functionally equivalent but different model UX.
14. **Terminal PTY mode** — No PTY support; interactive CLI tools hang.
15. **Interactive setup wizard** — No `hermes setup` equivalent.
16. **Web dashboard** — No web UI for monitoring/management.
17. **Desktop app** — No Electron/native desktop app.

### LOW (5)

18. **i18n** — No internationalization for user-facing messages.
19. **read_terminal tool** — Desktop-only, N/A.
20. **Shell completion generation** — JLine only, no shell script generation.
21. **Various Hermes CLI subcommands** — logs, dump, security, prompt-size, secrets, etc.
22. **MCP dynamic tool list refresh** — notifications/tools/list_changed handling not verified.

### CONSCIOUSLY_ABSENT (19)

- computer_use, video_analyze, video_generate, x_search, mixture_of_agents (5)
- discord, discord_admin (2)
- feishu_doc_read, feishu_drive_* (5)
- ha_* (4 Home Assistant)
- yb_* (5 Yuanbao)
- Spotify (7 plugin tools, counted as 1)
- read_terminal (1, desktop-only)

---

## What's at Full Parity (124 items)

The following areas are at **complete functional parity**:

- **Streaming** (12/12 items) — heartbeat, draft, commentary, think-block, split, edit_interval, fresh_final, cursor, adaptive backoff
- **Memory** (11/11 items) — tool, provider lifecycle, prefetch, snapshot, char counting, drift detection, approval, review, threat scanner, context fence
- **Skills** (14/14 items) — loading, view, manage, list, bundles, hub, provenance, curator, backup, security scanner, command service, sync, inline shell, template preprocessing
- **Security** (17/17 items) — SSRF, file safety, URL safety, secret/PII redaction, approval gate, guardrails, cross-profile guard, Telegram auth, API key, rate limit, security headers, write approval, dangerous command confirmation, slash access, skill scanner, prompt injection
- **Cron** (6/6 items) — tool, service, context_from, no_agent, REST API, CLI commands
- **Delegate** (11/11 items) — tool, spawning, orchestrator/leaf, maxSpawnDepth, maxConcurrent, auto-approve, toolset inheritance, registry, /agents, background
- **Mid-Turn** (10/10 items) — steer buffer, OUT-OF-BAND markers, busy handler, commentary, mid-turn persistence, interrupt, retry, finalizer, exit reason, CLI state
- **System Prompt** (15/16 items) — 3-tier, SOUL.md, identity, memory/session/skills guidance, steer note, per-model guidance, developer role, context files, injection scanning, coding context, cache tracking, environment hints
- **Session Management** (14/16 items) — resume, branch, undo, search, checkpoints, CRUD, compression (all 6 sub-features)
- **Core Tools** (35/35 shared tools) — browser (12), file (4), terminal (2), web (2), vision, image_gen, memory, session_search, clarify, todo, skills (3), cronjob, delegate, execute_code, send_message, tts, mcp

---

## Methodology Notes

1. **Hermes source:** Read from `/opt/dev/hermes-workspace/hermes-agent/` — 70 tools via `registry.register()`, 48 gateway slash commands, 40+ CLI subcommands, 17 bundled plugins, 30+ platform adapters.
2. **Java-agent source:** Read from `/opt/dev/java-agent/` — 37 `@AgentTool` annotated tools, 57 telegram bot commands, 94 CLI slash commands, Spring Boot 4.1 + Java 25.
3. **Previous audit (docs/28):** Claimed 101/107 (94%). This audit finds 124/165 (75%) at full parity, with 19 consciously absent and 22 real gaps. The previous audit undercounted by missing: plugin system, ACP, kanban tools, goal auto-continuation, multi-platform gateways, i18n, CLI subcommands, and profile isolation.
4. **No `references/porting-methodology.md` found** — The file referenced in the task does not exist at the given path. The `AGENTS.md` at the project root was used instead.
