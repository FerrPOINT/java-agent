# 07 — Application Design: Complete Agent Architecture

Target stack: Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + OpenAI-compatible LLM endpoint.

## 1. Naming Rule

- Project name: **Java Agent**.
- Code/config: use **agent** instead of *Hermes* or *гермес*.
- Upstream reference: `https://github.com/NousResearch/hermes-agent` is kept as-is in the link label.

## 2. Runtime: Single Conversation Flow

```
┌─────────────┐     user message      ┌──────────────────┐
│ CLI / HTTP  │ ───────────────────▶ │   AgentRuntime   │
│   / REPL    │                       │  (one session)   │
└─────────────┘                       └────────┬─────────┘
                                               │
        ┌────────────────────────────────────┼────────────────────────────────────┐
        ▼                                      ▼                                    ▼
┌───────────────┐                  ┌─────────────────┐                 ┌───────────────┐
│ PromptBuilder │                  │ ContextEngine   │                 │ MemoryManager │
│ system prompt │                  │ budget + comp.  │                 │ user facts    │
└───────┬───────┘                  └─────────────────┘                 └───────┬───────┘
        │                                                                     │
        │                                                                     │
        ▼                                                                     │
┌───────────────┐      tools + messages + memory                           │
│   ModelClient  │ ◀──────────────────────────────────────────────────────────┘
│ OpenAI fmt     │
└───────┬───────┘
        │
        ▼
┌───────────────┐     tool calls     ┌───────────────┐     tool results    ┌───────────────┐
│ ToolCall list │ ─────────────────▶ │  ToolExecutor │ ─────────────────▶ │  ToolResult   │
└───────────────┘                    │  / Registry   │                     └───────┬───────┘
                                   └───────────────┘                             │
                                                                                 │
                                                                                 ▼
                                                                           ┌───────────────┐
                                                                           │ next model call│
                                                                           │ or final text  │
                                                                           └───────────────┘
```

## 3. Core Components

### 3.1 `com.azhukov.agent.core.agent.AgentRuntime`

- Owns one conversation turn.
- Entry: `TurnResult runTurn(Session session, String userInput)`.
- Alternates: model call → tool execution → model call.
- Enforces `IterationBudget`.
- Handles `ModelClient` errors, retries, fallback.
- Calls `ContextCompressor` when needed.
- Ensures role alternation before each API call.

### 3.2 `com.azhukov.agent.core.client.ModelClient`

```java
public interface ModelClient {
    ChatResponse complete(List<Message> messages, List<ToolDefinition> tools);
}
```

- Implementation: `OpenAiCompatibleClient` using `langchain4j-open-ai`.
- Supports base URL override, model name, temperature, top-p.
- Returns content and/or `ToolCall` list.

### 3.3 `com.azhukov.agent.core.tool.ToolRegistry`

- Scans for `@AgentTool` annotated Spring beans.
- Provides `ToolDefinition` list for model calls.
- Dispatches `ToolCall` to correct handler.

### 3.4 `com.azhukov.agent.core.tool.ToolExecutor`

- Validates JSON arguments with Jackson.
- Runs the handler, catches exceptions.
- Bridges blocking tools via virtual threads.
- Returns `ToolResult` with content or error.

### 3.5 `com.azhukov.agent.core.prompt.PromptBuilder`

- Builds system prompt from template, skills, memory.
- Uses Pebble templates.
- Caches resolved system message per session.

### 3.6 `com.azhukov.agent.core.context.ContextEngine`

- Interface + default `ContextCompressor`.
- Decides when to compress (token count, message count).
- Summarizes oldest half of conversation.

### 3.7 `com.azhukov.agent.core.memory.MemoryManager`

- Persists facts and context.
- Default: `PostgresMemoryProvider`.
- Interface: `MemoryProvider`.

### 3.8 `com.azhukov.agent.core.skill.SkillManager`

- Loads skills from `~/.java-agent/skills/`.
- Parses `SKILL.md` + frontmatter.
- Exposes `skills_list`, `skill_view`, `skill_manage`.

## 4. Message Model

```java
public sealed interface Message
    permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage {
    Role role();
}

public record ToolCall(String id, String name, String arguments) {}
public record ToolResult(String toolCallId, String content, boolean error) {}
public record ToolDefinition(String name, String description, JsonNode schema) {}
public record ChatResponse(String content, List<ToolCall> toolCalls) {}
```

## 5. Built-in Tools

