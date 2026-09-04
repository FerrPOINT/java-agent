# ADR-008: Agentic Loop Deduplication

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-08-12 |
| **Deciders** | Project lead |
| **Tags** | architecture, agent, refactoring |

## Context

The agent loop (tool execution → LLM call → response) existed in two places:

1. `AgentRuntimeService.runTurn()` — synchronous path, returns a complete response.
2. `AgentStreamingService.streamTurn()` — streaming path, emits SSE events.

Both implementations had their own copy of the tool execution loop, memory sync, and turn management logic. Over time, they drifted: the streaming path had cursor/heartbeat events that the sync path lacked; the sync path had approval gate logic that the streaming path missed. Bugs fixed in one path were often not fixed in the other.

## Decision

Extract the shared loop logic into `TurnExecutor`, used by both `AgentRuntimeService` and `AgentStreamingService`. The executor handles:

- Tool execution (parallel via virtual threads).
- Memory sync (async, after turn).
- Turn counting and budget enforcement.
- Approval gate checks.

The two services differ only in how they deliver results: sync returns a `ChatResponseDto`, streaming emits SSE events. The core loop is identical.

## Consequences

**Positive:**

- Single source of truth for agent loop logic — no behavioral drift.
- Bug fixes apply to both paths automatically.
- Easier to reason about the agent's behavior.
- New features (e.g., metrics) added once, apply to both paths.

**Negative:**

- The `TurnExecutor` is a critical shared component — changes affect both paths.
- Slightly more indirection (service → executor → tools).
- Streaming-specific concerns (cursor, heartbeat) must be handled outside the executor, in the streaming service.
