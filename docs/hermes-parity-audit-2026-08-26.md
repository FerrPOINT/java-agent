# Hermes Parity Audit - 2026-08-26

## Summary
- Confirmed behavioral gaps: {len(gaps)}
- Fix statuses are updated only after code and tests are verified.

## Findings
### P1: Seam 1 [CRITICAL]
- **Hermes:** `agent/conversation_loop.py:3892` - Appends the truncated assistant fragment and a marked continuation user nudge to the live transcript before the next model call, then removes scaffolding or persists the stitched partial at the retry ceiling.
- **Java:** `core/agent/DefaultAgentRuntime.java:665` - Builds `lengthContext` containing the fragment and nudge, but never assigns it to `turnMessages`; the next loop rebuilds context from the unchanged transcript. It therefore repeats the same request up to four times and stitches responses that were not continuations.
- **Impact:** LENGTH recovery does not supply either the continuation point or instruction to the model, causing repeated output, unrelated stitched text, and failed recovery of truncated answers.

### P2: Seam 2 [CRITICAL]
- **Hermes:** `agent/conversation_loop.py:2740` - After compaction, replaces the active transcript and updates the conversation-history/persistence state so subsequent model calls use the compacted history rather than the dropped messages.
- **Java:** `core/agent/DefaultAgentRuntime.java:1195; core/context/DefaultContextEngine.java:171` - The post-tool proactive path replaces only in-memory `turnMessages` and does not persist or otherwise suppress the already persisted original rows. The next `prepareContext` reloads those full database rows and then appends the compressed `turnMessages` tail.
- **Impact:** The intended compaction can fail to reduce the next request and can duplicate the current turn (original history plus compressed tail), worsening context pressure and potentially triggering overflow.

### P3: Seam 5 - Cron scheduler [CRITICAL]
- **Hermes:** `cron/scheduler.py:6893; cron/scheduler.py:6973` - Saves the run result and actively routes the final response or failure alert through resolved origin/platform/bot-chat delivery targets.
- **Java:** `service/CronJobService.java:470; service/CronJobService.java:770` - Stores deliverTo and logs it, but CronJobService never invokes a transport or gateway delivery API; the noAgent path explicitly says delivery is only logged.
- **Impact:** Java scheduled jobs produce orphaned session output and users receive no cron notifications regardless of deliverTo.

### P4: Seam 1 [HIGH]
- **Hermes:** `agent/conversation_loop.py:7029` - Uniquifies and repairs tool calls before `_build_assistant_message` and before the assistant tool-call message is persisted, so persisted call IDs/names match tool results.
- **Java:** `core/agent/DefaultAgentRuntime.java:919` - Adds and persists `response.toolCalls()` first, then copies and mutates only the local `toolCalls` list for ID uniquification and name repair.
- **Impact:** Tool results can use repaired names or IDs that do not exist in the persisted assistant tool-call message; replay sanitizers can drop those results and strict providers can reject the next request as an orphaned tool result.

### P5: Seam 1 [HIGH]
- **Hermes:** `agent/conversation_loop.py:7417` - Flushes the assistant tool-call record before side effects and terminates the turn without executing tools if canonical persistence fails.
- **Java:** `core/agent/DefaultAgentRuntime.java:926` - Attempts incremental persistence but, when the callback returns false, merely leaves `persistedUpTo` unchanged and still validates and executes side-effecting tools.
- **Impact:** A crash or retry after persistence failure can rerun destructive tools whose initiating tool-call record was never durably saved.

### P6: Seam 1 [HIGH]
- **Hermes:** `agent/conversation_loop.py:7888; agent/empty_response_guard.py:172` - Records the actual response usage, classifies deterministic empties only when prompt usage is present and generated output plus reasoning tokens are zero, and reduces the retry budget to one for costly empty requests.
- **Java:** `core/agent/DefaultAgentRuntime.java:788; core/agent/EmptyResponseGuard.java:45` - Always records `null` for output usage (`/* usage not on ChatResponse yet */`), so `deterministicEmpty()` can never become true; it also has no cost-aware retry-budget path.
- **Impact:** Java repeats paid full-context empty calls that Hermes intentionally stops after a verified zero-output streak, increasing cost and delaying fallback.

### P7: Seam 1 [HIGH]
- **Hermes:** `agent/tool_executor.py:1589` - Polls concurrent tool futures at bounded intervals, checks the turn interrupt flag, cancels unstarted work, signals running tool threads, and returns without waiting for non-cooperative tools indefinitely.
- **Java:** `core/agent/DefaultAgentRuntime.java:1451` - Calls `CompletableFuture.allOf(...).join()` without checking `InterruptToken`; cancellation is checked only after every parallel future completes.
- **Impact:** A user interrupt during a long parallel-safe tool batch is not honored until all tools finish, including tools that may hang or take minutes.

### P8: Seam 1 [HIGH]
- **Hermes:** `agent/turn_finalizer.py:756` - At turn finalization drains an undelivered mid-turn steer and returns it as `pending_steer`, allowing the caller to deliver it as the next user turn rather than lose it.
- **Java:** `core/agent/DefaultAgentRuntime.java:380` - Unconditionally clears `SteerBuffer` in `runTurnInternal`'s `finally` block after the loop returns, with no equivalent handoff result.
- **Impact:** A steer that arrives after the final model response, or is requeued because no tool result exists, is silently discarded instead of becoming the next user instruction.

### P9: Seam 10 - Tool execution [HIGH]
- **Hermes:** `agent/tool_executor.py:661; agent/tool_executor.py:726` - Every dispatched tool, including one admitted to a concurrent segment, traverses the serialized authorization/pre-tool middleware before execution.
- **Java:** `backend/src/main/java/com/azhukov/agent/core/agent/TurnExecutor.java:603; backend/src/main/java/com/azhukov/agent/core/agent/TurnExecutor.java:661` - Approval requests, waits, and fail-closed approval checks exist only in the sequential branch. If ToolParallelSafety selects the parallel branch, calls go directly to executeToolsInParallel and ToolExecutionService without requiresApproval or ApprovalQueue checks.
- **Impact:** Any tool configured in alwaysRequireApprovalTools that is also admitted as parallel-safe can run without the required user approval.

