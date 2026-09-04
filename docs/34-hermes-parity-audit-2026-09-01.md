# Hermes-Parity Audit - 2026-09-01

## Scope

- Java target: current dirty worktree `C:\git\azhukov\sdlc\java-agent`.
- Hermes reference: `C:\git\azhukov\sdlc\прототипы\hermes`.
- Mode: compare source and regression tests, fix bounded behavior gaps immediately, keep large runtime/protocol gaps as backlog.
- Credentials: no live OpenAI, ElevenLabs, GitHub, Telegram, or browser-cloud credentials were used; coverage is by source parity, unit mocks, local HTTP fixtures, and contract regressions.

## Release Gate Notes

- Current branch state remains `main...origin/main [ahead 1, behind 44]`.
- Current worktree remains intentionally dirty: 298 tracked changed files and 133 untracked files after the latest cron model snapshot batch.
- Because many production/test/migration files are untracked, `git diff --check` alone is not a complete release gate; untracked text files were scanned separately for trailing whitespace.

## Fixed During This Pass

| Area | Java files | Hermes parity issue closed |
|------|------------|----------------------------|
| Test suite stabilization | `backend/build.gradle`, `OpenAiRunsControllerTest`, `SlidingWindowRateLimiterTest` | Backend tests now run with `maxHeapSize = '4g'`, `maxParallelForks = 1`, and `forkEvery = 750`; async OpenAI run tests wait for completion before Mockito verification; image-history persistence test stubbing is lenient because runtime execution races legitimately with the assertion scope; the rate limiter concurrency regression uses deterministic latches instead of timing-sensitive completion. |
| Cron monitor/continuity/attach | `CronJobTool`, `CronJobService`, `CronJobEntity`, `CronJobDto`, `HermesCronJobsController`, `CronDashboardController` | `cronjob` now accepts/persists Hermes fields `monitor`, `monitor_script`, `monitor_url`, `continuity`, and `attach_to_session`; monitor output is normalized, hashed, deduped on unchanged output, and injected into the next agent run when changed; `run` reports `execution_mode = background` for agent jobs. |
| Cron execution correctness | `CronJobService`, `CronJobTool`, `HermesCronJobsController`, `CronDashboardController` | Scheduled failures now produce explicit outcomes and no longer consume repeat counts/delete repeat jobs as if successful; scheduled/manual/background cron paths share the per-job lock; REST/dashboard triggers return background acknowledgements for agent jobs; model override fields are passed through create/update. |
| Cron persistence migration | `V38__cron_monitor_continuity_attach.sql` | Added persisted monitor state and continuity/attached-session columns: `monitor`, `monitor_last_hash`, `monitor_last_output`, `monitor_last_changed_at`, `continuity_enabled`, `attached_session_id`. |
| Delegate async bounded parity | `DelegateTaskTool`, `DelegatedTaskRunService`, `DelegatedTaskRunEntity`, `DelegatedTaskRunRepository`, `V39__delegated_task_runs.sql` | `delegate_task(background=true)`, `async=true`, `live=true`, or `action=create` now returns immediately with `run_id` and Hermes-style `delegation_id`; durable status/read/list/cancel control actions are backed by a persisted run ledger; service-level `createIfCapacity` makes background dispatch capacity acceptance atomic inside the Java runtime; completion is stored once and cancellation is scoped to the parent session. |
| Delegate tool schema honesty | `DelegateTaskTool`, `AgentToolAnnotationParityTest` | The model-facing `delegate_task` description no longer promises Hermes automatic gateway reinjection in Java before the Java gateway consumer loop exists. It advertises the real current contract instead: background dispatch returns `run_id`/`delegation_id`, completion is persisted and emitted to the event stream, and callers can inspect it with `action=status` or `action=read`. |
| Migration smoke | `FlywayMigrationTest`, `V39__delegated_task_runs.sql`, `V40__session_profile_scope.sql`, `V41__cron_profile_scope.sql` | Postgres migration smoke now includes common and vendor locations and asserts V39-V41 plus the `delegated_task_runs`, session profile, and cron profile schema. |
| Profile isolation foundation | `ProfileService`, `ProfilesDashboardController`, `SessionCrudController`, `SessionEntity`, `SessionRepository`, `V40__session_profile_scope.sql` | Dashboard profile create/switch/rename/delete/SOUL/description/model writes are real file-backed operations with Hermes-style validation; sessions created and read through `/p/{profile}/api/sessions` are persisted and guarded by profile scope instead of acting as global aliases. |
| Profile export/import archives | `backend/build.gradle`, `ProfileService`, `ProfilesDashboardController`, `ProfileServiceTest`, `ProfilesDashboardControllerTest` | Dashboard profile export/import is no longer a `not implemented` stub. Exports write real `.tar.gz` archives, default-profile exports use a Hermes-style root allowlist, named-profile exports exclude credential/runtime files, text artifacts are scrubbed in the staged copy before archive creation, extra desktop overlay files are validated, imports inspect exactly one top-level archive root, reject unsafe member paths and `default` imports, skip credential files, and return imported `desktop.json` overlay data for the dashboard. |
| Profile metadata writes | `ProfileService`, `ProfileServiceTest` | Java's atomic profile writer now preserves symlinked targets such as dotfile-managed `profile.yaml`: it writes atomically to the resolved link target instead of replacing the link itself. Profile YAML output also allows real Unicode, so astral-plane characters in descriptions do not become `\U...` / `\u...` escapes. This keeps Hermes' metadata merge/durability behavior without turning profile writes into broken local files. |
| Profile dashboard session aggregation | `ProfilesDashboardController`, `SessionRepository`, `ProfilesDashboardControllerTest` | `/api/profiles/sessions` now returns real DB-backed session rows instead of a permanent empty list. The Java response tags each row with `profile`/`is_default_profile`, preserves the `data`/`sessions` list aliases, reports `profile_totals`, supports Hermes-style `profile=all|<name>`,`min_messages`,`archived`,`order`,`source`,`sources`,`exclude_sources`, hidden/children/pinned switches, and fails closed for invalid or unknown profile scopes. |
| Profile sidebar session slices | `ProfilesDashboardController`, `SessionRepository`, `ProfilesDashboardControllerTest` | `/api/profiles/sessions/sidebar` now returns real batched sidebar slices instead of static empty arrays. The `recents_profile` scope applies to recents, cron, and messaging together; recents/messaging honor caller-provided source exclusions; cron uses `source=cron`; all slices require `min_messages=1`, exclude archived rows, keep Hermes' 300s active heuristic through the shared projection, report messaging totals, profile usage totals, and recents truncation flags, and backfill pinned rows past the normal window. |
| Profile project tree Home bucket | `ProfilesDashboardController`, `SessionRepository`, `ProfilesDashboardControllerTest` | `/api/profiles/projects/tree` no longer returns a permanently empty project tree. Until Java persists Hermes `cwd`/`git_repo_root` project metadata, it now returns a real `__no_project__` / `Home` bucket backed by session rows from the Java store, including profile-tagged sessions, preview rows, scoped session ids, session counts, and usage totals. Full Hermes project/repo/worktree grouping remains an explicit backlog gap rather than a fake tree. |
| Profile PR scan recovery | `ProfilesDashboardController`, `MessageRepository`, `ProfilesDashboardControllerTest` | `/api/profiles/sessions/pull-requests` no longer returns a permanent empty map. It scans requested session transcripts for JSON tool outputs whose whole trimmed output is a GitHub pull-request URL, ignores prose-wrapped URLs, keeps the latest matching output in transcript order, dedupes request ids, preserves non-UUID ids in `scanned`, and does not make live GitHub calls. |
| Profile terminal launcher | `ProfilesDashboardController`, `ProfilesDashboardControllerTest` | `/api/profiles/{name}/open-terminal` now matches Hermes' bounded dashboard behavior: known profiles return `{ok:true, command}` after dispatching the setup command to a system terminal launcher, missing profiles still fail closed, unsupported local terminal environments map to HTTP 400, and unexpected launcher failures map to HTTP 500. Unit tests inject a no-op launcher so verification never opens a real terminal. |
| Session import | `SessionCrudController`, `SessionCrudControllerTest` | `/api/sessions/import` now imports dashboard-exported session rows instead of returning a permanent 501. It accepts Hermes string ids by mapping them to stable Java UUIDs within the profile scope, stores the original id in session CLI state, persists imported messages, reports `imported`, `skipped`, `imported_ids`, `skipped_ids`, and per-row errors, and skips duplicates without failing the whole batch. |
| Session prune bounded subset | `SessionCrudController`, `SessionRepository`, `SessionCrudControllerTest` | `/api/sessions/prune` now performs real ended-session pruning over the fields Java actually persists: profile scope, source, title substring, end reason, user id, model substring, started window, explicit/implicit older-than cutoff, message-count bounds, include-archived switch, pinned-row protection, `dry_run`, candidate summaries, `skipped_open`, child orphaning, message deletion, session deletion, and session-deleted events through the existing delete path. Hermes filters that Java cannot honestly evaluate yet (`cwd_prefix`, billing provider, chat id/type, branch, token/cost/tool-call bounds) return explicit 501 unsupported errors instead of being silently ignored. |
| Session owner backfill | `SessionCrudController`, `SessionRepository`, `SessionCrudControllerTest` | `/api/sessions/owner-backfill` now performs the Hermes legacy owner migration instead of a no-op: it validates route/body profile scope, stamps only `NULL` or blank persisted session profiles, never overwrites already-owned rows, reports the DB row count as `stamped`, and works through profile-prefixed routes. |
| Profile-scoped memory and SOUL prompt | `MemoryScope`, `MemoryTool`, `DefaultPromptBuilder`, `MemoryToolTest`, `MemoryUserPrefixInjectionTest`, `DefaultPromptBuilderTest` | Built-in memory reads/writes and prompt memory injection now use a profile-scoped storage key for named profiles while preserving default-profile user ids. `SOUL.md` prompt loading now resolves `profiles/<name>/SOUL.md` for named profile sessions or process profile fallback, instead of leaking the default configured SOUL into profile-scoped runs. Invalid profile metadata fails closed into a safe internal scope. |
| Profile-scoped memory dashboard | `MemoryDashboardController`, `MemoryDashboardControllerTest` | `/api/memory?profile=<name>` and `/p/{profile}/api/memory` now read/reset the same profile-scoped built-in memory key used by runtime memory tools, so dashboard char counts and reset operations no longer leak across default and named profiles. Profile-prefixed routes validate known profiles fail-closed, reject invalid profile ids, and reject query/path profile mismatches. Provider config writes remain explicitly unsupported until Java has a real Hermes-style provider config writer. |
| Profile-scoped skills dashboard | `SkillsDashboardController`, `SkillsDashboardControllerTest` | `/api/skills`, `/api/skills/content`, `/api/skills/toggle`, `POST /api/skills`, and `PUT /api/skills/content` now honor requested named profiles through `ProfileService.profilePath(profile)/skills` and profile-local `config.yaml` `skills.disabled`, while default profile keeps the existing global `SkillManager` behavior. `/p/{profile}/api/skills` routes validate profile scope fail-closed and reject path/query/body mismatches. Hub background install/update/uninstall remain explicit unsupported gaps because Java still lacks Hermes' `hermes -p <profile>` subprocess action runner. |
| Profile-scoped toolsets dashboard | `ProfileService`, `ToolsetsController`, `ToolsetsControllerTest`, `ProfileServiceTest` | `/p/{profile}/v1/toolsets`, `/p/{profile}/api/tools/toolsets`, toolset toggle, and provider-selection routes no longer behave as aliases over global `AgentProperties`. Named profiles read/write `profiles/<name>/config.yaml` through `ProfileService`: `platform_toolsets.<platform>` for API/dashboard toolsets, `stt.enabled` for config-only STT, and provider choices like `web.backend` / `web.search_backend`, `tts.provider`, `image_gen.provider`, `vision.provider`, and `stt.provider`. Unknown profiles, `profile=all`, and path/query/body mismatches fail closed. Env writes and post-setup actions remain explicit unsupported gaps because Java still lacks Hermes' profile env store and interactive setup runner. |
| Profile-scoped dashboard config/env | `ProfileService`, `DashboardSystemController`, `DashboardSystemControllerTest`, `ProfileServiceTest` | `/api/config?profile=<name>`, `/p/{profile}/api/config`, `/api/config/raw`, `/api/config/schema`, and `/api/env` now validate requested profile scope fail-closed instead of silently reading global `AgentProperties`. Named-profile config reads merge the profile-local `config.yaml` over Java defaults without applying global runtime model overrides, redact sensitive config keys in the sanitized JSON response, expose the real profile `config.yaml` path/text for the raw editor, and support real named-profile config/raw YAML writes. Default-profile writes remain explicit unsupported because Java still has no durable static application config writer. Env writes/reveals remain unsupported, but named-profile env status no longer derives from global `AgentProperties` secrets. |
| Profile-scoped dashboard preferences | `DashboardSystemController`, `DashboardSystemControllerTest` | `/p/{profile}/api/dashboard/themes`, `/p/{profile}/api/dashboard/theme`, `/p/{profile}/api/dashboard/font`, and `/p/{profile}/api/dashboard/font` now read/write `dashboard.theme` and `dashboard.font` in the named profile's `config.yaml`. The default route preserves the previous in-process fallback, while path/query/body profile mismatches fail closed so changing one profile's UI settings no longer mutates another profile's dashboard state. |
| Profile-scoped dashboard status | `DashboardSystemController`, `DashboardSystemControllerTest` | `/api/status?profile=<name>` and `/p/{profile}/api/status` now resolve `hermes_home`, `env_path`, and `config_path` against the requested named profile and return the real profile list from `ProfileService`. Unknown profiles and path/query mismatches fail closed. Runtime health fields remain the honest Java fallback rather than fake Hermes gateway/platform probes. |
| Profile-scoped model dashboard/listing | `ModelOptionsController`, `ModelsController`, `ProfileService`, `ModelOptionsControllerTest`, `ModelsControllerTest`, `ProfileServiceTest` | `/api/model/options`, `/api/model/info`, `/api/model/auxiliary`, `/api/model/recommended-default`, `/api/model/set`, `/p/{profile}/api/model/...`, and `/p/{profile}/v1/models` now resolve requested named profiles fail-closed instead of falling through to global model state. Named-profile reads use `profiles/<name>/config.yaml`; main assignments persist `model.provider`, `model.default`, `model.base_url`, and explicit `model.api_key` without mutating `RuntimeConfigService`; auxiliary assignments and resets persist `auxiliary.<slot>` in the same profile config. `/p/{profile}/v1/models` now advertises the named profile id by default or profile-local `platforms.api_server.extra.model_name` when configured, while default `/v1/models` keeps `hermes-agent` and existing route aliases. The larger Hermes expensive-model pricing confirmation remains an explicit gap rather than a fake confirmation flow. |
| Cron model snapshots, model-impact summary, and drift guard | `CronJobService`, `CronJobEntity`, `CronJobDto`, `HermesCronJobsController`, `CronDashboardController`, `ModelOptionsController`, `V44__cron_model_snapshots.sql`, `CronJobServiceTest`, `CronDashboardControllerTest`, `HermesCronJobsControllerTest`, `ModelOptionsControllerTest`, `CronJobDtoMapperTest`, `FlywayMigrationTest` | Cron jobs now persist separate Hermes `provider_snapshot` and `model_snapshot` fields for unpinned agent jobs instead of overloading per-job provider/model overrides. Snapshot capture is best-effort, profile-aware, skips pinned axes, clears on `no_agent`, and only recomputes when effective inference axes change; dashboard updates with `provider/model/base_url: null` keep existing snapshots. `/api/jobs` and dashboard cron payloads expose the snapshot fields. Successful main model assignments now return a real profile-local `cron_model_impact` summary with `available`, `guard_enabled`, bounded affected jobs, and `drifted_axes`; auxiliary assignments still omit impact summaries like Hermes. Runtime cron execution now fails closed before inference when an unpinned provider/model snapshot drifts, unless `cron.model_drift_guard: false` is explicitly configured. |
| Profile-scoped legacy kanban dashboard | `KanbanController`, `TodoService`, `KanbanControllerProfileTest`, `TodoServiceTest`, `AgentControllerTest`, `AgentControllerBranchCoverageTest` | Legacy `/api/v1/agent/kanban` keeps the old default user id for compatibility, while `/p/{profile}/api/v1/agent/kanban` now validates known profiles, uses a profile-scoped todo owner key, and prevents `done/{id}` from completing another profile's todo by UUID. Unknown profiles, `profile=all`, and path/query mismatches fail closed. This closes the bounded dashboard leak; full Hermes todo runtime hydration remains covered by the existing session-scoped `todo` tool work. |
| Built-in memory enablement/schema flags | `AgentProperties`, `SpringToolRegistry`, `MemoryTool`, `AgentChatController`, `AgentPropertiesTest`, `SpringToolRegistryTest`, `MemoryToolTest`, `AgentControllerTest` | Java now honors Hermes-style `memory.memory_enabled` and `memory.user_profile_enabled`: the `memory` tool is hidden when both built-in stores are disabled, advertised `target.enum` narrows to the enabled store when only one remains, tool descriptions call out the single enabled target, disabled targets fail before provider writes or approval staging, and `/agent/doctor` no longer reports memory enabled when both stores are off. |
| Cron profile scope | `CronJobEntity`, `CronJobDto`, `CronJobRepository`, `CronJobService`, `HermesCronJobsController`, `CronDashboardController`, `V41__cron_profile_scope.sql` | Cron jobs now persist a `profile`, expose it in API payloads, support profile-aware list/find/create paths, enforce `/p/{profile}` lookup isolation, preserve bare `/api/...` compatibility, and pass non-default profile metadata into background runtime execution. |
| Background cron session reuse | `AgentRuntimeService`, `CronJobService` | Background cron execution can target an explicit attached session or reuse the prior run session for continuity instead of always creating an isolated context. |
| Realtime event core | `EventService`, `EventsController` | Added a bounded canonical event buffer with monotonic cursors, stable event ids, profile-scoped replay, `/api/events`, and `/p/{profile}/api/events`; bare `/api/events` can replay all profiles while profile-prefixed/query-scoped reads fail closed for invalid or unknown profiles. This is the REST/SSE-ready core, not a fake websocket implementation. |
| Realtime events WebSocket | `EventService`, `EventWebSocketHandler`, `EventWebSocketConfig` | `/api/events` and `/p/{profile}/api/events` now also work as WebSocket event streams over the same canonical event buffer: clients can pass `after`/`cursor`, `limit`, and profile scope, receive replayed events, then receive newly published cron/delegate/run events without polling. This is a real delivery stream over Java's event ledger; it deliberately does not fake Hermes `/api/pub` channel sidecar until the dashboard gateway runtime exists. |
| Realtime speak-stream bounded relay | `AudioSpeakStreamWebSocketHandler`, `AudioSpeakStreamWebSocketConfig` | `/api/audio/speak-stream` and `/p/{profile}/api/audio/speak-stream` no longer always return fallback. When TTS is unavailable the handler keeps the Hermes fallback close path; when TTS is available it sends a start frame, buffers client text until `done=true`, splits long text by provider cap, sends binary audio frames from the existing TTS service, then sends `end`. The Java relay declares `mime_type=audio/mpeg`/`encoding=mp3` because the current Java provider returns MP3 bytes, not Hermes raw PCM streaming. |
| WebSocket host/origin guard | `DashboardWebSocketGuard`, `DashboardWebSocketHandshakeInterceptor`, `AudioSpeakStreamWebSocketConfig` | Current dashboard WebSocket routes now validate `Host` and browser `Origin` during handshake using the Hermes DNS-rebinding rules: loopback hosts are accepted for loopback/default bind, explicit public hosts come from `agent.api.cors-origins`, malformed authorities fail closed, wildcard CORS does not trust arbitrary public hosts, and `server.address=0.0.0.0` remains the explicit all-interfaces opt-in. Spring rejects before upgrade with HTTP 403 plus a rejection reason header; Hermes ASGI surfaces the same boundary as WebSocket close `4403`. |
| MCP trust/readOnlyHint gate | `AgentProperties`, `McpToolTrustService`, `DefaultToolGuardrails`, `McpLifecycleManager` | MCP server config now accepts `trust = full|untrusted`, unknown trust values fail closed to`untrusted`, discovery/refresh records each tool's exact`annotations.readOnlyHint == true`, and write-capable tools from untrusted servers route through the existing approval queue before execution. Read-only tools and default/full-trust servers keep the old non-blocking behavior. |
| MCP server/tool filters | `AgentProperties`, `McpLifecycleManager` | MCP client config now honors per-server `enabled=false`, `tools.include`, and `tools.exclude`. Include takes precedence over exclude; `include=[]` is an explicit empty whitelist; exclude supports exact names and simple `*`/`?` globs; filtered-out native tools are not registered into the dynamic registry. |
| MCP result hard cap | `McpLifecycleManager` | Successful MCP text results now have the Hermes hard cap before downstream context/budget handling: results at or below 2,000,000 chars pass through unchanged, while pathological payloads keep a 40% head / 60% tail split with an omitted-character notice. Oversized serialized `structuredContent` is capped the same way. |
| MCP circuit breaker | `McpLifecycleManager` | Native MCP tool handlers now track consecutive server call failures, open a per-server breaker after three failures, short-circuit calls during a 60 second cooldown with a uniform MCP error envelope, allow a half-open probe after cooldown, reset on success, and reopen on probe failure. Successful reconnects also clear the breaker state. |
| MCP tool-call timeout | `AgentProperties`, `McpLifecycleManager` | MCP tool calls now use Hermes timeout precedence: per-server `timeout`, then legacy Java `timeout-seconds`, then global `agent.timeouts.mcp.tool-call`, then the 300 second default. Sync MCP calls run behind a bounded daemon executor, return a uniform timeout error instead of hanging the agent indefinitely, increment the server failure counter, and mark timeout as reconnect-worthy. |
| MCP utility-call timeout | `McpLifecycleManager` | Generated MCP utility tools now share the same bounded timeout boundary as native `call_tool`: `read_resource`, `list_resources`, `list_prompts`, and `get_prompt` no longer bypass the MCP timeout executor and can no longer hang the agent indefinitely on a slow server. Timeout errors keep the same sanitized MCP error envelope. |
| MCP ImageContent media cache | `McpLifecycleManager` | MCP `ImageContent` blocks now follow Hermes' MEDIA contract instead of rendering as placeholder text: valid base64 image bytes are signature-checked, cached under a local MCP media image cache, and returned as `MEDIA:<path>` for downstream multimodal/gateway delivery. Non-image MIME types, malformed payloads, and HTML masquerading as image data are dropped without killing the rest of the tool result. |
| MCP audio/resource media cache | `McpLifecycleManager` | MCP `AudioContent` blocks now cache bounded audio bytes and return `MEDIA:<path>` instead of placeholder text. Embedded binary resources are pre/post size-capped at the Hermes 50 MB resource limit, decoded once, saved under a sanitized local MCP resource cache using the URI's last path segment as a name hint, and rendered with a read-file hint. Malformed base64 now returns an explicit decode message. Mixed text/audio/resource blocks preserve model-facing order. |
| MCP dashboard enabled status | `McpDashboardController`, `McpDashboardControllerTest` | `/api/mcp/servers` now reflects each configured server's real `enabled` flag instead of reporting every configured server as enabled. Config mutation endpoints still remain explicit unsupported gaps until Java gains a durable Hermes-style `config.yaml` writer. |
| Browser screenshot handoff | `BrowserService`, `BrowserVisionTool`, `BrowserToolResponses` | `browser_vision` now returns a structured success payload with `data_url`, `screenshot_path`, `media_tag`, and `mime_type` instead of only a bare base64 data URL. Screenshots are captured through local CDP, saved under the Hermes-compatible screenshot cache, and remain shareable through the existing `MEDIA:<path>` gateway convention. The older `BrowserService.screenshot()` data-url contract remains intact for API vision callers. |
| Browser provider selection guard | `AgentProperties`, `BrowserService` | Java browser config now understands Hermes-style `browser.cloud-provider` selection and fails closed with an actionable unsupported-provider error when configured for cloud/hybrid/browser-use/Camofox modes that Java does not actually implement. `browser.backend` remains a driver/runtime hint and does not accidentally select a cloud provider, matching Hermes strict-selection behavior. |
| Browser URL-safety bypass guards | `BrowserService`, `BrowserToolResponses`, `BrowserServiceUnitTest`, `BrowserToolWrapperTest` | Raw `browser_cdp` can no longer bypass `browser_navigate` URL policy with `Page.navigate` or `Runtime.evaluate` URL literals. Java now preflights CDP navigation URLs, blocks JavaScript expressions that target private/internal/blocked URL literals before CDP I/O, performs a best-effort post-redirect current-page URL check, and treats website-policy blocks as structured browser tool failures. |
| Execute-code mode guard | `ExecuteCodeTool` | `execute_code` now exposes an explicit `mode`/`execution_mode` contract. The existing local Python behavior remains the default and returns `execution_mode=local` plus `kernel_mode=per_call`; `reset=true` is reported as ignored because there is no persistent Java kernel state. Hermes-only `session_kernel` and `remote_rpc` modes, plus unknown modes, fail closed with actionable structured errors instead of silently executing as local code. |
| Gateway message reactions | `SendMessageTool`, `BasePlatformAdapter`, `GatewayRoutingService`, `TelegramAdapter`, `TelegramBotApiClient`, `SendMessageToolTest`, `TelegramAdapterExtraTest`, `TelegramBotApiClientTest` | `send_message(action='react'/'unreact')` no longer returns a blanket Java-port stub. Direct targets with explicit `message_id` route through the gateway reaction capability; Telegram calls the real `setMessageReaction` Bot API shape for emoji reactions and empty-array clears. Platforms without reaction support still fail explicitly, and the Hermes live most-recent-message fallback remains a documented gap until Java has adapter message-id state. |
| Cron/delegate delivery events | `CronJobService`, `DelegateTaskTool`, `DelegatedTaskRunService`, `DelegatedTaskRunEntity`, `DelegatedTaskRunRepository`, `V42__delegated_task_run_delivery_fields.sql`, `V43__delegated_task_run_delivery_claims.sql` | Cron runs now publish `cron.started`, `cron.success`, `cron.no_change`, `cron.failure`, and `cron.timeout` events; delegated async runs publish lifecycle/delivery events. Completed delegate events are self-contained for gateway consumers: payloads include `delegation_id`, `delivery_pending`, `delivery_state`, `result_json`, and parsed `result`, and `restorePendingCompletions` republishes only fresh undelivered, non-dropped, unclaimed-or-stale completed runs after restart. Pending completions older than the Hermes 48h replay cap are terminally dropped instead of being replayed forever, and direct delivery-failure paths converge to dropped after 8 attempts. `delegated_task_runs` persists delivery ack state, target, error, attempts, idempotency key, claim token, claim timestamp, and dropped timestamp; service-level claim/ack/release/drop APIs protect parent-session delivery loops from duplicate delivery and infinite retry. `delegate_task read/status/list` exposes delivery state without exposing internal claim tokens. |
| OpenAI Responses/Runs replay | `ToolCall`, `OpenAiResponsesController`, `OpenAiRunsController` | Responses-style `function_call` input is now accepted, requires `call_id`, canonicalizes legacy/overlong ids to `call_*`, sanitizes function names, and JSON-serializes object/list arguments instead of Java `Map.toString()`. |
| OpenAI external session ids | `OpenAiSessionService`, `ChatCompletionsController`, `OpenAiResponsesController`, `OpenAiRunsController` | OpenAI-compatible chat/responses continuation headers and Runs `session_id` bodies now accept arbitrary Hermes string ids instead of UUID-only input. Non-UUID ids map to deterministic internal UUIDs scoped by session key/default user for persistence, UUID ids retain the previous strict resume semantics, and response headers echo the external string id. |
| File read UX | `ReadFileTool` | Empty files and offset-past-EOF reads now return Hermes-like recovery hints instead of silent dead-end payloads. |
| API auth | `ApiKeyAuthFilter` | `/actuator/healthy` no longer bypasses auth via prefix matching; only exact `/actuator/health` and subpaths are public for GET/HEAD. |
| Runtime status | `RuntimeSettingsController` | `/codex/runtime/status` again reports the legacy model override when the newer model selection is absent. |
| Dashboard routes | `DashboardSystemController`, `FilesystemDashboardControllerTest`, `DashboardSystemControllerTest` | Removed duplicate `/api/git/gh-auth` mapping; route remains covered through filesystem dashboard controller. |
| Curator/reset regressions | `CuratorServiceTest`, `ResetAndBackgroundReviewGateTest` | Tests now assert current structured tool failures and safe per-session reset deletion semantics. |
| Streaming events tests | `AgentStreamingServiceGapTest` | Registered minimal test tool definitions so tool event tests verify streaming behavior instead of failing on setup drift. |
| Terminal/process tests | `ProcessToolBranchTest` | Replaced fixed sleep with process-exit/output-drain wait for less flaky output assertions. |
| CLI slash commands | `SlashCommandRegistry`, `CliFixesTest` | Slash command lookup, aliases, and dynamic skill registration are normalized case-insensitively like Hermes CLI. |
| Telegram batching/commands/media | `PhotoBatchDebouncer`, `UpdateEvent`, `TelegramClient` | Photo captions merge with exact-block dedupe, commands lowercase with `Locale.ROOT`, and voice captions use MarkdownV2 with plain fallback on Telegram parse errors. |

