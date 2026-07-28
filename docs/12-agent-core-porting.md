# Agent Core → Java Porting Summary

This document maps the essential Python original agent implementation to a proposed Java structure under `com.azhukov.agent.core.*`. It covers only what must be ported; plugin hooks, display/TTS callbacks, kanban integration, credits tracking, and most provider-specific shims are intentionally omitted.

## Source Layout (Python)

The relevant Python files are in `/opt/dev/python-agent`:

| File | Lines | Role |
|------|------|------|
| `run_agent.py` | 5,461 | `AIAgent` class: main configuration, lifecycle, forwarders to helpers |
| `agent/conversation_loop.py` | 4,421 | Single-turn loop: API calls, retries, tool dispatch, compression, finalization |
| `agent/models_dev.py` | 725 | Core data models |
| `agent/trajectory.py` | 56 | Trajectory/message abstractions |
| `agent/iteration_budget.py` | 62 | Iteration budget accounting |
| `agent/errors.py` | 3 | Custom exceptions |
| `agent/tool_executor.py` | 1,428 | Concurrent/sequential tool execution |
| `agent/tool_dispatch_helpers.py` | 417 | Tool routing and invocation |
| `agent/context_engine.py` | 226 | Context assembly helpers |
| `agent/context_compressor.py` | 2,426 | Context compression engine |
| `agent/prompt_builder.py` | 1,630 | Prompt-formatting helpers |
| `agent/system_prompt.py` | 446 | Cached system-prompt assembly |
| `agent/turn_context.py` | 388 | Per-turn prologue / setup context |
| `agent/turn_finalizer.py` | 428 | Post-loop result assembly |
| `agent/turn_retry_state.py` | 68 | Per-call retry flags |

---

## 1. Data Models

Python models are dataclasses in `agent/models_dev.py`. Map to immutable-ish Java records with validation helpers.

### 1.1 Core Message / Turn Models

| Python | Java Package / Class | Responsibility |
|--------|----------------------|----------------|
| `models_dev.Message` | `com.azhukov.agent.core.model.Message` | One chat message: `role`, `content`, `toolCalls`, `toolCallId`, `name`, `reasoning`, `finishReason`, provider-specific fields (`reasoningContent`, `reasoningDetails`, `codexReasoningItems`), internal flags. |
| `models_dev.AssistantMessage` | `com.azhukov.agent.core.model.AssistantMessage` | Normalized assistant output: `content`, `toolCalls` (`List<ToolCall>`), `finishReason`, `reasoning`, `reasoningContent`, `reasoningDetails`. Produced by transport normalization. |
| `models_dev.ToolCall` / `FunctionCall` | `com.azhukov.agent.core.model.ToolCall` | `id`, `function.name`, `function.arguments` (JSON string). |
| `models_dev.ToolResult` | `com.azhukov.agent.core.model.ToolResult` | `content` (string or content parts), `error`, `isError`. |
| `models_dev.TurnResult` | `com.azhukov.agent.core.model.TurnResult` | Return value of one user turn: `finalResponse`, `messages`, `apiCalls`, `completed`, `interrupted`, `failed`, `partial`, `error`, token/cost counters. |
| `trajectory.TrajectoryMessage` | `com.azhukov.agent.core.model.TrajectoryMessage` | Minimal transcript view (role, content, timestamp). |
| `trajectory.Trajectory` | `com.azhukov.agent.core.model.Trajectory` | List of `TrajectoryMessage`, metadata for sample saving. |

### 1.2 Token / Usage Models

| Python | Java Package / Class | Responsibility |
|--------|----------------------|----------------|
| `models_dev.TokenUsage` | `com.azhukov.agent.core.model.TokenUsage` | `promptTokens`, `completionTokens`, `totalTokens`, `inputTokens`, `outputTokens`, `cacheReadTokens`, `cacheWriteTokens`, `reasoningTokens`. |
| `models_dev.CostEstimate` | `com.azhukov.agent.core.model.CostEstimate` | `amountUsd`, `status`, `source`. |

### 1.3 Configuration Models

| Python | Java Package / Class | Responsibility |
|--------|----------------------|----------------|
| `models_dev.AgentConfig` | `com.azhukov.agent.core.config.AgentConfig` | Model, provider, baseUrl, apiKey, maxIterations, maxTokens, tools, compressionEnabled, quietMode, etc. |
| `models_dev.ReasoningConfig` | `com.azhukov.agent.core.config.ReasoningConfig` | `enabled`, `effort`. |
| `models_dev.FallbackConfig` | `com.azhukov.agent.core.config.FallbackConfig` | Ordered fallback chain entries. |