### P10: Seam 10 - Tool execution [HIGH]
- **Hermes:** `agent/tool_executor.py:708; agent/tool_executor.py:726` - Hermes dispatches each tool once through its execution middleware; it returns a tool error to the model rather than generically retrying the whole invocation.
- **Java:** `backend/src/main/java/com/azhukov/agent/core/tool/ToolExecutionService.java:58; backend/src/main/java/com/azhukov/agent/core/tool/ToolExecutionService.java:91` - ToolExecutionService wraps every registry invocation in a Resilience4j retry that retries RuntimeException up to three times, regardless of whether the tool mutates files, processes, or external systems.
- **Impact:** A mutating tool that completes its side effect and then throws can be executed again automatically, causing duplicate writes, commands, requests, or external actions.

### P11: Seam 10 - Tool execution [HIGH]
- **Hermes:** `agent/tool_executor.py:957; agent/tool_executor.py:966` - On a sequential deadline Hermes cancels the future and signals the worker interrupt; its concurrent path similarly abandons and interrupts timed-out workers.
- **Java:** `backend/src/main/java/com/azhukov/agent/core/tool/ToolExecutionService.java:91; backend/src/main/java/com/azhukov/agent/core/tool/ToolExecutionService.java:94` - Java submits a Callable and immediately waits on the returned Future, but discards that Future. On TimeoutException it returns a failure result without canceling or interrupting the submitted task.
- **Impact:** A timed-out Java tool continues running and can perform late side effects after the model has received a timeout result and moved to a different strategy.

### P12: Seam 10 - Tool execution [HIGH]
- **Hermes:** `tools/delegate_tool.py:3688; tools/delegate_tool.py:4118; tools/delegate_tool.py:4281` - Top-level delegation can dispatch the complete fan-out as a background unit, immediately return live child identifiers/transcript information, and inject one completion event back into the parent session later.
- **Java:** `backend/src/main/java/com/azhukov/agent/tools/delegate/DelegateTaskTool.java:219; backend/src/main/java/com/azhukov/agent/tools/delegate/DelegateTaskTool.java:227; backend/src/main/java/com/azhukov/agent/tools/delegate/DelegateTaskTool.java:865` - DelegateArgs has no background field. execute waits for a single child with Future.get or waits through every batch Future before returning the consolidated result, despite its tool description saying dispatch returns immediately.
- **Impact:** Delegation blocks the parent agent turn and prevents it from continuing independent work, steering its own active session, or receiving asynchronous child completion behavior.

### P13: Seam 2 [HIGH]
- **Hermes:** `agent/context_compressor.py:3044; agent/context_compressor.py:3104` - Uses configured threshold percent (default 50%), longest-match per-model overrides, and the small-context 75% raise-only floor; large-context models therefore retain the configured/default 50% trigger.
- **Java:** `core/context/DefaultContextEngine.java:241; core/context/CompressionPolicy.java:197` - Preflight is hard-coded to 75% and `updateModel()` invokes the compressor overload with neither the model nor configured threshold map. The policy's default is also 75%.
- **Impact:** Java delays compression by roughly 25% of a large model's window and ignores per-model compression configuration, allowing avoidable context overflows and changing when summaries are generated.

### P14: Seam 2 [HIGH]
- **Hermes:** `agent/context_compressor.py:6314` - Computes the protected tail by walking backward under a token budget derived from the context threshold/summary ratio, with bounded message-count protection only as a floor and tool-group/user-message boundary repair.
- **Java:** `core/context/DefaultContextCompressor.java:382` - Always protects only a fixed 3–8 trailing messages before summarizing everything earlier, regardless of their aggregate token size or the model context window.
- **Impact:** Java can summarize away a large amount of recent, still-useful context that Hermes keeps verbatim; conversely its retained tail behavior does not scale with model context capacity.

### P15: Seam 2 [HIGH]
- **Hermes:** `agent/context_compressor.py:7863` - Chooses an alternation-safe user or assistant summary carrier, merges into the tail when neither standalone role is safe, and ensures a nonempty user message survives for providers that require one.
- **Java:** `core/context/DefaultContextCompressor.java:525; client/langchain4j/LangChain4jMessageMapper.java:31` - Always inserts a standalone summary as a `SYSTEM` message in the middle of history; the wire mapper sends every such message as a provider system message.
- **Impact:** The summary becomes privileged system content rather than historical conversational context and can create multiple/mid-history system messages that strict providers reject or interpret differently.

### P16: Seam 2 [HIGH]
- **Hermes:** `agent/context_compressor.py:7981; agent/context_compressor.py:5935` - Tracks `compression_count` per compressor/session and decays `protect_first_n` after every completed compaction.
- **Java:** `core/context/CompressionPolicy.java:139; core/context/CompressionPolicy.java:291` - Uses one singleton-global policy counter, increments it only when measured savings are at least 10%, and does not use the declared per-session map.
- **Impact:** One session's compaction can cause unrelated new sessions to stop protecting their opening turns; a low-savings compaction in the same session also fails to decay its protected head, unlike Hermes.

### P17: Seam 3 - System prompt [HIGH]
- **Hermes:** `agent/system_prompt.py:411` - Injects TASK_COMPLETION_GUIDANCE only when tools are loaded and agent.task_completion_guidance is enabled (default true).
- **Java:** `core/prompt/DefaultPromptBuilder.java:1212` - Always appends task-completion guidance, including toolless sessions, with no equivalent configuration gate.
- **Impact:** Java can direct a toolless model to produce tool-backed execution results and cannot honor a deployment that disables this prompt block.

### P18: Seam 3 - System prompt [HIGH]
- **Hermes:** `agent/system_prompt.py:422` - Injects PARALLEL_TOOL_CALL_GUIDANCE only when tools exist and agent.parallel_tool_call_guidance is enabled.
- **Java:** `core/prompt/DefaultPromptBuilder.java:1212` - Always injects parallel-tool-call guidance with no tools/configuration condition.
- **Impact:** Java issues irrelevant parallel-tool guidance in toolless sessions and does not support the Hermes prompt-size/behavior flag.

