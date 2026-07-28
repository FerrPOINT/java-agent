# 02 — Core Architecture for Java Port

This document describes the essential data flow and module responsibilities for a Java reimplementation of agent. It mirrors the Python design but replaces Python-specific patterns with JVM idioms.

## 1. Runtime Data Flow (One Turn)

```
┌─────────────────┐     user text      ┌──────────────────┐
│   CLI / Web /   │ ────────────────▶ │   AgentRuntime   │
│   Gateway       │                   │  (conversation   │
└─────────────────┘                   │  loop + executor)│
                                      └────────┬─────────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    ▼                          ▼                          ▼
           ┌─────────────┐          ┌─────────────────┐          ┌──────────────┐
           │ PromptBuilder│          │ ContextEngine   │          │ MemoryManager │
           │ system+user  │          │ token budget +  │          │ user facts    │
           └──────┬───────┘          │ compression     │          └──────┬───────┘
                  │                  └─────────────────┘               │
                  │                                                   │
                  ▼                                                   │
           ┌─────────────┐                                          │
           │ ModelClient   │ ◀──────── context + memory ──────────────┘
           │ (OpenAI fmt)  │
           └──────┬────────┘
                  │
                  ▼
           ┌─────────────┐
           │ ToolCall(s) │
           └──────┬──────┘
                  │
                  ▼
           ┌─────────────┐
           │ ToolExecutor│
           │ registry    │
           └──────┬──────┘
                  │
    ┌───────────────┼───────────────┐
    ▼               ▼               ▼
 ┌───────┐    ┌─────────┐   ┌──────────┐
 │ File  │    │ Terminal│   │  Web     │
 │ tools │    │ tools   │   │  search  │
 └───────┘    └─────────┘   └──────────┘
                  │
                  ▼
           ┌─────────────┐
           │ ToolResult   │
           │ (text + refs)│
           └──────┬──────┘
                  │
                  ▼
           ┌─────────────┐
           │ AgentRuntime│  → next model call or final answer
           └─────────────┘
```

## 2. Module Responsibilities

### 2.1 `AgentRuntime`

- Owns one conversation turn from user input to final response.
- Alternates model calls and tool execution.
- Enforces iteration budget (max tool rounds per turn).
- Handles model errors, retries, fallback providers.
- Calls `ContextEngine.shouldCompress()` after each turn.
- Ensures message-role alternation (no two consecutive user/assistant messages).

Java class draft:

```java
public class AgentRuntime {
    private final ModelClient modelClient;
    private final ToolExecutor toolExecutor;
    private final PromptBuilder promptBuilder;
    private final ContextEngine contextEngine;
    private final MemoryManager memoryManager;
    private final IterationBudget budget;

    public TurnResult runTurn(Session session, String userInput) { ... }
}
```

### 2.2 `ModelClient`

- Thin abstraction over OpenAI-compatible chat completions.
- Supports base URL override, model name, temperature, top-p.
- Returns `ChatResponse` with content and/or `ToolCall` list.
- Must be provider-agnostic: OpenAI, Ollama, Anthropic (via adapter), OpenRouter, etc.

Agent uses the OpenAI SDK with custom adapters (`anthropic_adapter.py`, `bedrock_adapter.py`, `gemini_native_adapter.py`, ...). In Java we can start with a single `OpenAiCompatibleClient` and add provider adapters later.

```java
public interface ModelClient {
    ChatResponse complete(List<Message> messages, List<ToolDefinition> tools);
}
```

### 2.3 `ToolRegistry`

- Scans classpath for tools annotated with `@AgentTool`.
- Each tool declares: name, description, JSON schema, toolset, availability check.
- `ToolExecutor` looks up tool by name and dispatches with parsed JSON args.

Python uses runtime module import + AST prefilter. Java can use:
- Annotation processing at compile time, or
- Reflection + classpath scanning (Spring `ClassPathScanningCandidateComponentProvider`, or ClassGraph).

### 2.4 `ToolExecutor`

- Receives `ToolCall { id, name, arguments }`.
- Validates arguments against schema (Jakarta Validation + Jackson).
- Calls the tool handler.
- Catches exceptions and returns `ToolResult` with success/failure.
- Bridges blocking tools via virtual threads.

### 2.5 `PromptBuilder`

- Constructs system prompt from:
  - Base Agent system prompt template.
  - Active skills summary.
  - Memory context (truncated to ~6k chars).
  - Tool descriptions.
