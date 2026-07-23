# 05 — Migration Notes & Tricky Parts

This document captures non-obvious translation issues when moving Hermes from Python to Java.

## 1. Dynamic Tool Discovery

**Python:** Hermes uses `tools/registry.py` to AST-scan `tools/*.py`, then `importlib.import_module()` at runtime. Each module self-registers with `registry.register()`.

**Java challenge:** Java is statically typed; modules are classes, not files. Options:
1. **Annotation + classpath scanning** at runtime (ClassGraph or Spring `ClassPathScanningCandidateComponentProvider`).
2. **Annotation processor** at compile time to generate a `META-INF/services/com.nous.hermes.core.tool.Tool` registry file.
3. **Explicit configuration** in `application.yml` for the prototype.

**Recommendation:** Start with explicit configuration for predictability, then add annotation scanning.

## 2. Async Bridging

**Python:** Hermes has `_tool_loop`, `_worker_thread_local`, and `asyncio.run()` bridges because many tools are sync (file, terminal) but the agent loop is async.

**Java:** Virtual threads (`Thread.startVirtualThread(...)` or `Executors.newVirtualThreadPerTaskExecutor()`) remove most of the async/sync friction. Use `CompletableFuture` only where you need composition or timeouts. Avoid `CompletableFuture` explosion; keep code linear.

## 3. OpenAI-Compatible Tool Schema

Hermes builds JSON schemas for tools and sends them in the OpenAI `tools` field. Java must produce identical schemas.

**Options:**
- Jackson POJOs + `jsonschema-generator` (Victools).
- Manual schema builder.
- LangChain4j `ToolSpecification` builder.

**Tricky detail:** OpenAI requires `type`, `properties`, `required`, and nested object handling. Some providers (Ollama, Gemini) are stricter. Test against the real target provider.

## 4. Prompt Caching & System Prompt Stability

**Hermes rule:** system prompt must not change mid-conversation unless compression occurs. Toolset changes invalidate the cache.

**Java implication:** Store the resolved `systemMessage` in `Session`. Recompute only when:
- New skills are added explicitly.
- Context compression fires.
- Toolset configuration changes (rare; should require new session).

## 5. Message Role Alternation

OpenAI format forbids consecutive messages with the same role. Hermes sanitizes by merging adjacent same-role messages or injecting empty assistant/tool messages.

**Java implementation:** `MessageSanitizer` should validate and fix before every API call.

## 6. Tool Result Attachments

OpenAI format expects:
- Assistant message with `tool_calls`.
- One or more `tool` role messages with `tool_call_id`.

**Java implementation:** `ToolResultMessage` must carry `toolCallId`. The runtime appends results before the next model call.

## 7. Terminal Tool

**Python:** Uses `ptyprocess` / `pywinpty` for pseudo-terminal semantics.

**Java:** `ProcessBuilder` gives a plain process, not a PTY. For prototype, implement:
- `terminal` → runs command with `ProcessBuilder`, streams stdout/stderr.
- `read_terminal` → reads buffered output.
- `close_terminal` → destroys process.

If true PTY is needed later, use JNA/JNR to call `forkpty` or embed a small native helper.

## 8. File Path Security

Hermes has `path_security.py` and `file_safety.py` to prevent escaping working directory and reading secrets.

**Java:** Implement `PathSecurity` with `Path.normalize()` and `Path.toRealPath()` checks. Maintain allow-list/deny-list (e.g., `.env`, `*.pem`).

## 9. Patch Tool

Hermes `patch` tool uses a custom parser that applies find-and-replace edits. It is not a unified-diff engine.

**Java:** Port the parser carefully. Test with Python test cases from `prototype/hermes-agent/tools/file_operations.py` and related tests. This is a frequent failure point.

## 10. Context Compression

`context_compressor.py` is large (~228 KB). It summarizes older messages and rewrites the conversation.

**Java strategy:**
- Implement a simple summarizer first: when token count > threshold, ask the model to summarize the oldest half.
- Keep full messages for the recent window.
- Add sliding-window fallback.

Do not port all compression heuristics in the first iteration.

## 11. Memory Provider Interface

Hermes supports multiple memory backends (Honcho, Supermemory, mem0, SQLite) via `agent/memory_provider.py`.

**Java:** Define `MemoryProvider` interface with methods:
```java
List<String> getFacts(String userId, String sessionId, String query);
void addFact(String userId, String sessionId, String fact);
```
Implement `SqliteMemoryProvider` first; add REST-backed providers later.

## 12. Skills

Hermes skills are Markdown files + optional scripts/templates. The agent calls `skill_view` to load instructions.

**Java:** Store skills as classpath resources or files under `~/.hermes/skills/`. Load `SKILL.md` as a string. If a skill references a script, execute it as a tool if the runtime supports it.

## 13. MCP Integration

Hermes `tools/mcp_tool.py` starts MCP servers (stdio or SSE) and exposes their tools.

**Java:** Use `io.modelcontextprotocol.sdk:mcp`. The SDK supports stdio and SSE transports. Wrap each MCP tool as a `ToolDefinition` in the registry.

**Challenge:** MCP servers are separate processes. Java must manage process lifecycle and restart on crash.

## 14. ACP (Agent Client Protocol)

ACP is an emerging protocol for editors/IDEs to drive agents. Hermes has `acp_adapter/`.

**Java:** No official stable Java SDK yet. Study `acp_adapter/server.py` and implement the JSON-RPC-like surface manually, or wait for JetBrains/Zed SDK.