### P19: Seam 3 - System prompt [HIGH]
- **Hermes:** `agent/system_prompt.py:482` - Tool-use enforcement is gated on loaded tools and supports agent.tool_use_enforcement values auto, true, false, or a custom substring list; default matching is substring-based.
- **Java:** `core/prompt/ModelPromptPolicy.java:49` - Uses an unconditional fixed prefix set with no configuration override and does not first require a nonempty toolset.
- **Impact:** Model identifiers with provider prefixes can miss enforcement in Java, toolless sessions can receive it, and operators cannot disable/force/customize the policy.

### P20: Seam 3 - System prompt [HIGH]
- **Hermes:** `agent/system_prompt.py:517` - Execution discipline is independently controlled by agent.execution_guidance (auto/true/false/custom substring list) and only rendered for sessions with tools.
- **Java:** `core/prompt/DefaultPromptBuilder.java:38` - ModelPromptPolicy.guidanceFor always selects guidance using immutable starts-with prefixes whenever tools exist.
- **Impact:** Java cannot honor Hermes execution-guidance configuration and fails to match provider-qualified model IDs that Hermes matches by substring.

### P21: Seam 3 - System prompt [HIGH]
- **Hermes:** `gateway/session.py:513` - Treats gateway session labels as untrusted, normalizes/truncates them, and JSON-quotes source, user, topic, and chat metadata before prompt insertion.
- **Java:** `core/prompt/DefaultPromptBuilder.java:1391` - Interpolates platform, chat type, and userDisplayName directly into the Current Session Context block.
- **Impact:** Untrusted session metadata can inject headings or instructions into Java's system prompt rather than remaining inert labels.

### P22: Seam 4 - Curator [HIGH]
- **Hermes:** `agent/curator.py:328` - Processes every curator-managed candidate returned from the skill tree and telemetry report.
- **Java:** `core/skill/CuratorService.java:457` - Fetches only the first 50 unarchived SkillEntity records for each curator cycle.
- **Impact:** Skills beyond the first database page never transition stale/archived or participate in curator consolidation.

### P23: Seam 4 - Curator [HIGH]
- **Hermes:** `agent/curator.py:334` - Skips all automatic lifecycle transitions for skills referenced by any cron job, including paused/disabled jobs and distant one-shots.
- **Java:** `core/skill/CuratorService.java:464` - Has no cron-reference check before stale/archive classification.
- **Impact:** Java can archive a skill that a scheduled job still references, breaking that job on its next run.

### P24: Seam 4 - Skill bundles [HIGH]
- **Hermes:** `agent/skill_bundles.py:269` - Re-applies global/platform disabled-skill filtering while expanding bundle members and reports disabled members as skipped.
- **Java:** `core/skill/SkillBundleService.java:128` - Loads bundle member content directly from the filesystem/database with no disabled-skill check.
- **Impact:** Java bundles can activate skills that an operator disabled, bypassing the normal skill availability policy.

### P25: Seam 4 - Skill management [HIGH]
- **Hermes:** `tools/skill_manager_tool.py:1236` - For a nonempty absorbed_into declaration, requires a distinct umbrella skill to exist before deletion; background curator deletes are recoverable archives.
- **Java:** `core/skill/DatabaseSkillManager.java:164` - Stores any nonempty absorbedInto string on the departing entity and immediately deletes it without validating that the destination exists.
- **Impact:** Java can record a false consolidation into a nonexistent umbrella and irreversibly remove the source skill.

### P26: Seam 4 - Skill management [HIGH]
- **Hermes:** `tools/skill_manager_tool.py:289` - Treats essential skills, including hermes-agent, as permanently deletion-protected even without a telemetry pin.
- **Java:** `core/skill/DatabaseSkillManager.java:153` - Blocks deletion only when the database entity's pinned flag is true.
- **Impact:** Java can permanently delete the mandatory Hermes operating-manual skill if it was not explicitly pinned.

### P27: Seam 4 - Skill management [HIGH]
- **Hermes:** `tools/skill_manager_tool.py:606` - YAML-parses frontmatter and blocks malformed YAML, non-mapping frontmatter, missing fields, oversized descriptions, and empty bodies.
- **Java:** `core/skill/DatabaseSkillManager.java:684` - Uses substring checks for name: and description: between fences and does not parse/validate a YAML mapping or description length.
- **Impact:** Java can persist malformed or structurally invalid SKILL.md content that Hermes rejects, causing unreliable index metadata and later skill loading.

### P28: Seam 4 - Skill preprocessing [HIGH]
- **Hermes:** `tools/skills_tool.py:1591` - Returns a requested linked file as raw file content; preprocessing is applied only to main SKILL.md content.
- **Java:** `tools/memory/SkillViewTool.java:95` - Passes requested support files through SkillPreprocessor, which can execute enabled !`...` inline-shell snippets.
- **Impact:** With inline shell enabled, Java executes commands embedded in support files that Hermes only returns as text; this changes both safety exposure and delivered content.

### P29: Seam 4 - Skill view [HIGH]
- **Hermes:** `tools/skills_tool.py:1493` - Rejects explicit skill loads for platform-incompatible and configuration-disabled skills before serving SKILL.md or a linked file.
- **Java:** `tools/memory/SkillViewTool.java:126` - Rejects only the SkillInfo frontmatter-disabled flag.
- **Impact:** Java can load a skill disabled by configuration or unsupported on the current platform.

### P30: Seam 4 - Skill view [HIGH]
- **Hermes:** `tools/skills_tool.py:1493` - Performs platform and disabled checks before branching to a requested supporting file.
- **Java:** `tools/memory/SkillViewTool.java:89` - Reads a requested support file immediately, before multi-strategy lookup and disabled checks.
- **Impact:** A caller can retrieve support content from a disabled skill in Java even though loading that skill's main SKILL.md is refused.

### P31: Seam 4 - Skills index [HIGH]
- **Hermes:** `agent/prompt_builder.py:1967` - Filters the skills index using global/platform disabled names, platform compatibility, environment relevance, and requires/fallback tool and toolset conditions.
- **Java:** `core/prompt/DefaultPromptBuilder.java:891` - Filters only SkillInfo.disabled(), which represents frontmatter disabled state; it does not apply configured disabled names or offer-time platform/environment/tool-condition filters.
- **Impact:** Java advertises skills in its mandatory index that are disabled or unusable in the current platform, environment, or toolset, encouraging invalid skill loads.

