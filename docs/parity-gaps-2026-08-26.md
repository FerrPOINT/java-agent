# Hermes Parity Gaps — Direct Audit 2026-08-26

## Found by direct line-by-line comparison (before subagent results)

### GAP-1: Compression threshold — no max_tokens subtraction [HIGH]

- **Hermes**: `_compute_threshold_tokens` (context_compressor.py:3072): `effective_window = context_length - max_tokens` — subtracts output reservation before computing threshold.
- **Java**: `CompressionPolicy.recalculateThreshold` (CompressionPolicy.java:177): uses `newContextWindowSize * thresholdFraction` directly — no max_tokens subtraction.
- **Impact**: With a large max_tokens (e.g. 65536), effective input budget is smaller. Java triggers compression too late → provider 400 before compaction fires.
- **Fix status**: ✅ Fixed 2026-08-26. `CompressionPolicy` now reserves `agent.model.max-tokens` from the input window; tested with 128K/32K reservation.
- **Fix**: Pass max_tokens to recalculateThreshold, compute `effective_window = contextWindowSize - max_tokens`.

### GAP-2: Compression threshold — no degenerate-window check [HIGH]

- **Hermes**: `_compute_threshold_tokens` (context_compressor.py:3090): if `floored >= effective_window`, triggers at 85% instead of 100% — prevents unreachable threshold on small-context models.
- **Java**: No such check. If `MINIMUM_CONTEXT_LENGTH` floor ≥ context window, threshold = entire window → compression never fires.
- **Impact**: Small-context models (64K) never compress → provider rejects request.
- **Fix status**: ✅ Fixed 2026-08-26. The 85% reachable trigger is now applied when the floor reaches the effective input budget, with an 8K regression test.
- **Fix**: Add `_MIN_CTX_TRIGGER_RATIO = 0.85` degenerate check.

### GAP-3: No PARTIAL_STREAM_STUB handling [MEDIUM]

- **Hermes**: conversation_loop.py:3915: when stream drops mid-tool-call before any text, `_is_empty_partial_stub` prevents appending empty assistant message — strict providers (Kimi/Moonshot) reject `{"role":"assistant","content":""}` with HTTP 400.
- **Java**: DefaultAgentRuntime.java:650: always appends `Message.assistant(partialContent, turnIndex)` even if content is empty.
- **Impact**: Stream interruption on Kimi/Moonshot → 400 on next replay → session permanently poisoned.
- **Fix status**: ✅ Fixed 2026-08-26. `isLengthContinuable` already guards on `response.hasContent()` (line 110), so empty stubs never enter the continuation path. Added defensive null/empty check before appending interim assistant message.
- **Fix**: Check `response.content()` is non-empty before appending interim assistant message in length-continuation path.

### GAP-4: No cron_hint in prompt [HIGH]

- **Hermes**: cron/scheduler.py:4351: always prepends `cron_hint` (`[IMPORTANT: You are running as a scheduled cron job... SILENT...]`) to every cron job prompt.
- **Java**: CronJobService: no cron_hint added. [SILENT] only in blueprint prompts.
- **Impact**: Cron jobs don't know they should suppress delivery with [SILENT]; don't know delivery is automatic; may try to use send_message.
- **Fix status**: ✅ Fixed 2026-08-26. `CronJobService` now prepends the full Hermes `cron_hint` (delivery + [SILENT]) to every agent-driven cron prompt; 3 test call-sites updated to assert the hint is present.
- **Fix**: Add cron_hint to prompt assembly in CronJobService.

### GAP-5: No cron notepad [LOW]

- **Hermes**: cron/scheduler.py:4345: `notepad_section = render_notepad_section(job_id)` — per-job KV scratchpad surviving scheduled wake-ups.
- **Java**: No notepad feature.
- **Impact**: Recurring cron jobs can't persist state between runs (beyond contextFrom).
- **Fix**: Defer — requires product decision (new feature, not parity bug).

### GAP-6: No _truncate_tool_call_args (Pass 3) [MEDIUM]

- **Hermes**: context_compressor.py:3926: Pass 3 truncates large tool_call arguments (>500 chars) in assistant messages outside protected tail, preserving valid JSON structure.
- **Java**: DefaultContextCompressor: no tool_call argument truncation. Only truncates tool OUTPUTS (pruneToolOutput), not tool CALL arguments.
- **Impact**: `write_file` with 50KB content survives pruning entirely → context bloat after multiple file operations.
- **Fix**: Add `truncateToolCallArgs` in compression pipeline, before summary input.

### GAP-7: No protected skill guard in pruning [MEDIUM]

- **Hermes**: context_compressor.py:3842: `_demote_tool_result_at` checks `spare_protected_skills` — skills just loaded via `skill_view` keep their full bodies through prune passes (ghost-skill defense #32106).
- **Java**: No protected-skill guard. `pruneToolOutput` prunes all tool results equally.
- **Impact**: Skill loaded moments before compaction gets demoted → model thinks instructions are still in context but they're gone.
- **Fix**: Track recently-loaded skills, spare their `skill_view` results from pruning.