## Hermes Evidence Checked

- Cron parity: `tools/cronjob_tools.py`, `cron/monitor.py`, and Hermes cron monitor/continuity/attach behavior around unchanged-output suppression and background dispatch.
- Delegate async parity: `tools/async_delegation.py`, `tools/process_registry.py`, and `tests/tools/test_async_delegation.py`, especially immediate dispatch, concurrent capacity rejection, durable status, completion event shape, restore of undelivered completions, 48h replay age cap, 8-attempt delivery convergence, delivered ack state, and cancellation.
- Responses/Runs contracts: Hermes OpenAI replay handling and tests around Responses `function_call` items.
- File reads: `tools/file_tools.py`, `tools/file_operations.py`, `tests/tools/test_read_past_eof_note.py`.
- CLI commands: `hermes_cli/commands.py`.
- Telegram captions: `plugins/platforms/telegram/adapter.py`, `tests/gateway/test_telegram_caption_merge.py`, `tests/gateway/test_telegram_voice_caption_markdown.py`.
- Dashboard streaming TTS: `docs/streaming-tts.md` and `tests/hermes_cli/test_web_server_speak_stream.py`, especially the start/binary/end frame order, long-text splitting, and the remaining raw PCM provider contract.
- WebSocket dashboard boundary: `tests/hermes_cli/test_web_server_host_header.py` and `hermes_cli/web_server.py` host/origin helpers around malformed authorities, trusted public hosts, loopback defaults, and cross-site origin rejection.
- MCP trust gating: `tests/tools/test_mcp_trust_gating.py` and `tools/mcp_tool.py` around `trust: untrusted`, exact `readOnlyHint=True`, default full trust, and fail-closed unknown trust values.
- MCP selective loading: `tests/tools/test_mcp_tool.py::TestMCPSelectiveToolLoading` and `tools/mcp_tool.py::_register_server_tools` around `enabled=false`, include precedence, explicit empty include, exclude mode, and glob matching.
- MCP result caps: `tests/tools/test_mcp_result_size_limit.py` and `tools/mcp_tool.py::_truncate_mcp_text_result` around the 2M hard cap, 40/60 split, and ordinary 60K spillover-sized results passing untouched.
- MCP circuit breaker: `tests/tools/test_mcp_circuit_breaker.py` and `tools/mcp_tool.py` around three consecutive failures, 60 second cooldown, half-open probe, reset on success, and re-open on probe failure.
- MCP timeout resolution: `tests/tools/test_mcp_timeout_resolution.py` and `tools/mcp_tool.py::_resolve_tool_timeout` / `_run_on_mcp_loop` around per-server timeout precedence, global `timeouts.mcp.tool_call`, default 300 seconds, native `call_tool` timeout errors, and generated `list_resources`/`read_resource`/`list_prompts`/`get_prompt` utility handlers.
- MCP image media content: `tests/tools/test_mcp_image_content.py` and `tools/mcp_tool.py::_cache_mcp_image_block` around MIME extension mapping, valid image bytes producing `MEDIA:<path>`, and invalid/missing/non-image blocks being ignored.
- MCP resource/audio content: `tests/tools/test_mcp_resource_content.py` and `tools/mcp_tool.py::_cache_mcp_audio_block` / `_render_mcp_resource_block` around `AudioContent` MEDIA tags, embedded PDF materialization, path traversal neutralization, malformed base64 errors, pre-decode size caps, and mixed block ordering.
- MCP dashboard enabled status: `hermes_cli/web_routers/mcp.py::list_mcp_servers` and `set_mcp_server_enabled`, plus `tests/hermes_cli/test_mcp_config.py` around configured `enabled=false` remaining visible and disabled until re-enabled through config.
- Browser screenshot handoff: `tools/browser_tool.py::browser_vision` around persistent screenshot cache paths, `screenshot_path` metadata, and `MEDIA:<screenshot_path>` sharing semantics.
- Browser provider selection: `tests/tools/test_strict_provider_selection.py` and `tools/tool_backend_helpers.py` around `browser.backend` not being the cloud selection, plus `tools/browser_tool.py` cloud provider routing for explicit non-local providers.
- Browser URL-safety bypasses: `tools/browser_tool.py::evaluate_url_safety`, `browser_navigate`, `_expression_targets_private_url`, and `_browser_eval`, plus `tools/browser_use_cli.py::_blocked_url_in_code` around always-blocked metadata URLs, private/internal URL literals in eval/exec paths, website policy enforcement, and post-navigation private-page checks.
- Execute-code runtime modes: `tools/code_execution_tool.py`, `tools/code_kernel.py`, `tests/tools/test_code_execution_modes.py`, and `tests/tools/test_code_kernel.py` around `code_execution.mode = project|strict`, `code_execution.kernel_mode = per-call|session`, remote file-RPC execution for non-local terminal backends, session-kernel reset/state/lifecycle, and per-call defaults.
- Gateway reactions: `tools/send_message_tool.py::_handle_react`, `tests/hermes_cli/test_platform_actions.py`, and `tests/gateway/test_telegram_reactions.py` around `react`/`unreact` actions, live adapter reaction capability, unsupported-platform errors, Telegram `set_message_reaction`, and reaction clearing with no standalone fallback.
- OpenAI external session ids: `tests/agent/transports/test_chat_completions.py` and `tests/agent/transports/test_codex_transport.py` around arbitrary `session_id` values such as `s1`, `cron_job_2026-07-15T10:00:00Z`, `session_alice_1`, and `cron_job42_20260801_090000`.
- Profile-scoped memory/SOUL: `tools/memory_tool.py::get_memory_dir`, `tools/memory_tool.py::get_builtin_memory_store_flags`, `tools/memory_tool.py::_build_memory_schema_overrides`, `tests/agent/test_skip_memory_store_65429.py`, and `tests/agent/test_builtin_memory_disabled_surface.py` around dynamic profile home resolution, `memories/MEMORY.md`/`USER.md` isolation, built-in memory enablement flags, schema target narrowing, and disabled-target write rejection before staged approval.
- Profile-scoped memory dashboard: `tests/hermes_cli/test_dashboard_admin_endpoints.py::TestMemoryEndpoints`, `tools/memory_tool.py::get_memory_dir`, and Hermes profile home scoping around `memories/MEMORY.md` / `USER.md` status and reset operations using the requested profile home instead of a shared default memory store.
- Profile-scoped skills dashboard: `hermes_cli/web_routers/skills.py::get_skills`, `toggle_skill`, `get_skill_content`, `create_skill`, and `update_skill_content`, plus `tests/hermes_cli/test_web_server_skills_profiles.py` and `tests/hermes_cli/test_web_server_skill_editor.py` around per-request profile home scoping, `skills.disabled` writes landing in the requested profile, worker-only skills staying invisible from default scope, and hub actions requiring a real profile-aware subprocess path.
- Profile-scoped toolsets dashboard: `hermes_cli/web_routers/tools.py::get_toolsets`, `toggle_toolset`, `get_toolset_config`, `select_toolset_provider`, `save_toolset_env`, and `run_toolset_post_setup`, plus `hermes_cli/tools_config.py::_get_platform_tools` / `_save_platform_tools` around `_profile_scope(profile)`, `platform_toolsets.<platform>`, config-only `stt.enabled`, `web.backend` / per-capability backend keys, fail-closed unknown toolsets, and real env/post-setup support that Java still reports as unsupported.
- Profile-scoped dashboard status/config/env/preferences: `hermes_cli/web_server.py::get_status`, `get_config`, `update_config`, `get_config_raw`, `update_config_raw`, `get_env`, `set_dashboard_theme`, and `set_dashboard_font`, plus `tests/hermes_cli/test_web_server_profile_unification.py::TestProfileScopedGateway::test_status_reads_requested_profile_home`, `tests/hermes_cli/test_web_server_config_offloop.py`, `test_read_raw_config_readonly.py`, `test_config_read_guard.py`, and `test_redact_config_bridge.py` around `_profile_scope(profile)`, requested-profile `hermes_home` status, raw `config.yaml` path resolution, deep-merge config writes, config-backed dashboard preferences, no event-loop blocking on profile locks, serialized read-modify-write behavior, and no secret leakage through sanitized config responses.
- Profile-scoped model dashboard/listing: `hermes_cli/web_server.py::get_model_info`, `set_model_assignment`, and `_apply_model_assignment_sync`, `gateway/platforms/api_server.py::APIServerAdapter._resolve_model_name`, plus `tests/hermes_cli/test_web_server_profile_unification.py::TestProfileScopedModel` and `tests/gateway/test_api_server.py::TestModelsEndpoint` around `_profile_scope(profile)`, unknown-profile 404 instead of global fallback, main assignment writing only the requested profile config, auxiliary assignment/reset writing profile-local `auxiliary.<slot>`, named-profile `/v1/models` advertising the profile name by default, and omission of cron impact summaries from auxiliary or confirmation-only responses.
- Profile-scoped legacy kanban dashboard: `tools/todo_tool.py::TodoStore`, `todo_tool`, `TODO_SCHEMA`, and Hermes agent todo hydration around session/profile-owned task state instead of one process-global dashboard list. Java's legacy REST kanban surface now mirrors that isolation boundary for named profile dashboard calls while keeping the old default route compatible.
- Profile export/import and metadata writes: `hermes_cli.profiles.export_profile`, `hermes_cli.profiles.import_profile`, `hermes_cli.profiles.write_profile_meta`, `hermes_cli.web_routers.profiles.export_profile_endpoint`, `hermes_cli.web_routers.profiles.import_profile_endpoint`, `tests/hermes_cli/test_profiles.py`, and `tests/hermes_cli/test_profile_export_credentials.py` around `.tar.gz` archive shape, default-root allowlist, credential exclusion, staged-copy secret scrubbing, safe archive member paths, single top-level root validation, symlinked `profile.yaml` preservation, real UTF-8 profile descriptions, and dashboard `{ok, archive}` / `{ok, name, path, desktop}` responses.
- Profile dashboard session aggregation: `hermes_cli/web_routers/profiles.py::get_profiles_sessions`, `apps/desktop/src/api/sessions.ts::listAllProfileSessions`, `apps/desktop/src/types/hermes.ts::PaginatedSessions`, `tests/hermes_cli/test_web_server.py::test_profiles_sessions_positive_limit_still_works`, and `tests/hermes_cli/test_dashboard_param_clamps.py` around 0..500 limit bounds, `profile=all`, `min_messages`, archived/order validation, source/exclude-source filters, row profile tags, and `profile_totals`.
- Profile sidebar session slices: `hermes_cli/web_routers/profiles.py::get_profiles_sessions_sidebar`, `apps/desktop/src/api/sessions.ts::listSidebarSessions`, and `tests/hermes_cli/test_profiles_sidebar_scope.py` around one profile scope for recents/cron/messaging, caller-owned source taxonomy, `min_messages=1`, pinned row backfill, recents truncation, and per-profile usage totals beyond the returned window.
- Profile project tree: `hermes_cli/web_routers/profiles.py::get_profiles_projects_tree`, `apps/desktop/src/app/chat/sidebar/projects/workspace-groups.ts`, and `tests/hermes_cli/test_profiles_sidebar_scope.py` around the `__no_project__` Home bucket, cross-profile row tagging, scoped session ids, project session counts, and the remaining requirement for persisted `cwd`/project DB data before Java can build real repo/worktree lanes.
- Profile PR scan recovery: `hermes_cli/web_routers/profiles.py::_pr_url_from_tool_output`, `hermes_cli/web_routers/profiles.py::post_profiles_sessions_pull_requests`, and desktop session PR scan callers around transcript-only recovery, strict whole-output GitHub PR URL matching, request-id dedupe, no network dependency, and later `gh pr create` tool outputs winning over earlier outputs.
- Profile terminal launch: `hermes_cli/web_routers/profiles.py::open_profile_terminal_endpoint` around using the same setup command returned by `/api/profiles/{name}/setup-command`, platform-specific terminal dispatch, HTTP 400 when no supported terminal emulator exists, HTTP 404 for missing profile setup, and `{ok, command}` success shape.
- Session import: `hermes_cli/web_routers/sessions.py::import_sessions_endpoint`, `_import_sessions_for_profile`, and `tests/hermes_cli/test_web_server.py::test_import_sessions_endpoint_imports_exported_json` around dashboard-safe JSON session import, arbitrary string ids, duplicate `skipped_ids`, persisted message rows, and structured `detail.errors` for invalid rows.
- Session prune: `hermes_cli/web_routers/sessions.py::prune_sessions_endpoint`, `hermes_cli/web_server.py::_prune_sessions`, and `hermes_state.py::SessionDB.prune_sessions` around ended-only deletion, attribute-filter suppression of the implicit 90-day cutoff, explicit age/window filters, dry-run summaries, skipped-open counts, pinned-session protection, child orphaning, and the larger set of Hermes filters that still require missing Java persisted fields before full parity.
- Session owner backfill: `hermes_cli/web_routers/sessions.py::backfill_session_owner_profiles`, `hermes_state.py::SessionDB.backfill_null_session_profiles`, and `tests/hermes_cli/test_session_owner_backfill.py` around idempotent legacy stamping of `NULL`/empty owner rows with the serving profile and never overwriting non-blank owner values.
- Delegate/browser/MCP/profile/audio backlog evidence from Hermes tool modules and focused tests listed below.