### P32: Seam 5 - Cron scheduler [HIGH]
- **Hermes:** `cron/jobs.py:2710; cron/jobs.py:2715; cron/scheduler.py:152` - Persists failure_streak for every failed run, resets it only after a successful run, and includes a recurring-job failure nudge in delivered failure output after the configured threshold.
- **Java:** `service/CronJobService.java:552; service/CronJobService.java:561; service/CronJobService.java:419` - Only increments consecutiveFailures for errors classified as backend-unavailable; executeJob swallows the error, after which executeAndReschedule resets the counter as if the execution had succeeded.
- **Impact:** Java's consecutive-failure threshold and exponential backoff generally never accumulate, so recurring backend failures retry at normal cadence and the needs-attention nudge is not reliably reached.

### P33: Seam 5 - Cron scheduler [HIGH]
- **Hermes:** `cron/jobs.py:3098; cron/jobs.py:3123; cron/scheduler.py:6659` - Uses durable file-backed fire claims and per-job locks, fenced by owner tokens, to provide cross-process/machine at-most-once firing.
- **Java:** `service/CronJobService.java:64; service/CronJobService.java:65; service/CronJobService.java:353` - Uses only process-local ConcurrentHashMap scheduled tasks and ReentrantLocks.
- **Impact:** Multiple Java application instances sharing the database can independently initialize and execute the same enabled job, duplicating LLM calls and side effects.

### P34: Seam 5 - Cron scheduler [HIGH]
- **Hermes:** `cron/jobs.py:811; cron/jobs.py:818; cron/jobs.py:1098` - Converts a relative one-shot duration into a persisted absolute run_at timestamp, so restart recovery retains the original due time.
- **Java:** `service/CronScheduleParser.java:63; service/CronScheduleParser.java:73; service/CronJobService.java:336` - Persists the raw duration such as 30m and reparses it as a fresh delay from now whenever scheduleJob runs, including application startup.
- **Impact:** Restarting Java before a relative one-shot fires postpones it by the entire duration each time.

### P35: Seam 5 - Cron scheduler [HIGH]
- **Hermes:** `cron/monitor.py:143; cron/monitor.py:147; cron/scheduler.py:5242` - Supports monitor_script and monitor_url sources, hashes exact output, suppresses unchanged ticks, and injects a bounded diff/context only on change.
- **Java:** `service/CronJobService.java:451` - Has no monitor fields or monitor execution/change-detection path; every scheduled execution proceeds directly to script/agent execution.
- **Impact:** Java cannot implement monitor-mode jobs and unnecessarily spends inference/delivers repeated unchanged observations.

### P36: Seam 5 - Cron scheduler [HIGH]
- **Hermes:** `cron/scheduler.py:3932; cron/scheduler.py:3997; cron/scheduler.py:4007` - Resolves scripts under HERMES_HOME/scripts and rejects traversal, symlink escape, non-files, and arbitrary absolute paths before execution.
- **Java:** `service/CronJobService.java:674; service/CronJobService.java:683; service/CronJobService.java:710` - Accepts any absolute script path, or resolves any relative path against workdir/user.dir, then executes it with bash or python3.
- **Impact:** A Java cron record can execute arbitrary host files outside the managed scripts directory.

### P37: Seam 5 - Cron scheduler [HIGH]
- **Hermes:** `cron/scheduler.py:5165; cron/scheduler.py:5182; cron/scheduler.py:7103` - For no_agent jobs, a missing script, missing file, timeout, or nonzero script exit returns failure, creates an alertable result, and the firing path records the failed run.
- **Java:** `service/CronJobService.java:461; service/CronJobService.java:463; service/CronJobService.java:683; service/CronJobService.java:759` - executeNoAgentJob returns normally for missing scripts/files, timeout, and nonzero exit. executeJob then unconditionally writes lastStatus=success and returns.
- **Impact:** Broken script-only jobs are falsely reported as successful, can consume repeat budget, and do not enter failure/nudge handling.

### P38: Seam 5 - Cron scheduler [HIGH]
- **Hermes:** `cron/scheduler.py:5621; cron/scheduler.py:5627; cron/scheduler.py:5842` - Resolves and applies persisted per-job model, provider, and base_url overrides before constructing the cron agent runtime.
- **Java:** `service/CronJobService.java:168; service/CronJobService.java:491; service/CronJobService.java:501` - Persists modelProvider, modelName, and baseUrl, but executeJob never forwards any of them to runBackground; the code states model settings are not applied.
- **Impact:** Java job-level model/provider pins are inert, so scheduled jobs may use an unintended model, provider, credentials, or cost profile.

### P39: Seam 6 - Memory [HIGH]
- **Hermes:** `tools/memory_tool.py:227; tools/memory_tool.py:254; agent/system_prompt.py:830` - Threat-scans every on-disk memory entry while building the system-prompt snapshot and substitutes a blocked placeholder for suspicious legacy or externally modified entries.
- **Java:** `core/memory/DatabaseMemoryProvider.java:195; core/memory/DatabaseMemoryProvider.java:460; core/prompt/DefaultPromptBuilder.java:1111` - Threat-scans content on its normal write paths, but DefaultPromptBuilder reads raw persisted facts directly from the database and injects them without scanning.
- **Impact:** A malicious or legacy database entry inserted outside the normal memory write path is injected verbatim into the Java system prompt, creating a persistent prompt-injection path.

### P40: Seam 7 - Session search [HIGH]
- **Hermes:** `tools/session_search_tool.py:766-781` - Parses role_filter and passes either the supplied roles or the default user,assistant roles into the FTS query.
- **Java:** `tools/memory/SessionSearchService.java:117-131,143-156` - Parses roleFilter into roleList but never uses it in either content or title search; all message roles are searched.
- **Impact:** role_filter=tool is ineffective, and default discovery can match tool output that Hermes intentionally excludes.