## 15. Configuration Compatibility

Hermes uses `~/.hermes/config.yaml` and `.env` for secrets. Java Spring Boot uses `application.yml` and env vars.

**Recommendation:** Write a loader that reads `~/.hermes/config.yaml` and maps it to `HermesProperties`. Keep secrets in env vars only. Do not write secrets to YAML.

## 16. Logging

Hermes uses Python logging + `concurrent-log-handler` on Windows.

**Java:** Use SLF4J + Logback. Use `Mapped Diagnostic Context` (MDC) for `sessionId` and `taskId`. Keep logs structured (JSON optionally).

## 17. Testing Isolation

Hermes tests rely on monkeypatching module-level globals (e.g., `_db`, `_srv`). In Java this is harder.

**Strategy:**
- Use constructor injection everywhere.
- Provide `FakeModelClient`, `FakeMemoryProvider`, `InMemoryToolRegistry` for tests.
- Avoid static mutable state.

## 18. Gateway Session Management

Hermes gateway maps each platform conversation to an `AgentRuntime` session. Sessions expire after inactivity.

**Java:** Use a `ConcurrentHashMap<String, Session>` with scheduled cleanup. Be careful with thread-safety: one `AgentRuntime` per session; do not share mutable state.

## 19. Native Code & Sandboxing

Hermes has `native/fts5_cjk/` (SQLite FTS5 extension) and OpenShell integration.

**Java:**
- FTS5 extension is SQLite-specific. For search, use H2 full-text or Lucene later.
- Sandboxing: start with `ProcessBuilder` security and file-system allow-lists. True seccomp/OpenShell integration is advanced; defer.

## 20. Notable Files to Deep-Dive

When implementing each module, read these Python files first:

| Module | Key files in `prototype/hermes-agent/` |
|--------|----------------------------------------|
| Runtime | `run_agent.py`, `agent/conversation_loop.py`, `agent/tool_executor.py` |
| Tools | `tools/registry.py`, `model_tools.py`, `toolsets.py`, `tools/file_operations.py`, `tools/terminal_tool.py`, `tools/vision_tools.py`, `tools/browser_tool.py` |
| Prompts | `agent/prompt_builder.py` |
| Context | `agent/context_engine.py`, `agent/context_compressor.py` |
| Memory | `agent/memory_manager.py`, `agent/memory_provider.py` |
| Skills | `agent/skill_*.py`, `tools/skills_tool.py` |
| Security | `tools/path_security.py`, `tools/file_safety.py`, `tools/approval.py` |
| Gateway | `gateway/run.py`, `gateway/session.py`, `gateway/delivery.py` |
| MCP | `tools/mcp_tool.py`, `mcp_serve.py` |
| ACP | `acp_adapter/server.py`, `acp_adapter/tools.py` |

## 21. Success Criteria for Prototype

1. Start a REPL.
2. User asks: "Read the contents of /tmp/hello.txt".
3. Agent sends a tool call to `read_file` with correct path.
4. Tool executes and returns content.
5. Agent returns a final answer based on file content.
6. No boilerplate, no internal codes, immediate actionable output.

This single happy path validates: model client, tool registry, tool executor, message serialization, prompt builder, and runtime loop.

## 22. Vision & Browser Migration Notes

### 22.1 Vision

- `tools/vision_tools.py` downloads image, encodes base64, sends to auxiliary vision router.
- In Java: `VisionAnalyzeTool` uses the same `ModelClient` with `ImageContent`.
- Default model: `kimi-k2.7-code` via OpenAI-compatible endpoint at `api.moonshot.ai`.
- Fallback chain: main provider (if vision-capable) → Kimi → OpenRouter → Anthropic → custom endpoint.
- Security: cap download size, check URL policy, redact credentials.

### 22.2 Browser

- `tools/browser_tool.py` uses `agent-browser` Node wrapper + cloud providers. For Java prototype use direct CDP.
- `tools/browser_cdp_tool.py` and `tools/browser_supervisor.py` handle persistent CDP websocket, dialogs, frames.
- In Java: `CdpClient` opens WebSocket to `http://localhost:9222`, sends JSON-RPC CDP commands.
- `BrowserPool` launches/destroys Chromium processes per `taskId`.
- `BrowserSnapshot` calls `Accessibility.getFullAXTree` or `DOMSnapshot.captureSnapshot` and renders text refs like `@e1`.
- `BrowserVisionTool` captures screenshot via `Page.captureScreenshot`, then calls `VisionAnalyzeTool`.

### 22.3 Browser Dependencies

| Need | Java library |
|------|--------------|
| CDP WebSocket | `java.net.http.WebSocket` or Tyrus |
| JSON-RPC over CDP | manual (small) |
| Chromium launch | `ProcessBuilder` with `google-chrome --remote-debugging-port=9222` or `org.seleniumhq.selenium:chrome-driver` helper |
| Screenshot decode | `javax.imageio.ImageIO` |
| Base64 | `java.util.Base64` |

### 22.4 Browser Config

- `hermes.browser.local.executable`
- `hermes.browser.local.headless=true`
- `hermes.browser.local.args=--no-sandbox,--disable-gpu`
- `hermes.browser.cdp-url` (external)
- `hermes.browser.timeout`

### 22.5 Browser Security

- Run Chromium in headless + sandbox.
- Avoid `--no-sandbox` unless inside container without user namespaces.
- Restrict navigation via allow-list.
- Redact CDP URL credentials.
- Clean up processes in `BrowserPool.close()` and JVM shutdown hook.