### 1.4 Turn-Local Models

| Python | Java Package / Class | Responsibility |
|--------|----------------------|----------------|
| `turn_context.TurnContext` | `com.azhukov.agent.core.turn.TurnContext` | All locals produced by the turn prologue: `userMessage`, `originalUserMessage`, `messages`, `conversationHistory`, `activeSystemPrompt`, `effectiveTaskId`, `turnId`, `currentTurnUserIdx`, `shouldReviewMemory`, `pluginUserContext`, `extPrefetchCache`. |
| `turn_retry_state.TurnRetryState` | `com.azhukov.agent.core.turn.TurnRetryState` | Per-API-call retry flags: auth retries, image-shrink retry, thinking-signature retry, etc. |

---

## 2. Key Classes / Functions and Java Mapping

### 2.1 Agent Shell

| Python | Java | Responsibility |
|--------|------|----------------|
| `run_agent.AIAgent` | `com.azhukov.agent.core.AgentRuntime` | Holds session state, config, tools, clients, counters, cached system prompt, and delegates all loop work. |
| `run_agent.AIAgent.run_conversation` | `AgentRuntime.runTurn(String userMessage, ...)` | Public entry point; forwards to `TurnOrchestrator`. |
| `run_agent.AIAgent.chat` | `AgentRuntime.chat(...)` | Simple text-in/text-out wrapper. |

### 2.2 Turn Orchestration

| Python | Java | Responsibility |
|--------|------|----------------|
| `conversation_loop.run_conversation` | `com.azhukov.agent.core.turn.TurnOrchestrator.run()` | Main loop: builds `TurnContext`, runs the tool-calling `while` loop, calls finalizer. |
| `turn_context.build_turn_context` | `TurnContextBuilder.build(AgentRuntime, ...)` | Per-turn setup: sanitize input, reset counters, hydrate stores, build/restore system prompt, preflight compression, prefetch memory, set task/turn IDs. |
| `turn_finalizer.finalize_turn` | `TurnFinalizer.finalize(...)` | Budget-exhausted summary, trajectory save, session persist, result dict, plugin post-hooks, background review scheduling. |

### 2.3 System Prompt

| Python | Java | Responsibility |
|--------|------|----------------|
| `system_prompt.build_system_prompt` | `com.azhukov.agent.core.prompt.SystemPromptBuilder.build(agent, systemMessage, history)` | Assemble three-tier prompt: stable (identity, tool guidance, skills, platform hints), context (caller system message + context files), volatile (memory, profile, timestamp). |
| `system_prompt.invalidate_system_prompt` | `SystemPromptBuilder.invalidate()` | Clear `AgentRuntime.cachedSystemPrompt`. Must be called after context compression. |

**Critical invariant:** The system prompt is built once per session and replayed verbatim every turn. It is only invalidated after context compression. Date-only timestamps are used to keep the prefix byte-stable for provider KV caches.

### 2.4 Context / Prompt Assembly

| Python | Java | Responsibility |
|--------|------|----------------|
| `context_engine.ContextEngine` / helpers | `com.azhukov.agent.core.context.ContextAssembler` | Assemble API message list, inject ephemeral context into the current user message, copy reasoning fields, remove internal-only fields, apply system prompt + prefill messages, normalize whitespace and tool-call JSON. |
| `context_compressor.ContextCompressor` | `com.azhukov.agent.core.context.ContextCompressor` | Decide when to compress, summarize old turns, keep first/last N messages, update model context length from provider errors. |
| `prompt_builder.*` | `com.azhukov.agent.core.prompt.PromptBuilder` | String helpers: code fences, steer markers, continuation prompts, truncation helpers. |

### 2.5 API / Transport

| Python | Java | Responsibility |
|--------|------|----------------|
| `run_agent._build_api_kwargs` (forwards to `chat_completion_helpers.build_api_kwargs`) | `com.azhukov.agent.core.transport.ApiRequestBuilder.build(messages)` | Build provider-specific request payload (model, max tokens, tools, reasoning extra body, etc.). |
| Transport classes (chat_completions, anthropic_messages, bedrock_converse, codex_responses) | `com.azhukov.agent.core.transport.*` | Normalize requests/responses, validate response shape, map `finish_reason`, stream handling. |
| `run_agent._interruptible_streaming_api_call` | `StreamingApiClient.call(...)` | Streaming path with interrupt/stale-stream detection. |
| `run_agent._invoke_api_request_error_hook` | `ApiErrorReporter.report(...)` | Emit structured error events for observability. |