### P41: Seam 7 - Session search [HIGH]
- **Hermes:** `tools/session_search_tool.py:771-781` - Passes the user's FTS5 expression directly to the SQLite FTS query, preserving FTS5 AND/OR/NOT, quoted phrase, and prefix-wildcard semantics.
- **Java:** `persistence/repository/MessageRepository.java:56-62; persistence/repository/SessionRepository.java:47-64` - Sends the raw expression to PostgreSQL plainto_tsquery; Boolean operators, quotes, and trailing wildcard syntax are treated as plain-language input rather than FTS5 syntax.
- **Impact:** Advertised queries such as alpha OR beta, python NOT java, "docker networking", and deploy* return materially different result sets.

### P42: Seam 7 - Session search [HIGH]
- **Hermes:** `tools/session_search_tool.py:978-990,1009-1020` - Opens the requested profile's read-only state DB for every shape and, for an unqualified read miss, scans profile DBs to locate the owning session and reports its profile.
- **Java:** `tools/memory/SessionSearchService.java:67-81,105-114` - Parses profile from an @session link but never uses it to select a repository/database; it is only propagated for formatting a link.
- **Impact:** Cross-profile session links and profile= reads silently query the current Java database instead of the requested profile.

### P43: Seam 8 - Curator [HIGH]
- **Hermes:** `agent/curator.py:1956-1963; tools/skill_ledger.py:67-83,163-190` - Marks the forked review as background_review, so skill mutations derive the curator actor; its ledger records actor, action, evidence, and before/after file manifests backed by content-addressed blobs.
- **Java:** `core/skill/CuratorService.java:672-675,723-726; core/skill/SkillMutationLedger.java:53-71` - Creates a synthetic curator session but does not bind WriteContext to BACKGROUND_REVIEW before executing tools; generic mutations therefore derive agent unless another caller set context. Its audit entity stores only textual old/new values.
- **Impact:** LLM-driven curator changes can be misattributed as agent changes and lack the package-level forensic/rollback evidence Hermes records.

### P44: Seam 8 - Curator [HIGH]
- **Hermes:** `agent/curator.py:2016-2031` - Runs only through maybe_run_curator after enabled, paused, interval, and supplied idle-duration gates pass.
- **Java:** `core/skill/CuratorService.java:411-420,434-442` - The scheduled executor calls runCuratorCycle directly, bypassing shouldRunNow, paused status, and idle gating after startup.
- **Impact:** Pausing the curator or having a recently active agent does not prevent Java's scheduled mutation cycle.

### P45: Seam 8 - Curator [HIGH]
- **Hermes:** `agent/curator.py:324-398; tools/skill_usage.py:455-482` - Only transitions curator-eligible skills: agent-managed skills and optionally permitted bundled skills; it excludes hub, external, and protected built-ins, and skips cron-referenced skills.
- **Java:** `core/skill/CuratorService.java:456-500` - Processes every non-archived database skill except pinned/protected/manual-user entries. It has no hub-origin, curator-management, bundled-policy, external-owner, or cron-reference eligibility gate.
- **Impact:** The Java curator can automatically mark stale or archive hub-installed, foreground-created, and cron-dependent skills that Hermes deliberately leaves untouched.

### P46: Seam 8 - Curator [HIGH]
- **Hermes:** `agent/curator.py:328-398` - Iterates every row returned by curated_report for each automatic-transition pass.
- **Java:** `core/skill/CuratorService.java:456-457` - Loads only PageRequest.of(0, 50) non-archived skills and has no pagination loop.
- **Impact:** Skills after the first 50 are never transitioned, audited, or included in a deterministic curator cycle.

### P47: Seam 8 - Curator [HIGH]
- **Hermes:** `agent/curator.py:359-369` - Applies a never-used grace floor: a use_count=0 skill younger than stale_after_days cannot be archived or even remain stale, even if archive_after_days is configured shorter.
- **Java:** `core/skill/CuratorService.java:480-500` - Uses createdAt immediately as the archive anchor for every never-used skill and has no use-count/grace-floor condition.
- **Impact:** With valid custom thresholds where archive_after_days is shorter than stale_after_days, Java can archive a new never-used skill before Hermes would allow it.

### P48: Seam 8 - Curator [HIGH]
- **Hermes:** `agent/curator.py:70-78,1609-1654` - Defaults consolidation off and, when disabled, runs only deterministic transitions without starting an auxiliary-model review.
- **Java:** `core/skill/CuratorService.java:538-548` - Always invokes LLM consolidation when modelClient is available, with heuristic fallback otherwise; it has no consolidation configuration gate.
- **Impact:** Routine Java curator cycles incur model cost and produce consolidation recommendations/actions when Hermes's default policy is prune-only.

### P49: Seam 8 - Curator [HIGH]
- **Hermes:** `tools/skill_usage.py:59-78; agent/curator.py:328-341` - Treats the load-bearing built-in skill plan as protected on every curation path.
- **Java:** `core/skill/CuratorService.java:83-86,464-468,975-978` - Protects hermes-agent, hermes-agent-dev, backend-dev, and default, but not plan.
- **Impact:** Java may archive or consolidate plan, breaking the documented /plan command path Hermes protects.

### P50: Seam 9 - Streaming [HIGH]
- **Hermes:** `gateway/stream_consumer.py:1037; gateway/stream_consumer.py:1078; gateway/stream_consumer.py:1173` - After sealing overflow head chunks, Hermes replaces the live accumulated buffer with only the unsent tail; subsequent stream updates edit that tail rather than replaying the whole response.
- **Java:** `telegram-bot/src/main/java/com/azhukov/agent/bot/streaming/StreamEditor.java:467; telegram-bot/src/main/java/com/azhukov/agent/bot/streaming/StreamEditor.java:507` - editStreamSplit receives the full accumulated response, edits/sends its chunks, and changes only currentMessageId to the last chunk. The caller continues passing the complete accumulated response on each later token, so the next split edits the last chunk with the first response chunk again and re-sends the old remainder.
- **Impact:** Long streamed replies can duplicate their leading content and repeated continuations on every post-limit update; finalization can also leave stale fragments behind.

