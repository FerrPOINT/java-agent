# 05 — Migration Notes & Tricky Parts

Current stack: Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + OpenAI-compatible LLM endpoint.

This document captures non-obvious translation issues and current design decisions.

## 1. Dynamic Tool Discovery

**Python:** Hermes uses `tools/registry.py` to AST-scan `tools/*.py`, then `importlib.import_module()` at runtime.

**Java:** Tool classes annotated with `@AgentTool` are discovered via Spring classpath scanning at startup (`SpringToolRegistry`). Each tool is a Spring bean; its `@AgentTool` annotation provides name/description, and its `execute(...)` method accepts a JSON string that is deserialized into the tool's args POJO/record.

## 2. Concurrency: Virtual Threads vs Reactive

**Python:** Hermes has `_tool_loop`, `_worker_thread_local`, and `asyncio.run()` bridges because many tools are sync but the loop is async.

**Java:** We use **Spring MVC + virtual threads** (`spring.threads.virtual.enabled=true`) instead of WebFlux because:
- Agent tools are mostly blocking I/O (JDBC, CDP, shell).
- Imperative code is easier to debug and test.
- LangChain4j, JDBC, CDP clients are blocking by default.
- Reactor backpressure is unnecessary for request/response LLM calls.

Use `CompletableFuture` only where composition or timeouts are needed.

## 3. OpenAI-Compatible Tool Schema

Hermes builds JSON schemas for tools and sends them in the OpenAI `tools` field.

**Java implementation:** `SpringToolRegistry.buildDefinition()` introspects `@AgentTool`, `@ToolParam`, and record/POJO fields to produce `ToolDefinition`. Required fields are derived from args class fields.

**Tricky detail:** `@ToolParam` must target `ElementType.RECORD_COMPONENT` to be readable on Java records via `RecordComponent.getAnnotation()`.

## 4. Tool Args Deserialization

Tool args are POJOs/records. `ToolHandler.parseJson()` uses a single `ObjectMapper` with:
- `FAIL_ON_UNKNOWN_PROPERTIES = false`
- `ACCEPT_SINGLE_VALUE_AS_ARRAY = true`
- field visibility `ANY` (so private record fields are populated without getters).

Without field-visibility `ANY`, Jackson cannot deserialize records whose accessor methods are named `command()` instead of `getCommand()`.

## 5. System Prompt First

`DefaultContextEngine.prepareContext()` always places the system message first, then extras (skills/memory/recall), then history, then the current turn. This matches Hermes behavior and prevents the model from ignoring its own name/instructions.

## 6. Message Role Alternation

OpenAI format forbids consecutive messages with the same role. `DefaultAgentRuntime` currently appends `assistant(toolCalls)` followed by `tool` result messages, which satisfies alternation for the tool loop.

## 7. Terminal Tool

**Python:** Uses `ptyprocess` / `pywinpty` for pseudo-terminal semantics.

**Java:** `ProcessBuilder` gives a plain process. We implement:
- `terminal` → runs command with `ProcessBuilder`, streams stdout/stderr.
- `process` → waits for an existing process.

True PTY support is deferred.

## 8. File Path Security

`FileSafety` uses `Path.normalize()` and `Path.toRealPath()` checks plus a deny-list (`.env`, `*.pem`, `.ssh/`).

## 9. Patch Tool

Deferred for Phase 4. The current prototype supports `read_file` and `write_file`; `patch` requires careful find-and-replace semantics matching Hermes.

## 10. Context Compression

Deferred. Current implementation uses a sliding window of the last N messages plus token budget. Summarization via auxiliary model will be added later.

## 11. Memory Provider Interface

```java
List<String> recall(String userId, String query, int limit);
void store(String userId, String category, String fact);
```

`DatabaseMemoryProvider` uses PostgreSQL FTS; `NoOpMemoryProvider` is used when `agent.memory.enabled=false`.

## 12. Skills

Skills are stored as rows in `skills` table. `DatabaseSkillManager` loads/saves Markdown content. `NoOpSkillManager` is used when `agent.skills.enabled=false`.

## 13. MCP Integration

Uses `io.modelcontextprotocol.sdk:mcp:2.0.0`. MCP servers are configured under `agent.mcp.servers` and their tools are registered dynamically at runtime.

## 14. Browser / Vision

- Browser: own Chromium launcher + CDP WebSocket client (`java.net.http.WebSocket`), no Playwright.
- Vision: base64 screenshots sent to a vision-capable OpenAI-compatible model or auxiliary model if `agent.vision.use-auxiliary-first=true`.

## 15. Timeout Strategy

All timeouts increased by an order of magnitude to avoid stalls under real LLM/CDP/browser load:
- model/auxiliary/vision HTTP: 600 s
- `TerminalTool`: 300 s
- `ProcessTool` wait: 1800 s
- `ExecuteCodeTool`: 300 s
- `DelegateTaskTool` sub-agent: 1800 s
- web search/extract: 120 s
- CDP connect/request/operations: 60–120 s

## 16. Dev Launch

Gradle `bootRun` consumes too much memory for real LLM calls and is killed with SIGKILL 137. Use `java -jar` for dev server:

```bash
java -jar build/libs/java-agent-backend-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=dev \
  --spring.datasource.password=project_workflow \
  --server.port=8090
```

## 17. NoOp / Offline Profile

Profile `noop` uses H2 in-memory DB and `NoOpModelClient` so the application starts without PostgreSQL or an API key. Useful for tests and offline development.

## 18. Approval Gate

`ApprovalGate` is in-memory with auto-approve in dev. Persistent approvals and UI are deferred.

## 19. Agent Name

Configurable via `agent.name`; defaults to `Джава агент`.