### 2.6 Error Classification / Recovery

| Python | Java | Responsibility |
|--------|------|----------------|
| `conversation_loop.classify_api_error` | `com.azhukov.agent.core.error.ApiErrorClassifier.classify(...)` | Map exceptions to `FailoverReason` (rate_limit, billing, context_overflow, payload_too_large, content_filter, auth, etc.) plus retryable/fallback/rotate flags. |
| `FailoverReason` enum | `com.azhukov.agent.core.error.FailoverReason` | Reasons used for recovery routing. |
| Retry loop in `conversation_loop` | `ApiErrorRecoveryHandler` | Per-error recovery: credential rotation, image shrink, strip reasoning details, fallback activation, compression, Unicode sanitization. |

### 2.7 Tool Execution

| Python | Java | Responsibility |
|--------|------|----------------|
| `tool_executor.execute_tool_calls_concurrent` | `com.azhukov.agent.core.tool.ToolExecutor.executeParallel(...)` | Run multiple tool calls concurrently, collect results, append tool messages. |
| `tool_executor.execute_tool_calls_sequential` | `ToolExecutor.executeSequential(...)` | Run tool calls one-by-one when dependencies/order matter. |
| `tool_dispatch_helpers.invoke_tool` | `ToolInvoker.invoke(name, args, taskId, toolCallId, ...)` | Resolve tool, apply request middleware, execute, format result. |
| `tool_dispatch_helpers._tool_result_content_for_active_model` | `ToolResultFormatter.formatForModel(...)` | Downgrade multimodal results for text-only models. |
| `run_agent._cap_delegate_task_calls` / `_deduplicate_tool_calls` | `ToolCallPreprocessor.limitDelegates(...)` / `deduplicate(...)` | Limit concurrent delegate calls, remove duplicate calls. |

### 2.8 Iteration Budget

| Python | Java | Responsibility |
|--------|------|----------------|
| `iteration_budget.IterationBudget` | `com.azhukov.agent.core.budget.IterationBudget` | Tracks `remaining`, `used`, `maxTotal`; `consume()` decrements; `refund()` restores (e.g. pure `execute_code` turns). |

### 2.9 Utilities

| Python | Java | Responsibility |
|--------|------|----------------|
| `model_tools.get_all_tool_names`, schema helpers | `com.azhukov.agent.core.tools.ToolRegistry` | Discover tools, build OpenAI function schemas, resolve toolsets. |
| `agent_runtime_helpers.sanitize_api_messages` | `com.azhukov.agent.core.message.MessageSanitizer.sanitize(...)` | Repair role alternation, stub missing tool results, drop orphaned tool results. |
| `agent_runtime_helpers.drop_thinking_only_and_merge_users` | `MessageSanitizer.dropThinkingOnlyAndMergeUsers(...)` | Remove assistant turns with only reasoning content, merge adjacent user messages. |
| `agent_runtime_helpers.repair_message_sequence_with_cursor` | `MessageSanitizer.repairRoleAlternation(...)` | Fix `tool → user` / `user → user` tails. |

---

## 3. Control Flow for a Single Turn

A single user turn is handled by `TurnOrchestrator.run()`:

1. **Prologue** (`TurnContextBuilder.build`)
   - Sanitize user message (strip surrogates).
   - Generate `effectiveTaskId` and `turnId`.
   - Reset per-turn retry counters and iteration budget.
   - Hydrate todo/nudge counters from history.
   - Build or restore cached system prompt.
   - Append user message to working `messages` list.
   - Run preflight context compression if enabled and threshold crossed.
   - Invoke `pre_llm_call` plugins (context injected into user message only).
   - Prefetch external memory.