## Regression Commands

The local machine did not have Java on `PATH`, so verification used the portable JDK at `C:\git\azhukov\sdlc\.jdks\temurin-25`:

```powershell
$env:JAVA_HOME='C:\git\azhukov\sdlc\.jdks\temurin-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

Green focused suites:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.cron.CronJobToolBranchTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.service.AgentRuntimeServiceTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.api.mapper.CronJobDtoMapperTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.OpenAiRunsControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.core.security.SlidingWindowRateLimiterTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.OpenAiResponsesControllerTest --tests com.azhukov.agent.tools.file.* --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --tests com.azhukov.agent.api.filter.ApiKeyAuthFilterTest --tests com.azhukov.agent.api.DashboardSystemControllerTest --tests com.azhukov.agent.api.FilesystemDashboardControllerTest --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.service.AgentStreamingServiceGapTest --tests com.azhukov.agent.tools.terminal.ProcessToolBranchTest --tests com.azhukov.agent.tools.terminal.TerminalToolTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :cli:test --tests com.azhukov.agent.cli.CliFixesTest --tests com.azhukov.agent.cli.SlashCommandRegistryTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :telegram-bot:test --tests com.azhukov.agent.bot.batch.PhotoBatchDebouncerTest --tests com.azhukov.agent.bot.polling.UpdateEventTest --tests com.azhukov.agent.bot.client.TelegramClientMediaDeliveryTest --tests com.azhukov.agent.bot.client.TelegramClientTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.service.CronJobServiceConcurrencyTest --tests com.azhukov.agent.tools.cron.CronJobToolBranchTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.config.FlywayConfigTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.api.mapper.CronJobDtoMapperTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.CronJobServiceTest --max-workers=1 --no-daemon --console=plain
```