| Tool | Class | Notes |
|------|-------|-------|
| `read_file` | `ReadFileTool` | `PathSecurity` check |
| `write_file` | `WriteFileTool` | atomic write |
| `patch` | `PatchTool` | fuzzy replace |
| `search_files` | `SearchFilesTool` | ripgrep / file walk |
| `terminal` | `TerminalTool` | `ProcessBuilder` |
| `process` | `ProcessTool` | background process manager |
| `web_search` | `WebSearchTool` | SearXNG / DDGS |
| `web_extract` | `WebExtractTool` | readability / jsoup |
| `vision_analyze` | `VisionAnalyzeTool` | base64 + `ModelClient` |
| `memory` | `MemoryTool` | store/retrieve facts |
| `skills_list` | `SkillsListTool` | list loaded skills |
| `skill_view` | `SkillViewTool` | read SKILL.md |
| `skill_manage` | `SkillManageTool` | create/update skill |
| `todo` | `TodoTool` | session tasks |
| `session_search` | `SessionSearchTool` | search past sessions |

## 6. Browser Tools (CDP)

| Tool | Class |
|------|-------|
| `browser_navigate` | `BrowserNavigateTool` |
| `browser_snapshot` | `BrowserSnapshotTool` |
| `browser_click` | `BrowserClickTool` |
| `browser_type` | `BrowserTypeTool` |
| `browser_scroll` | `BrowserScrollTool` |
| `browser_back` | `BrowserBackTool` |
| `browser_press` | `BrowserPressTool` |
| `browser_console` | `BrowserConsoleTool` |
| `browser_get_images` | `BrowserGetImagesTool` |
| `browser_vision` | `BrowserVisionTool` |

### Browser Architecture

```
BrowserPool (Spring singleton)
  └── ChromiumLauncher (ProcessBuilder)
        └── Chrome --remote-debugging-port=9222
  └── CdpClient (WebSocket to ws://localhost:9222/devtools/page/...)
        └── BrowserSession per taskId
```

- No Playwright in core.
- Screenshot via `Page.captureScreenshot`.
- Snapshot via `Accessibility.getFullAXTree` + ref labels.

## 7. Configuration (`AgentProperties`)

```yaml
agent:
  name: ${AGENT_NAME:Джава агент}
  max-turns: 25
  max-iterations: 100
  reasoning:
    enabled: false
  model:
    provider: openai-compatible
    base-url: ${AGENT_MODEL_BASE_URL:http://localhost:11434/v1}
    api-key: ${AGENT_MODEL_API_KEY:}
    model-name: ${AGENT_MODEL_NAME:qwen2.5:3b}
    timeout-seconds: 60
    max-retries: 2
  vision:
    model-name: ${AGENT_VISION_MODEL_NAME:}
    max-download-bytes: 52428800
    download-timeout-seconds: 30
  browser:
    cdp-url: ${AGENT_BROWSER_CDP_URL:}
    default-timeout-ms: 30000
  memory:
    enabled: true
  skills:
    path: ${AGENT_SKILLS_PATH:${user.home}/.java-agent/skills}
  security:
    approval-required: false
```

## 8. Persistence Schema

Tables managed by Flyway:

```sql
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id TEXT,
    name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT,
    tool_calls JSONB,
    tool_call_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE memory (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL,
    fact TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE todos (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID,
    content TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE skills (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    content TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 9. Spring Boot Module Layout (Current → Future)

### Current (single module under `backend/`)

```
java-agent/
├── backend/
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/com/azhukov/agent/
├── docs/
└── prototype/
```

### Future split

```
java-agent/
├── agent-core/          // pure Java, no Spring
├── agent-cli/           // picocli + JLine REPL
├── agent-gateway/       // HTTP / WebSocket adapters
└── agent-spring-boot-starter/
```

## 10. CLI Entry Points

- `java -jar agent.jar` — starts web server.
- `java -jar agent.jar repl` — starts interactive REPL.
- `java -jar agent.jar tools` — lists registered tools.
- `java -jar agent.jar skill view <name>` — views a skill.

Implemented with Picocli subcommands.

## 11. Security

- `PathSecurity`: normalize, realpath, check working directory.
- `ApprovalGate`: configurable per-tool approval.
- `Redactor`: remove secrets before memory egress.
- Browser: sandboxed Chromium, allow-list navigation.

## 12. Observability

- SLF4J + Logback with MDC for `sessionId`.
- Spring Boot Actuator health endpoint.
- Optional Micrometer metrics later.

## 13. Testing Strategy

- Unit: registry, prompt builder, message sanitizer, budget.
- Integration: one turn with mocked `ModelClient`.
- E2E: real Ollama + `read_file` in temp directory.
- Testcontainers for PostgreSQL integration tests.

## 14. Success Criteria

1. Start REPL.
2. User asks: "Read /tmp/hello.txt".
3. Agent emits `read_file` tool call.
4. Tool returns content.
5. Agent answers with file content.
6. Output contains only actionable text.