### P51: Seam 9 - Streaming [HIGH]
- **Hermes:** `gateway/stream_consumer.py:553; gateway/stream_consumer.py:1307` - A tool boundary is queued independently of a returned platform message ID, then the consumer finalizes and resets the segment so post-tool text starts in a fresh bubble.
- **Java:** `telegram-bot/src/main/java/com/azhukov/agent/bot/core/StreamingOrchestrator.java:94; telegram-bot/src/main/java/com/azhukov/agent/bot/core/StreamingOrchestrator.java:129` - The orchestrator starts with the short placeholder "...", so startStream returns no ID. editStream later creates and tracks an ID internally, but messageId[0] remains -1. The tool callback only calls onSegmentBreak and clears accumulated text when messageId[0] >= 0, so it skips the boundary after the normal delayed first send.
- **Impact:** Text produced after a tool call continues in and overwrites/extends the pre-tool streaming bubble, while the tool-progress bubble appears in the middle rather than separating two text segments.

### P52: Seam 9 - Streaming [HIGH]
- **Hermes:** `gateway/stream_consumer.py:707; gateway/stream_consumer.py:717` - The streaming think scrubber compares a lower-cased view of each chunk and therefore strips supported think tags regardless of case, including mixed-case tags.
- **Java:** `telegram-bot/src/main/java/com/azhukov/agent/bot/streaming/StreamEditor.java:1155; telegram-bot/src/main/java/com/azhukov/agent/bot/streaming/ThinkTagFilter.java:84` - Intermediate display uses a case-insensitive regex, but finalization uses the stateful ThinkScrubber alone. Its opening-tag table is exact/case-sensitive and lacks mixed-case forms such as and .
- **Impact:** A mixed-case reasoning block can be hidden while streaming but exposed in the final Telegram message, leaking model reasoning.

### P53: Seam 10 - Tool execution [MEDIUM]
- **Hermes:** `agent/tool_dispatch_helpers.py:117; agent/tool_dispatch_helpers.py:203` - Hermes plans a batch into ordered segments: maximal safe runs execute concurrently, barriers execute sequentially, and later independent safe calls can still regain parallelism.
- **Java:** `backend/src/main/java/com/azhukov/agent/core/tool/ToolParallelSafety.java:74; backend/src/main/java/com/azhukov/agent/core/agent/TurnExecutor.java:593` - Java makes one all-or-nothing shouldParallelize decision for the entire batch. One unsafe, interactive, malformed, or overlapping call forces every otherwise-independent call in the response onto the sequential path.
- **Impact:** Mixed batches have unnecessarily serialized read/search work and materially slower tool turns; Java also rejects concurrent overlapping read/read calls that Hermes permits.

### P54: Seam 10 - Tool execution [MEDIUM]
- **Hermes:** `tools/browser_use_cli.py:602; tools/browser_use_cli.py:814` - browser_exec exposes raw CDP method invocation, enabling commands such as Accessibility.getFullAXTree and DOM.getBoxModel as well as session-aware browser interactions.
- **Java:** `backend/src/main/java/com/azhukov/agent/tools/browser/BrowserCdpTool.java:12; backend/src/main/java/com/azhukov/agent/tools/browser/BrowserCdpTool.java:24` - browser_cdp accepts only an expression string and routes it to BrowserService.evaluate; it cannot invoke arbitrary CDP domains or methods.
- **Impact:** Java cannot perform several required CDP workflows, including accessibility-tree lookup and DOM box-model coordinate resolution, through its model-visible browser tool.

### P55: Seam 10 - Tool execution [MEDIUM]
- **Hermes:** `tools/process_registry.py:461; tools/process_registry.py:1625` - A background process marked notify_on_complete enqueues a durable completion notification that can be delivered back into the agent/session.
- **Java:** `backend/src/main/java/com/azhukov/agent/tools/terminal/TerminalTool.java:137; backend/src/main/java/com/azhukov/agent/tools/terminal/ProcessTool.java:290` - notify_on_complete installs only a callback that writes an INFO log when the process exits; no message, queue entry, or session event is produced.
- **Impact:** Users and the model are not actually notified when Java background work finishes, despite the tool result and schema claiming that notify_on_complete provides notification.

### P56: Seam 2 [MEDIUM]
- **Hermes:** `agent/context_compressor.py:4289; agent/context_compressor.py:4301; agent/context_compressor.py:4439` - Builds deterministic fallback from structured messages: links tool results to call IDs/names/arguments, recursively extracts `path`/`workdir`/output paths from arguments, excludes synthetic compression-user rows from user asks, and re-injects ghosted pruned-skill markers after size capping.
- **Java:** `core/context/DefaultContextCompressor.java:1122` - Builds fallback only by splitting the already flattened summary-input text on blank lines. It has no message structure, tool-call/result association, synthetic-user metadata, argument-object traversal, or pruned-skill marker reinjection.
- **Impact:** When the summary model fails, Java's fallback loses important file/tool provenance and can preserve internal synthetic prompts as user asks while dropping skill reload requirements.

### P57: Seam 2 [MEDIUM]
- **Hermes:** `agent/context_compressor.py:4828; agent/context_compressor.py:4908` - Requests historical-task and resolved-question sections, explicit user-correction/error preservation, pruned-skill marker reproduction, concrete output detail, and optional temporal anchoring in the LLM summary.
- **Java:** `core/context/DefaultContextCompressor.java:117; core/context/DefaultContextCompressor.java:160` - Uses a shorter fixed template that omits Historical Task Snapshot, Resolved Questions, the pruned-skills section/marker rule, temporal anchoring, and the richer provenance instructions.
- **Impact:** After ordinary LLM compression Java can lose unresolved-vs-resolved state and required `[SKILL_PRUNED]` reload markers, so subsequent turns may resume stale work or use unavailable skills.

### P58: Seam 3 - System prompt [MEDIUM]
- **Hermes:** `agent/system_prompt.py:461` - Adds STEER_CHANNEL_NOTE only if the session has tools, because an out-of-band steer is delivered through a tool result.
- **Java:** `core/prompt/DefaultPromptBuilder.java:1194` - Always adds the steer-marker trust and one-shot guidance.
- **Impact:** Java advertises a mid-turn delivery channel in sessions where no tool-result channel exists.

### P59: Seam 3 - System prompt [MEDIUM]
- **Hermes:** `agent/system_prompt.py:501` - Google operational guidance is emitted only when tool-use enforcement is selected, so disabling enforcement suppresses the related operational block.
- **Java:** `core/prompt/DefaultPromptBuilder.java:1203` - Always emits Google/OpenAI family guidance whenever tools exist, independently of tool-use enforcement.
- **Impact:** Turning off enforcement has materially different behavior: Java still supplies model-specific operational directives.