Full all-module suite, green twice after the final stabilization fixes:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

- Pass 1: `BUILD SUCCESSFUL in 7m 20s`.
- Pass 2: `BUILD SUCCESSFUL in 7m 12s`.
- Note: an intervening rerun failed during `:telegram-bot:jacocoTestReport` because Windows reported `Недостаточно места на диске` while copying/writing Gradle cache files; no tests failed in that run. A targeted retry of `.\gradlew.bat :telegram-bot:jacocoTestReport --max-workers=1 --no-daemon --console=plain` succeeded in `1m 39s`.

Latest post-profile-scope all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 6m 34s`.

Latest post-event/delivery focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.CronJobServiceTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 39s`.

Latest post-delegate-delivery-envelope focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 39s`.

Latest post-delegate-delivery-envelope touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.service.CronJobServiceConcurrencyTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 38s`.

Latest post-delegate-delivery-claim focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --tests com.azhukov.agent.config.FlywayMigrationTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 40s`.

Latest post-delegate-delivery-claim touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.service.CronJobServiceConcurrencyTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 34s`.

Latest post-delegate-replay-age-cap focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --tests com.azhukov.agent.config.FlywayMigrationTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 34s`; `BUILD SUCCESSFUL in 28s`.

Latest post-delegate-replay-age-cap touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.service.CronJobServiceConcurrencyTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 30s`.