2. **Tool-Calling Loop** (while budget remains)
   - Check for interrupt; break if requested.
   - Consume one iteration from `IterationBudget` (refund later for pure `execute_code`).
   - Drain pending `/steer` into the most recent tool message if present.
   - Build `apiMessages`: copy from working list, inject memory/plugin context into current user message, copy reasoning fields for API, strip internal fields, add system prompt + prefill messages, apply Anthropic cache control, sanitize role alternation, normalize whitespace/tool-call JSON, strip lone surrogates.
   - Estimate request tokens.
   - Make API call (streaming preferred; with retry loop).
   - Validate response shape; on invalid response retry/fallback.
   - Normalize response to `AssistantMessage` (content + toolCalls + finishReason).
   - Update token usage and cost counters in `AgentRuntime`.
   - Handle finish reasons:
     - `length` → continuation retry (up to 3), truncated tool-call retry, or rollback.
     - `content_filter` → try fallback once, else return refusal.
     - `incomplete` (Codex) → continue up to 3 times.
   - If tool calls present:
     - Validate tool names, repair mismatches, validate JSON arguments.
     - Cap/deduplicate delegate calls.
     - Append assistant message.
     - Execute tools (parallel by default).
     - Append tool results.
     - Check proactive context compression threshold.
     - Continue loop.
   - If no tool calls:
     - Final response reached.
     - Handle empty / reasoning-only responses with nudges / prefill retries / fallback.
     - Strip think blocks, append final assistant message, break.

3. **Error Recovery** (inside API retry loop)
   - Classify the error.
   - Try provider/model-specific recovery once each: image shrink, multimodal downgrade, reasoning signature strip, invalid encrypted reasoning, llama.cpp grammar sanitize, credential refresh, OAuth beta disable.
   - For 429/billing: rotate credentials if pool available, else eager fallback.
   - For 413 / context overflow: compress (max 3 attempts) unless auto-compaction disabled.
   - For non-retryable client errors / max retries: try fallback, else return failure dict.

4. **Finalization** (`TurnFinalizer.finalize`)
   - If final response is null and budget exhausted, call `_handle_max_iterations` for a toolless summary.
   - Save trajectory if enabled.
   - Clean up task-specific resources.
   - Persist session, dropping empty-response scaffolding first.
   - Append file-mutation verifier footer and turn-completion explainer when needed.
   - Run `transform_llm_output` and `post_llm_call` plugins.
   - Extract last reasoning from current turn only.
   - Assemble `TurnResult`.
   - Sync external memory; spawn background memory/skill review if due.
   - Fire `on_session_end` plugin hook.

---

## 4. Iteration Budget

- `IterationBudget` is re-created at the start of each turn from `AgentRuntime.maxIterations`.
- The loop continues while `api_call_count < maxIterations && budget.remaining > 0`, plus one optional `_budgetGraceCall`.
- `consume()` returns false when budget is exhausted.
- `refund()` is used:
  - When an Ollama context-too-small error aborts before the API call completes.
  - When the only tool called in a turn is `execute_code` (cheap programmatic call).
  - After a compression restart so the same user-visible iteration is not double-counted.

Critical behavior: budget exhaustion does not immediately terminate; the grace call gives the model one extra chance to produce a final answer.

---

## 5. Context Compression

Responsibility: `com.azhukov.agent.core.context.ContextCompressor`

### 5.1 Triggers

1. **Preflight** (`TurnContextBuilder`): before the first API call, if `len(messages) > protectFirstN + protectLastN + 1` and estimated tokens exceed the threshold.
2. **Post-response** (`TurnOrchestrator`): after tool results are appended, if `should_compress(realPromptTokens)` is true.
3. **Error-driven** (`ApiErrorRecoveryHandler`): on 413 / context-overflow errors, up to `maxCompressionAttempts` (3).

### 5.2 Key Rules

- Use real provider-reported `promptTokens` when available; fall back to rough request-token estimate only when no usage was returned.
- Threshold is a fraction of the model's context length (default 50%).
- Protect the first N and last N messages; summarize the middle.
- After compression, clear `conversationHistory`, invalidate the cached system prompt, and reset empty-response retry counters.
- Honor `compressionEnabled = false`: overflow errors become terminal instead of triggering automatic compression.

### 5.3 Provider Context Length

- `ContextCompressor` stores `contextLength` and `thresholdTokens`.
- Provider errors may report a lower real context length; parse and call `update_model(...)`.
- Persist discovered limits only when the provider explicitly reports them, not from guessed probe tiers.

---

## 6. Prompt Construction

Responsibility split:

| Layer | Java Class | What it builds |
|-------|-----------|----------------|
| Stable system prompt | `SystemPromptBuilder` | Identity, universal tool guidance, skill instructions, environment/platform hints, model-family operational rules. |
| Context system prompt | `SystemPromptBuilder` | Caller's `systemMessage` + context files under `TERMINAL_CWD`. |
| Volatile system prompt | `SystemPromptBuilder` | Memory, user profile, external memory block, timestamp/session/model/provider line. |
| API message assembly | `ContextAssembler` | Final `List<Message>` for the provider: system + prefill + history-with-reasoning, with ephemeral context injected into the current user message only. |
| Prompt formatting helpers | `PromptBuilder` | Code fences, steer markers, continuation prompts. |