### P60: Seam 3 - System prompt [MEDIUM]
- **Hermes:** `agent/system_prompt.py:741` - Resolves hints for all built-in and plugin platforms, applies configured replace/append overrides, and appends Telegram rich-message instructions only when opted in.
- **Java:** `core/prompt/DefaultPromptBuilder.java:1220` - Adds only a fixed Telegram hint when session metadata equals telegram.
- **Impact:** Java lacks non-Telegram channel formatting/delivery guidance, plugin platform hints, configured overrides, and Telegram rich-message opt-in behavior.

### P61: Seam 3 - System prompt [MEDIUM]
- **Hermes:** `agent/system_prompt.py:830` - Places MEMORY.md content before USER.md content in the volatile tier.
- **Java:** `core/prompt/DefaultPromptBuilder.java:1076` - Builds and emits the user-profile block before the memory block.
- **Impact:** The later memory block has greater recency/precedence in Java, reversing Hermes's profile-versus-notes ordering.

### P62: Seam 3 - System prompt [MEDIUM]
- **Hermes:** `agent/system_prompt.py:841` - Optionally appends an external memory-provider system-prompt block after built-in memory and user-profile blocks when its tools are exposed.
- **Java:** `core/prompt/DefaultPromptBuilder.java:1347` - Only appends MemoryProvider-backed memory/user blocks; no external provider prompt hook is rendered.
- **Impact:** Java cannot expose provider-specific memory operating instructions even when an external memory integration is active.

### P63: Seam 4 - Audit log [MEDIUM]
- **Hermes:** `tools/skill_manager_tool.py:1593` - Captures before/after state and appends a ledger mutation for every successful skill_manage create, edit, patch, delete, write_file, and remove_file operation.
- **Java:** `core/skill/CuratorService.java:196` - Writes audit entries only from CuratorService lifecycle state mutations; DatabaseSkillManager saves/deletes and support-file mutations do not write this audit ledger.
- **Impact:** Java lacks a complete mutation history for ordinary skill-management changes, reducing traceability and recovery evidence.

### P64: Seam 4 - Convention linter [MEDIUM]
- **Hermes:** `tools/skill_linter.py:393` - Runs all ten checks; when given a skill directory it checks dangling references, POSIX script platform gating, and forbidden scaffolding files in addition to content-only rules.
- **Java:** `core/skill/SkillConventionLinter.java:97` - Runs only content-only checks and has no filesystem-aware invocation for dangling references, POSIX primitives in scripts, or forbidden files.
- **Impact:** Java reports advisory lint success while shipping broken linked-file references, platform-unsafe scripts, or disallowed skill scaffolding.

### P65: Seam 4 - Skill bundles [MEDIUM]
- **Hermes:** `agent/skill_bundles.py:332` - Builds each bundle member through the normal skill-message path, including configured template and inline-shell preprocessing.
- **Java:** `core/skill/SkillBundleService.java:133` - Loads raw SKILL.md text for bundle members and concatenates it into the bundle message.
- **Impact:** Template variables and enabled inline-shell expansions work for individually loaded Hermes skills but are skipped for Java bundle members.

### P66: Seam 4 - Skill management [MEDIUM]
- **Hermes:** `tools/skill_manager_tool.py:621` - On create, blocks descriptions longer than the 60-character system-index budget; edit/patch retain legacy over-limit descriptions for repair.
- **Java:** `core/skill/DatabaseSkillManager.java:80` - Has one save path and never enforces the 60-character create-time routing budget.
- **Impact:** New Java skills can lose their trigger information to index truncation immediately after creation.

### P67: Seam 4 - Skill preprocessing [MEDIUM]
- **Hermes:** `agent/skill_preprocessing.py:15` - Expands only ${HERMES_SKILL_DIR} and ${HERMES_SESSION_ID}; legacy ${SKILL_DIR} and ${SESSION_ID} remain literal.
- **Java:** `core/skill/SkillPreprocessor.java:32` - Also expands legacy ${SKILL_DIR} and ${SESSION_ID}.
- **Impact:** The same skill source renders differently across runtimes, potentially substituting text that Hermes deliberately leaves visible for debugging.

### P68: Seam 5 - Cron scheduler [MEDIUM]
- **Hermes:** `cron/scheduler.py:5210; cron/scheduler.py:5218; cron/scheduler.py:6875` - Creates and saves an output document for successful no_agent stdout, allowing inspection and later context_from continuity.
- **Java:** `service/CronJobService.java:461; service/CronJobService.java:462; service/CronJobService.java:467` - Returns from executeJob immediately after successful noAgent execution and never calls recordExecution or saves stdout as outputText.
- **Impact:** Java script-only output is unavailable to execution history and cannot be consumed by Java context_from chains.

### P69: Seam 5 - Cron scheduler [MEDIUM]
- **Hermes:** `cron/scheduler.py:6323; cron/scheduler.py:6356; cron/scheduler.py:6875; cron/scheduler.py:4310` - Persists a full per-run Markdown document containing job metadata, assembled prompt, and final response; context_from injects that latest document.
- **Java:** `service/CronJobService.java:548; service/CronJobService.java:550; service/CronJobService.java:640` - Stores only the final nonblank assistant message from the run session as outputText.
- **Impact:** Downstream Java context_from jobs lose the prior run's prompt, schedule/job metadata, script/monitor context, and explicit run framing that Hermes makes available for continuity.

### P70: Seam 6 - Memory [MEDIUM]
- **Hermes:** `agent/background_review.py:1063; agent/background_review.py:1511` - Publishes compact background-review action summaries back through the parent agent's safe-print/callback path after filtering inherited stale actions.
- **Java:** `core/agent/MemoryNudgeManager.java:161; core/agent/MemoryNudgeManager.java:165` - Retrieves a completed review summary only to log it; the nudge manager does not emit it through the user response or streaming transport.
- **Impact:** Java users do not receive Hermes-style feedback that the self-improvement loop saved memory or changed skills, even when the review completed successfully.