Latest post-delegate-schema-honesty focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.AgentToolAnnotationParityTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 32s`.

Event edge-case rerun after tightening literal `all` profile scope:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 52s`.

Latest post-event/delivery combined touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.api.mapper.CronJobDtoMapperTest --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 40s`.

Latest post-event/delivery all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Pass 1: `BUILD SUCCESSFUL in 6m 57s`.
Pass 2, after the final event scope edge-case fix: `BUILD SUCCESSFUL in 6m 47s`.

Latest post-speak-stream focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.AudioSpeakStreamWebSocketHandlerTest --tests com.azhukov.agent.api.AudioSpeakStreamWebSocketConfigTest --tests com.azhukov.agent.api.AudioDashboardControllerTest --tests com.azhukov.agent.service.tts.TtsServiceBranchTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 36s`.

Latest post-speak-stream media/provider regression suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.Audio* --tests com.azhukov.agent.service.tts.* --tests com.azhukov.agent.tools.tts.* --tests com.azhukov.agent.service.imagegen.* --tests com.azhukov.agent.tools.imagegen.* --tests com.azhukov.agent.api.VisionControllerTest --tests com.azhukov.agent.tools.vision.* --tests com.azhukov.agent.service.ImageShrinkerServiceTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 30s`.

Latest post-websocket-guard focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.DashboardWebSocketGuardTest --tests com.azhukov.agent.api.DashboardWebSocketHandshakeInterceptorTest --tests com.azhukov.agent.api.AudioSpeakStreamWebSocketConfigTest --tests com.azhukov.agent.api.AudioSpeakStreamWebSocketHandlerTest --tests com.azhukov.agent.api.filter.ApiCorsFilterTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 2m 10s`.