- Uses Pebble templating.
- Must be **byte-stable** for the lifetime of a conversation (Agent rule: prompt caching is sacred).

### 2.6 `ContextEngine`

- Interface with default `ContextCompressor`.
- Methods: `onSessionStart`, `updateFromResponse`, `shouldCompress`, `compress`, `onSessionEnd`.
- In Java: `ContextCompressor` summarizes older messages when token usage exceeds threshold.

### 2.7 `MemoryManager`

- Persists user facts and session context across turns.
- Default PostgreSQL provider; H2 for tests.
- Optional Honcho/Supermemory provider via interface.
- Redacts sensitive text before egress.

### 2.8 `SkillManager`

- Loads skills from filesystem (`~/.java-agent/skills/` or classpath).
- A skill is a directory with `SKILL.md` + optional `references/`, `templates/`, `scripts/`.
- Provides agent-facing tools: `skills_list`, `skill_view`, `skill_manage`.
- In Java: store skills as resources or files; execute scripts via `ToolExecutor` if needed.

### 2.9 `IterationBudget`

- Prevents runaway tool loops.
- Configurable max rounds per turn.
- Tracks count and cost.

### 2.10 `Message` Model

Core envelope types:

```java
public sealed interface Message permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage {
    Role role();
}

public record ToolCall(String id, String name, String arguments) {}
public record ToolResult(String toolCallId, String content, boolean isError) {}
```

## 3. Key Invariants (from AGENTS.md)

1. **Per-conversation prompt caching is sacred.**
   - System prompt must not change mid-conversation unless compressed.
   - Toolset changes mid-conversation invalidate cache — avoid.

2. **Strict role alternation.**
   - Never two `user` or two `assistant` messages in a row.
   - Tool results are usually attached to an `assistant` message as `tool` role (OpenAI format).

3. **Core is a narrow waist.**
   - Add new capability as skills or MCP servers, not core tools.
   - Every core tool ships on every API call; keep the default set small.

4. **Behavior contracts over snapshots.**
   - Tests should assert invariants (e.g., "after N rounds budget is exhausted"), not freeze model outputs.

## 4. Threading Model

- Use **Java 25 virtual threads** for I/O-bound work (model calls, web search, terminal I/O).
- Use `StructuredTaskScope` for parallel tool calls if the model sends multiple tool calls.
- Keep one `AgentRuntime` per conversation session; it is not thread-safe across sessions.

## 5. Configuration

Agent uses `config.yaml` + `.env` for secrets. In Java:
- `application.yml` (Spring Boot style) for behavior settings.
- Environment variables for secrets (`OPENAI_API_KEY`, etc.).
- Optional: `~/.java-agent/config.yaml` loader for compatibility.

## 6. Persistence

- Session DB: PostgreSQL via JDBC; H2 for tests.
- Schema should mirror Agent tables: `sessions`, `messages`, `tool_calls`, `memory`, `skills`.
- Use Flyway or Liquibase for migrations.

## 7. Testing Strategy

- **Unit:** tool registry, prompt builder, message sanitizer, budget.
- **Integration:** one end-to-end turn with mocked `ModelClient`.
- **E2E:** real model call with `read_file` and `terminal` against a temp directory.
- Use Testcontainers if PostgreSQL path is needed later.

## 8. Reference Files (removed)

- `run_agent.py` — high-level agent class.
- `agent/conversation_loop.py` — turn logic.
- `agent/tool_executor.py` — dispatch and async bridging.
- `agent/prompt_builder.py` — prompt construction.
- `agent/context_compressor.py` — compression strategy.
- `agent/memory_manager.py` — memory persistence.
- `tools/registry.py` — tool discovery.
- `model_tools.py` — public API over registry.
- `toolsets.py` — toolset composition.


### Tool groups exposed to the runtime

Core toolset covers: `read_file`, `write_file`, `patch`, `search_files`, `terminal`, `web_search`, `web_extract`, `browser_navigate`, `browser_snapshot`, `browser_click`, `browser_type`, `browser_scroll`, `browser_back`, `browser_press`, `browser_get_images`, `browser_vision`, `browser_console`, `browser_cdp`, `execute_code`, `memory`, `todo`, `session_search`, `skills_list`, `skill_view`, `skill_manage`, `clarify`, `delegate_task`, and dynamic MCP-prefixed tools (`mcp__{server}__{name}`).

Out of scope: `video_analyze`, `computer_use`, voice/TTS, messenger integrations.