### P71: Seam 6 - Memory [MEDIUM]
- **Hermes:** `agent/background_review.py:1462; agent/background_review.py:1476` - For the normal inherited-runtime review path, replays the full captured conversation snapshot; it only digests history when explicitly routed to a different review model.
- **Java:** `core/memory/BackgroundReviewService.java:239; core/memory/BackgroundReviewService.java:245` - Always truncates the review input to the final ten messages after dropping leading tool messages.
- **Impact:** Java background reviews cannot identify durable facts, recurring mistakes, or self-improvement opportunities that depend on older conversation context.

### P72: Seam 6 - Memory [MEDIUM]
- **Hermes:** `tools/memory_tool.py:161; tools/memory_tool.py:190; agent/system_prompt.py:830` - Captures a sanitized MEMORY.md/USER.md snapshot at load time and keeps the system-prompt memory block immutable for the session; writes update live tool state but not the cached prompt.
- **Java:** `core/prompt/DefaultPromptBuilder.java:1074; core/prompt/DefaultPromptBuilder.java:1111; core/memory/DatabaseMemoryProvider.java:460` - Builds memory blocks by querying the current database rows each time the system prompt is assembled.
- **Impact:** Java can expose newly written memory to the model mid-session and mutate the prompt/cache key, whereas Hermes deliberately defers that visibility to preserve prompt-cache and conversation invariants.

### P73: Seam 7 - Session search [MEDIUM]
- **Hermes:** `tools/session_search_tool.py:1040-1062,771-781` - Lets the DB apply newest/oldest ordering on timestamps as part of FTS retrieval before result hydration.
- **Java:** `tools/memory/SessionSearchService.java:281-287` - Sorts hydrated DiscoverResult values using the human-formatted when string, such as "August 2, 2026 at 9:00 AM", rather than an Instant.
- **Impact:** sort=newest and sort=oldest can order sessions incorrectly across months or non-zero-padded day values.

### P74: Seam 7 - Session search [MEDIUM]
- **Hermes:** `tools/session_search_tool.py:485-528` - Uses list_sessions_rich's canonical child classifier and compression-chain projection, hiding implementation children while retaining listable reset/branch sessions.
- **Java:** `tools/memory/SessionSearchService.java:356-378; persistence/repository/SessionRepository.java:35-40` - Fetches all non-hidden-source sessions directly and only skips the current session and one compression-root case.
- **Impact:** Browse can expose delegation/compression implementation sessions and lacks Hermes's one-logical-conversation compression projection.

### P75: Seam 7 - Session search [MEDIUM]
- **Hermes:** `tools/session_search_tool.py:625-650` - When an anchor belongs to a same-lineage descendant rather than the supplied parent session, transparently rebinds to the owning child and returns a warning.
- **Java:** `tools/memory/SessionSearchService.java:312-315` - Looks only in the supplied session and returns an error when the anchor is absent.
- **Impact:** A valid parent-session/message-id pairing from a compression or delegation lineage cannot be scrolled in Java.

### P76: Seam 7 - Session search [MEDIUM]
- **Hermes:** `tools/session_search_tool.py:96,198-220` - Derives fresh-reset reasons from the canonical state-layer set (session_reset, session_switch, idle, daily, suspended, resume_pending_expired) plus new_session.
- **Java:** `tools/memory/SessionSearchService.java:42-44,428-433` - Uses a separate, incompatible set: new_session, idle_timeout, daily_reset, gateway_reset.
- **Impact:** Same-lineage discovery/scroll incorrectly treats actual Java-parity reset predecessors as still live context, hiding recall that Hermes exposes after resets.

### P77: Seam 8 - Curator [MEDIUM]
- **Hermes:** `agent/curator.py:1239-1298; agent/curator.py:1301-1478` - Writes a per-run JSON and Markdown report containing model/provider, duration, automatic transition counts, before/after delta, transitions, tool-call evidence, consolidation/pruning classification, and cron rewrites.
- **Java:** `core/skill/CuratorService.java:562-580,1112-1118` - Returns a CuratorReport containing only active/stale/archived name lists, suggestions, and actions, then persists a one-line state summary.
- **Impact:** Java operators cannot audit a curator run's provenance, transition counts, concrete mutation evidence, or cron-reference effects at Hermes report fidelity.

### P78: Seam 8 - Curator [MEDIUM]
- **Hermes:** `tools/skill_usage.py:1075-1148,1154-1190` - Archives by moving the complete skill directory to skills/.archive/<skill>, preserves support files, and offers per-skill restore from that archive.
- **Java:** `core/skill/CuratorService.java:489-496; core/skill/CuratorBackupService.java:1016-1032` - Archives by setting archived=true and lifecycleState=archived on the database row; recovery is only through a curator snapshot rollback API.
- **Impact:** Java has no Hermes-compatible archive directory, per-skill restore flow, or filesystem-visible recovery of a curated skill package.

### P79: Seam 9 - Streaming [MEDIUM]
- **Hermes:** `gateway/stream_consumer.py:195; gateway/stream_consumer.py:211` - Draft IDs are seeded with 49 random bits, making collision with relay tombstones after gateway restart negligibly likely.
- **Java:** `telegram-bot/src/main/java/com/azhukov/agent/bot/streaming/StreamEditor.java:127; telegram-bot/src/main/java/com/azhukov/agent/bot/streaming/StreamEditor.java:131` - Draft IDs are AtomicInteger values seeded only from the 9,000,000-value range, despite the adjacent comment claiming 49-bit behavior.
- **Impact:** A restarted Java bot can reuse a recent draft ID much more readily; transport-side draft tombstones may associate new frames with an old sealed stream, producing missing or misrouted draft/final delivery.

### P80: Seam 5 - Cron scheduler [LOW]
- **Hermes:** `cron/jobs.py:2750; cron/jobs.py:2762; cron/jobs.py:3195` - On reaching a finite repeat limit, retains a disabled completed job with final status, error, and delivery state, then prunes it later by retention policy.
- **Java:** `service/CronJobService.java:395; service/CronJobService.java:402; service/CronJobService.java:404` - Cancels and deletes the job immediately after repeatCompleted reaches repeatCount.
- **Impact:** Java loses completed finite-job audit state and its final delivery/error outcome immediately after the last run.