Latest post-events-websocket focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --tests com.azhukov.agent.api.EventWebSocketConfigTest --tests com.azhukov.agent.api.DashboardWebSocketGuardTest --tests com.azhukov.agent.api.DashboardWebSocketHandshakeInterceptorTest --tests com.azhukov.agent.api.AudioSpeakStreamWebSocketConfigTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 52s`.

Latest post-events-websocket combined touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.service.EventServiceTest --tests com.azhukov.agent.api.EventsControllerTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --tests com.azhukov.agent.api.EventWebSocketConfigTest --tests com.azhukov.agent.service.DelegatedTaskRunServiceTest --tests com.azhukov.agent.tools.delegate.DelegateTaskToolTest --tests com.azhukov.agent.api.DashboardWebSocketGuardTest --tests com.azhukov.agent.api.DashboardWebSocketHandshakeInterceptorTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 34s`.

Targeted Spring context rerun after event websocket constructor wiring fix:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.config.AgentConfigProfilesTest --tests com.azhukov.agent.api.EventWebSocketHandlerTest --tests com.azhukov.agent.api.EventWebSocketConfigTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 54s`.

Latest post-events-websocket all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result after fixing `EventWebSocketHandler` constructor autowiring: `BUILD SUCCESSFUL in 6m 36s`.

Latest post-websocket-guard all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 7m 21s`.

Latest post-profile-memory/SOUL focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.tools.memory.MemoryTodoBranchTest --tests com.azhukov.agent.core.prompt.MemoryUserPrefixInjectionTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 32s`.

Latest post-profile-memory/SOUL all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 6m 46s`.

Latest post-profile-memory-dashboard focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 43s`.

Latest post-profile-memory-dashboard context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 46s`.

Latest post-profile-skills-dashboard focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.SkillsDashboardControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 37s`.

Latest post-profile-skills-dashboard context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.SkillsDashboardControllerTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 51s`.

Latest post-profile-dashboard combined touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.api.SkillsDashboardControllerTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 51s`.

Latest post-profile-toolsets-dashboard focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.ToolsetsControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 44s`.

Latest post-profile-toolsets-dashboard combined touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.api.SkillsDashboardControllerTest --tests com.azhukov.agent.api.ToolsetsControllerTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 54s`.

Latest post-profile-kanban focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.KanbanControllerProfileTest --tests com.azhukov.agent.service.TodoServiceTest --tests com.azhukov.agent.api.AgentControllerTest --tests com.azhukov.agent.api.AgentControllerBranchCoverageTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 48s`.

Latest post-profile-kanban combined touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.api.SkillsDashboardControllerTest --tests com.azhukov.agent.api.ToolsetsControllerTest --tests com.azhukov.agent.api.KanbanControllerProfileTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.service.TodoServiceTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 56s`.

Latest post-profile-config focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.DashboardSystemControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 33s`.

Latest post-profile-config combined profile/dashboard backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.DashboardSystemControllerTest --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.api.SkillsDashboardControllerTest --tests com.azhukov.agent.api.ToolsetsControllerTest --tests com.azhukov.agent.api.KanbanControllerProfileTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.service.TodoServiceTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 4s`.

Latest post-profile-dashboard-preferences focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.DashboardSystemControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 29s`.

Latest post-profile-dashboard-preferences combined profile/dashboard backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.DashboardSystemControllerTest --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.api.SkillsDashboardControllerTest --tests com.azhukov.agent.api.ToolsetsControllerTest --tests com.azhukov.agent.api.KanbanControllerProfileTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.service.TodoServiceTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 15s`.

Latest post-profile-status focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.DashboardSystemControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 40s`.

Latest post-profile-status combined profile/dashboard backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.DashboardSystemControllerTest --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.api.SkillsDashboardControllerTest --tests com.azhukov.agent.api.ToolsetsControllerTest --tests com.azhukov.agent.api.KanbanControllerProfileTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.service.TodoServiceTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 54s`.

Latest post-profile-model focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.ModelsControllerTest --tests com.azhukov.agent.api.ModelOptionsControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 51s`.

Latest post-profile-model combined profile/dashboard backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.DashboardSystemControllerTest --tests com.azhukov.agent.api.ModelsControllerTest --tests com.azhukov.agent.api.ModelOptionsControllerTest --tests com.azhukov.agent.api.MemoryDashboardControllerTest --tests com.azhukov.agent.api.SkillsDashboardControllerTest --tests com.azhukov.agent.api.ToolsetsControllerTest --tests com.azhukov.agent.api.KanbanControllerProfileTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.service.TodoServiceTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 57s`.

Latest post-profile-model all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Pass 1, after backend test fork cadence was raised to `forkEvery = 750`: `BUILD SUCCESSFUL in 6m 40s`.
Pass 2: `BUILD SUCCESSFUL in 7m 6s`.

Latest post-cron-model-snapshots focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.ModelOptionsControllerTest --tests com.azhukov.agent.api.mapper.CronJobDtoMapperTest --max-workers=1 --no-daemon --console=plain
```

Result after fixing the empty-fixture regression: `BUILD SUCCESSFUL in 1m 3s`.

Latest post-cron-model-snapshots wiring/backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ModelOptionsControllerTest --tests com.azhukov.agent.api.ModelsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.tools.cron.CronJobToolBranchTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 50s`.

Latest post-cron-model-drift-guard focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.api.ModelOptionsControllerTest --tests com.azhukov.agent.api.mapper.CronJobDtoMapperTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 2m 14s`.

Latest post-cron-model-drift-guard wiring/backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ModelOptionsControllerTest --tests com.azhukov.agent.api.ModelsControllerTest --tests com.azhukov.agent.api.CronDashboardControllerTest --tests com.azhukov.agent.api.HermesCronJobsControllerTest --tests com.azhukov.agent.service.CronJobServiceTest --tests com.azhukov.agent.tools.cron.CronJobToolBranchTest --tests com.azhukov.agent.api.mapper.CronJobDtoMapperTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 12s`.

Latest pre-push all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 6m 53s`.