### Invariants

- The system prompt is a single stable content string, byte-stable across turns (date-only timestamp).
- Plugin/`pre_llm_call` context is injected into the **user message**, never the system prompt, to preserve prefix-cache warmth.
- Internal fields (`reasoning`, `finishReason`, `_thinkingPrefill`, `codex` IDs) are stripped from the API copy; reasoning is copied to `reasoningContent` for providers that need it.
- Tool-call argument JSON is normalized (compact, sorted keys) for consistent prefix matching.

---

## 7. Critical Invariants

1. **System prompt caching**: built once per session; invalidated only by compression. Byte stability matters for upstream KV/prefix caches.
2. **Role alternation**: `MessageSanitizer` must guarantee valid `user → assistant → tool → assistant …` sequences before every API call.
3. **Tool result pairing**: every assistant message with `toolCalls` must be followed by one `tool` result per `toolCallId`.
4. **Reasoning echo-back**: some providers (DeepSeek v4 thinking, Kimi/Moonshot, MiMo) require `reasoningContent` on assistant tool-call replays.
5. **Thinking-only assistant turns**: must be dropped from the API copy (not persisted history) to avoid Anthropic 400s.
6. **Iteration budget**: created per turn; `execute_code`-only turns are refunded; compression restarts refund the consumed iteration.
7. **Auto-compaction respect**: if `compressionEnabled` is false, overflow errors must be terminal, never silent compression.
8. **Session persistence**: persist after dropping empty-response scaffolding (`_emptyTerminalSentinel`, `_thinkingPrefill`, `_emptyRecoverySynthetic`).
9. **Interrupt scoping**: interrupt signal is scoped to the agent's execution thread only.
10. **Surrogate/non-ASCII sanitization**: sanitize messages, tool schemas, prefill messages, headers, and API key before retransmission on `UnicodeEncodeError`.

---

## 8. Suggested Java Package Structure

```
com.azhukov.agent.core
├── AgentRuntime                 # main session state / entry point
├── config
│   ├── AgentConfig
│   ├── ReasoningConfig
│   └── FallbackConfig
├── model
│   ├── Message
│   ├── AssistantMessage
│   ├── ToolCall
│   ├── ToolResult
│   ├── TurnResult
│   ├── TokenUsage
│   ├── CostEstimate
│   └── Trajectory (message + list)
├── turn
│   ├── TurnOrchestrator
│   ├── TurnContext
│   ├── TurnContextBuilder
│   ├── TurnFinalizer
│   └── TurnRetryState
├── prompt
│   ├── SystemPromptBuilder
│   └── PromptBuilder
├── context
│   ├── ContextAssembler
│   └── ContextCompressor
├── transport
│   ├── ApiRequestBuilder
│   ├── StreamingApiClient
│   ├── Transport (normalize/validate interface)
│   └── chat/anthropic/bedrock/codex implementations
├── tool
│   ├── ToolRegistry
│   ├── ToolExecutor
│   ├── ToolInvoker
│   ├── ToolResultFormatter
│   └── ToolCallPreprocessor
├── message
│   └── MessageSanitizer
├── budget
│   └── IterationBudget
├── error
│   ├── FailoverReason
│   ├── ApiErrorClassifier
│   └── ApiErrorRecoveryHandler
└── util
    └── JsonNormalizer / Utf8Sanitizer
```

---

## 9. What to Port First

Priority order for a minimal working Java agent core:

1. `model.*` records and `Message` role/content handling.
2. `config.AgentConfig` and `AgentRuntime` state holder.
3. `budget.IterationBudget`.
4. `tool.ToolRegistry` + at least `read_file`, `write_file`, `patch`, `terminal`, `execute_code`.
5. `turn.TurnContext`, `TurnContextBuilder`, `TurnOrchestrator`, `TurnFinalizer`.
6. `prompt.SystemPromptBuilder` and `context.ContextAssembler`.
7. `transport` abstraction for OpenAI-compatible chat completions.
8. `context.ContextCompressor` with a simple summarization strategy.
9. `message.MessageSanitizer` and `error.ApiErrorClassifier`/`ApiErrorRecoveryHandler`.
10. `model_tools` equivalent for tool schema discovery.

Optional/later: streaming display, Anthropic/Bedrock/Codex transports, credential pools, plugin hooks, memory managers, credits/rate-limit tracking, multimodal image shrinking, kanban integration.