Latest post-built-in-memory-enable focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.config.AgentPropertiesTest --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.MemoryUserPrefixInjectionTest --tests com.azhukov.agent.api.AgentControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 2m 41s`.

Latest post-profile-export-import focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.config.AgentPropertiesTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 55s`.

Latest post-profile-export-import combined profile/memory backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.config.AgentPropertiesTest --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.MemoryUserPrefixInjectionTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --tests com.azhukov.agent.api.AgentControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 41s`.

Latest post-profile-symlink-write focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.config.AgentPropertiesTest --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.MemoryUserPrefixInjectionTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --tests com.azhukov.agent.api.AgentControllerTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 32s`; `BUILD SUCCESSFUL in 39s`.

Latest post-profile-unicode-write focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.ProfileServiceTest --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.config.AgentPropertiesTest --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --tests com.azhukov.agent.tools.memory.MemoryToolTest --tests com.azhukov.agent.core.prompt.MemoryUserPrefixInjectionTest --tests com.azhukov.agent.core.prompt.DefaultPromptBuilderTest --tests com.azhukov.agent.api.AgentControllerTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 31s`; `BUILD SUCCESSFUL in 33s`.

Latest post-profile-dashboard-session-aggregation focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 45s`; `BUILD SUCCESSFUL in 32s`; `BUILD SUCCESSFUL in 51s`.

Latest post-profile-sidebar-session-slices focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 45s`; `BUILD SUCCESSFUL in 54s`.

Latest post-profile-project-tree-Home-bucket focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 1m 39s`; `BUILD SUCCESSFUL in 1m 17s`.

Latest post-profile-PR-scan-recovery focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 40s`; `BUILD SUCCESSFUL in 43s`.

Latest post-profile-terminal-launch focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 32s`; `BUILD SUCCESSFUL in 45s`.

Latest post-session-import focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.SessionCrudControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 43s`; `BUILD SUCCESSFUL in 47s`.

Latest post-session-prune focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.SessionCrudControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 56s`; `BUILD SUCCESSFUL in 48s`.

Latest post-session-owner-backfill focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.SessionCrudControllerTest --max-workers=1 --no-daemon --console=plain
.\gradlew.bat :backend:test --tests com.azhukov.agent.JavaAgentApplicationTests --tests com.azhukov.agent.api.ProfilesDashboardControllerTest --tests com.azhukov.agent.api.SessionCrudControllerTest --tests com.azhukov.agent.service.ProfileServiceTest --max-workers=1 --no-daemon --console=plain
```

Results: `BUILD SUCCESSFUL in 40s`; `BUILD SUCCESSFUL in 49s`.

Latest post-gateway-reaction focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.gateway.SendMessageToolTest --tests com.azhukov.agent.gateway.telegram.TelegramAdapterTest --tests com.azhukov.agent.gateway.telegram.TelegramAdapterExtraTest --tests com.azhukov.agent.gateway.telegram.TelegramBotApiClientTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 52s`.

Latest post-gateway-reaction touched-area suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.gateway.SendMessageToolTest --tests com.azhukov.agent.gateway.telegram.TelegramAdapterTest --tests com.azhukov.agent.gateway.telegram.TelegramAdapterExtraTest --tests com.azhukov.agent.gateway.telegram.TelegramBotApiClientTest :telegram-bot:test --tests com.azhukov.agent.bot.core.ReactionManagerTest --tests com.azhukov.agent.bot.client.TelegramClientTest --tests com.azhukov.agent.bot.client.TelegramClientMediaDeliveryTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 34s`.

Latest post-MCP-dashboard-enabled-status focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.McpDashboardControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 43s`.

Latest post-MCP-dashboard-enabled-status touched-area backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.McpDashboardControllerTest --tests com.azhukov.agent.client.mcp.McpLifecycleManagerNewFeaturesTest --tests com.azhukov.agent.client.mcp.McpToolHandlerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 28s`.

Latest post-MCP-trust focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.core.security.McpToolTrustServiceTest --tests com.azhukov.agent.core.security.DefaultToolGuardrailsTest --tests com.azhukov.agent.core.security.ApprovalProducerWiringTest --tests com.azhukov.agent.client.mcp.McpLifecycleManagerNewFeaturesTest --tests com.azhukov.agent.client.mcp.McpToolHandlerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 29s`.

Latest post-MCP-trust context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.client.mcp.* --tests com.azhukov.agent.config.AgentConfigNoDuplicateBeanTest --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 9s`.

Latest post-MCP-filter context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.client.mcp.* --tests com.azhukov.agent.core.security.McpToolTrustServiceTest --tests com.azhukov.agent.core.security.DefaultToolGuardrailsTest --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 54s`.

Latest post-MCP-result-cap context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.client.mcp.McpLifecycleManagerNewFeaturesTest --tests com.azhukov.agent.client.mcp.McpToolHandlerTest --tests com.azhukov.agent.client.mcp.* --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 25s`.

Latest post-MCP-circuit-breaker context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.client.mcp.McpToolHandlerTest --tests com.azhukov.agent.client.mcp.McpLifecycleManagerNewFeaturesTest --tests com.azhukov.agent.client.mcp.McpLifecycleManagerReconnectTest --tests com.azhukov.agent.client.mcp.* --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 14s`.

Latest post-MCP-timeout context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.client.mcp.McpToolHandlerTest --tests com.azhukov.agent.client.mcp.McpLifecycleManagerNewFeaturesTest --tests com.azhukov.agent.client.mcp.* --tests com.azhukov.agent.config.AgentPropertiesTest --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 33s`.

Latest post-MCP-image-media context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.client.mcp.McpLifecycleManagerNewFeaturesTest --tests com.azhukov.agent.client.mcp.McpToolHandlerTest --tests com.azhukov.agent.client.mcp.* --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 10s`.

Latest post-MCP-resource-audio context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.client.mcp.McpLifecycleManagerNewFeaturesTest --tests com.azhukov.agent.client.mcp.McpToolHandlerTest --tests com.azhukov.agent.client.mcp.* --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 25s`.

Latest post-MCP-utility-timeout context backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.client.mcp.McpLifecycleManagerNewFeaturesTest --tests com.azhukov.agent.client.mcp.McpToolHandlerTest --tests com.azhukov.agent.client.mcp.* --tests com.azhukov.agent.config.AgentPropertiesTest --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 25s`.

Latest post-MCP-media-and-timeout all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 7m 43s`.

Latest post-browser-screenshot-handoff focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.browser.BrowserServiceUnitTest --tests com.azhukov.agent.tools.browser.BrowserServiceBranchTest --tests com.azhukov.agent.tools.browser.BrowserToolWrapperTest --tests com.azhukov.agent.tools.browser.BrowserServiceTest --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --tests com.azhukov.agent.api.VisionControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 52s`.

Latest post-browser-provider-guard focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.browser.BrowserServiceUnitTest --tests com.azhukov.agent.tools.browser.BrowserServiceBranchTest --tests com.azhukov.agent.tools.browser.BrowserToolWrapperTest --tests com.azhukov.agent.tools.browser.BrowserServiceTest --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --tests com.azhukov.agent.config.AgentPropertiesTest --tests com.azhukov.agent.api.VisionControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 11s`.

Latest post-execute-code-mode-guard focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.code.ExecuteCodeToolUnitTest --tests com.azhukov.agent.tools.AgentToolAnnotationParityTest --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --tests com.azhukov.agent.JavaAgentApplicationTests --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 54s`.

Latest post-OpenAI-external-session-ids focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.service.OpenAiSessionServiceTest --tests com.azhukov.agent.api.OpenAiResponsesControllerTest --tests com.azhukov.agent.api.OpenAiRunsControllerTest --tests com.azhukov.agent.api.ChatCompletionsControllerTest --tests com.azhukov.agent.api.ChatCompletionsControllerStreamingTest --tests com.azhukov.agent.service.OpenAiResponseStoreTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 33s`.

Focused rerun after fixing an old async Runs verification that surfaced during the wide suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.api.OpenAiRunsControllerTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 42s`.

Latest post-OpenAI-external-session-ids all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 6m 24s`.

Latest post-browser-URL-safety focused backend suite:

```powershell
.\gradlew.bat :backend:test --tests com.azhukov.agent.tools.browser.BrowserServiceUnitTest --tests com.azhukov.agent.tools.browser.BrowserServiceBranchTest --tests com.azhukov.agent.tools.browser.BrowserToolWrapperTest --tests com.azhukov.agent.core.security.DefaultUrlSafetyTest --tests com.azhukov.agent.core.security.CdpUrlValidationTest --tests com.azhukov.agent.api.VisionControllerTest --tests com.azhukov.agent.core.tool.SpringToolRegistryTest --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 1m 2s`.

Latest post-browser-URL-safety all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 7m 12s`.

Latest post-browser-and-execute-code all-module non-live suite:

```powershell
.\gradlew.bat :backend:test :cli:test :telegram-bot:test --max-workers=1 --no-daemon --console=plain
```

Result: `BUILD SUCCESSFUL in 8m 40s`.

Targeted Postgres migration smoke:

```powershell
.\gradlew.bat :backend:slowTest --tests com.azhukov.agent.config.FlywayMigrationTest --max-workers=1 --no-daemon --console=plain
```

Result: blocked before migration execution because Testcontainers could not find a valid Docker environment (`DockerClientProviderStrategy`). The test source now asserts V42/V43/V44 and the cron/session/delegated delivery columns, but this local host did not provide a runnable Docker provider for verification.

Repository hygiene:

```powershell
git diff --check
```

Result: exit code 0. Git emitted existing line-ending normalization warnings (`LF will be replaced by CRLF`) but no whitespace errors.

Latest untracked text-file trailing whitespace scan:

```powershell
git ls-files --others --exclude-standard
```

Result after the latest pre-push release-gate scan: 133 untracked files; no trailing whitespace found in scanned untracked text files.

Latest non-test secret-pattern scan:

```powershell
rg -n --hidden -S "(BEGIN (RSA|DSA|EC|OPENSSH) PRIVATE KEY|OPENAI_API_KEY=|TELEGRAM_BOT_TOKEN=|GITHUB_TOKEN=|xox[baprs]-|AIza[0-9A-Za-z\-_]{35}|sk-[A-Za-z0-9]{20,})" --glob "!**/build/**" --glob "!**/.gradle/**" --glob "!**/.git/**" --glob "!**/src/test/**" --glob "!**/*Test.java" .
```

Result: no matches outside test fixtures.

Latest profile-scope batch release manifest snapshot:

| Package | Count | Notes |
|---------|-------|-------|
| `cron+delegate` | 24 | Cron/delegate production, tests, event publishing, and durable ledger files. |
| `OpenAI/dashboard/profile` | 181 | Dashboard, OpenAI-compatible API, profiles, sessions, model/runtime, event API, browser/MCP/media, and related tests. |
| `CLI/Telegram` | 16 | CLI slash registry and Telegram batching/media/polling files. |
| `migrations` | 12 | Common Flyway migrations V33-V36, V38-V44 and Postgres V37. |
| `unrelated_or_shared` | 178 | Shared core/tool/security/persistence/config/doc files that need owner review before packaging. |

## Large Backlog Gaps Not Faked

1. Realtime dashboard/websocket gateway.
   - Hermes evidence: `/api/console`, `/api/pty`, `/api/events`, `/api/audio/speak-stream`, host/origin guard tests such as `tests/hermes_cli/test_web_server_console_ws.py`, `test_web_server_pty_reconnect.py`, `test_web_server_speak_stream.py`, `test_web_server_host_header.py`.
   - Java status: REST/SSE-ready `EventService` + `/api/events` profile-scoped replay core is implemented, and `/api/events` now also streams replay/live events over WebSocket. `/api/audio/speak-stream` has a bounded real relay over the existing Java TTS service instead of a permanent fallback stub. Current dashboard WebSocket routes have a Spring handshake Host/Origin guard. `/api/console` is a documented gap, not a bounded stub candidate: Hermes ties it to `HermesConsoleEngine`, confirmation state, cancelable command tasks, and WS auth/close semantics. The full websocket/reconnect/protocol contract, `/api/pub` sidecar channel fan-out, PTY session cursoring, `/api/console`, `/api/pty`, WebSocket auth/token close semantics, and Hermes raw PCM streaming provider contract are still open.

2. Profile isolation.
   - Hermes evidence: `hermes_cli/active_sessions.py`, `tests/docker/test_profile_gateway.py`, `test_container_restart.py`, `tests/cron/test_cron_bot_chat_delivery.py`.
   - Java status: file-backed `ProfileService`, dashboard profile mutations, symlink-safe and Unicode-safe metadata writes, profile export/import archives, dashboard session aggregation with profile totals, batched sidebar session slices, a real `Home` project-tree bucket over current session rows, transcript-backed PR recovery for bare `gh pr create` output URLs, dashboard profile terminal launch, dashboard-safe session import with stable Java UUID mapping for Hermes string ids, bounded session prune over persisted Java fields, session profile persistence/guards, cron REST/dashboard profile scope, profile-scoped memory keys, prompt memory injection, named-profile `SOUL.md` loading, built-in memory enablement/schema flags, named-profile dashboard status/config/raw/env read boundaries, profile-scoped dashboard theme/font writes, named-profile model info/options/main/auxiliary config writes, `/p/{profile}/v1/models` profile-name advertisement, and profile-scoped cron model-impact summaries backed by real snapshot fields plus runtime drift guard are now implemented. Remaining gaps are persisted `cwd`/`git_repo_root` session metadata for true project/repo/worktree grouping, plus session prune/export full-fidelity file cleanup and unsupported prune filters requiring missing persisted fields, real profile-scoped gateway/platform health state, profile-specific skill managers/tool registry hot reload, full Hermes model picker provider catalogs, expensive-model pricing confirmation, platform delivery, and worker lifecycle.

3. Delegate async orchestration beyond bounded ledger.
   - Hermes evidence: `tools/delegate_tool.py` async/background behavior and `tests/tools/test_async_delegation.py`.
   - Java status: bounded durable dispatch/status/read/cancel parity, atomic capacity acceptance, self-contained completion events, pending-completion restore publishing with the 48h replay age cap, delivery state/events, 8-attempt delivery convergence, service-level claim/ack/release/drop, and honest model-facing schema text are implemented. Remaining gap is the richer Hermes gateway reinjection/coalescing and progress-based stalled-monitor consumer loop over those claims; it should be built on the same `delegated_task_runs` delivery ledger rather than faked in the tool response.

4. Browser cloud/hybrid control.
   - Hermes evidence: `tools/browser_extension_router.py`, `tools/browser_use_cli.py`, CDP/router/browser-control tests.
   - Java status: browser/CDP tools exist, `browser_vision` now returns `screenshot_path`/`MEDIA:<path>` metadata for multimodal/gateway handoff while preserving the legacy `BrowserService.screenshot()` data URL for API vision, explicit non-local `browser.cloud-provider` values fail closed instead of silently falling back to local CDP, and raw CDP/eval URL-safety bypasses are guarded. Still open: real extension-control/cloud/hybrid routing, Camofox/browser-use runtime mode, and the provider-specific private-network guard parity needed once non-local browser providers actually exist.

5. MCP lifecycle depth.
    - Hermes evidence: tests/docs for lazy schema cache, discovery lock, circuit breaker, trust gating, include/exclude, sampling/elicitation, media caching, caps, and watchdogs.
    - Java status: `McpLifecycleManager`, `McpServerService`, and `McpTool` provide a base; utility capability gating, untrusted-server readOnlyHint approval gating, per-server `enabled=false`, native tool include/exclude filters, hard caps for pathological text/structured results, per-server circuit breaker short-circuiting, bounded native and generated-utility call timeout resolution, `ImageContent` MEDIA caching, `AudioContent` MEDIA caching, and embedded binary resource materialization/caps are now implemented. Remaining gaps are lazy persisted schema cache parity, dashboard writes for MCP config/catalog/OAuth, sampling/elicitation/media result handling beyond these model-facing content blocks, POSIX-style stdio parent-death watchdogs, and richer in-flight RPC teardown semantics.

6. Audio/image/vision provider ecosystem.
   - Hermes evidence: provider/routing tests for relay/client-direct, Groq, ElevenLabs, OpenAI, xAI, FAL, Krea, upscale/edit/video, TTS streaming.
   - Java status: limited OpenAI TTS/STT/imagegen providers plus a WebSocket speak-stream relay over the current sync TTS service. There is still no full provider matrix, credential resolver parity, or chunked raw PCM streaming provider contract.

7. Execute-code runtime protocol.
   - Hermes evidence: RPC/session-kernel/code-execution mode tests.
   - Java status: local Python-oriented `ExecuteCodeTool` remains the only implemented runtime, and it now advertises/returns `execution_mode=local` and `kernel_mode=per_call`. Unsupported `session_kernel` and `remote_rpc` requests fail closed with actionable structured errors instead of being silently run as local per-call scripts. Remaining gap is the real Hermes remote file-RPC and session-persistent kernel protocol.

8. OpenAI rich approvals/session model.
   - Java now closes bounded `function_call` replay/id/name/argument gaps and arbitrary string session ids for OpenAI-compatible chat/responses/runs. Remaining gaps are richer approval scopes, cancellation/interruption parity, and the full Responses/Runs runtime protocol.

9. CLI and Telegram remaining gateway parity.
   - CLI: file/image-drop attachments, plugin/busy-policy/runtime surface remain thinner than Hermes.
   - Telegram: native relay/media contracts, reaction/prompt ops, DM topics, active-session recovery, background slash behavior, media re-delivery/dedupe, and STT/video/audio document parity remain open.

10. Cron residual delivery/profile depth.
    - The bounded cron tool/service parity for monitor, continuity, attach-to-session, persistence, and background run reporting is implemented.
    - Cron lifecycle events now publish into the shared event core. Remaining gaps are cross-cutting rather than local cron fields: durable gateway delivery loop parity and richer run-result delivery semantics shared with delegate/profile work.
