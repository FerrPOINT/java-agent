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

## 9. Layered Architecture: Controller → Service → Repository

All server-side code follows a strict three-layer model. No layer bypasses the layer below it.

```
┌─────────────────────────────────────────────────────────────┐
│  Presentation Layer                                          │
│  - Controllers / CLI / Gateway adapters                      │
│  - DTO in, DTO out                                           │
│  - No business logic, only routing and HTTP/CLI mapping       │
└───────────────────────────────┬─────────────────────────────┘
                                │ calls
┌───────────────────────────────▼─────────────────────────────┐
│  Service Layer                                               │
│  - AgentRuntimeService, ToolService, SessionService         │
│  - Business logic, transactions, orchestration             │
│  - Uses repositories and core runtime                      │
└───────────────────────────────┬─────────────────────────────┘
                                │ calls
┌───────────────────────────────▼─────────────────────────────┐
│  Repository / Infrastructure Layer                           │
│  - JPA Repositories, JDBC templates, Flyway                │
│  - File system, WebSocket CDP client, process manager        │
│  - External clients (Ollama, web search, MCP)              │
└─────────────────────────────────────────────────────────────┘
```

### 9.1 Rules

| Rule | Description |
|------|-------------|
| Controller calls only Service | Never repository or core runtime directly. |
| Service is transaction boundary | Use `@Transactional` at service methods. |
| Service calls Repository and domain objects | Domain (`AgentRuntime`, `ToolExecutor`) is injected into service. |
| Repository returns entities / raw data | No business logic in repositories. |
| DTOs live in `api.dto.*` | Never expose internal `Message`/`ToolCall` models directly. |
| No layer skips | Controller → Service → Repository. Exception: health checks may call actuator directly. |

### 9.2 Controller Layer

```
backend/src/main/java/com/azhukov/agent/api/
├── AgentController.java              # POST /api/v1/agent/chat
├── SessionController.java              # GET/POST /api/v1/sessions
├── ToolController.java                 # GET /api/v1/tools
├── SkillController.java                # GET /api/v1/skills
├── BrowserController.java              # POST /api/v1/browser/{action}
├── OpenAiCompatibleController.java     # POST /v1/chat/completions
└── dto/
    ├── ChatRequest.java
    ├── ChatResponse.java
    ├── SessionDto.java
    └── ToolCallResultDto.java
```

Controllers only:
- Validate input (`@Valid`).
- Convert DTO → service command.
- Call service method.
- Convert result → response DTO.
- Handle exceptions via `@ControllerAdvice`.

### 9.3 Service Layer

```
backend/src/main/java/com/azhukov/agent/service/
├── AgentRuntimeService.java            # orchestrates a turn
├── SessionService.java                 # session CRUD + cleanup
├── ToolService.java                    # list tools, execute single tool call
├── MemoryService.java                  # memory facts CRUD
├── SkillService.java                   # skill CRUD + load
├── BrowserService.java                 # browser lifecycle + actions
├── ModelClientService.java             # wraps ModelClient + fallback
└── command/
    ├── RunTurnCommand.java
    ├── ExecuteToolCommand.java
    └── CreateSessionCommand.java
```

Service methods are the transaction boundary and the only place where multiple repositories/runtime objects are composed.

Example:

```java
@Service
@RequiredArgsConstructor
public class AgentRuntimeService {

    private final AgentRuntime agentRuntime;
    private final SessionService sessionService;
    private final ToolService toolService;
    private final MemoryService memoryService;

    @Transactional
    public TurnResultDto runTurn(RunTurnCommand command) {
        Session session = sessionService.getOrCreate(command.sessionId());
        TurnResult result = agentRuntime.runTurn(session, command.userInput());
        sessionService.saveMessages(session, result.messages());
        return TurnResultDto.from(result);
    }
}
```

### 9.4 Repository Layer

```
backend/src/main/java/com/azhukov/agent/repository/
├── SessionRepository.java            # JpaRepository<SessionEntity, UUID>
├── MessageRepository.java            # JpaRepository<MessageEntity, Long>
├── MemoryRepository.java               # JpaRepository<MemoryEntity, Long>
├── TodoRepository.java                 # JpaRepository<TodoEntity, Long>
├── SkillRepository.java                # JpaRepository<SkillEntity, Long>
├── entity/
│   ├── SessionEntity.java
│   ├── MessageEntity.java
│   ├── MemoryEntity.java
│   ├── TodoEntity.java
│   └── SkillEntity.java
└── jdbi/                               # optional complex SQL
    └── SessionSearchDao.java
```

Repositories:
- Extend `JpaRepository` or use `JdbcClient` for complex queries.
- Map entities to/from domain models in service layer (not in repository).
- No `@Transactional` on repositories (handled by service).

### 9.5 Domain / Core Layer (independent of Spring)

```
backend/src/main/java/com/azhukov/agent/core/
├── agent/
│   └── AgentRuntime.java
├── client/
│   └── ModelClient.java
├── tool/
│   ├── ToolRegistry.java
│   └── ToolExecutor.java
├── prompt/
│   └── PromptBuilder.java
├── context/
│   └── ContextEngine.java
├── memory/
│   └── MemoryProvider.java
└── skill/
    └── SkillManager.java
```

Domain layer:
- Has no Spring annotations.
- Receives primitives and domain models only.
- Can be unit-tested without Spring context.

### 9.6 CLI / REPL Layer

```
backend/src/main/java/com/azhukov/agent/cli/
├── AgentCli.java                       # Picocli entry point
├── Repl.java                           # JLine interactive loop
├── commands/
│   ├── ChatCommand.java
│   ├── ToolsCommand.java
│   ├── SkillCommand.java
│   └── BrowserCommand.java
└── cli/CliSessionService.java          # thin wrapper around SessionService
```

CLI commands call services, not repositories directly.

### 9.7 Package Dependencies

Allowed dependencies:

```
api ──▶ service ──▶ repository ──▶ entity
service ──▶ core
cli ──▶ service
gateway ──▶ service
```

Forbidden:

```
api ──▶ repository
api ──▶ core
repository ──▶ service
core ──▶ service / repository / api
```

### 9.8 Example: Read File Turn

```
POST /api/v1/agent/chat
  ChatRequest{sessionId, message}
    │
    ▼
AgentController.runTurn(ChatRequest)
    │
    ▼
AgentRuntimeService.runTurn(RunTurnCommand)
    │
    ├── SessionService.getOrCreate(sessionId)
    │       └── SessionRepository.findById(...)  (Repository)
    │
    ├── AgentRuntime.runTurn(session, message)     (Core)
    │       ├── PromptBuilder.build(...)           (Core)
    │       ├── ModelClient.complete(...)          (Core)
    │       └── ToolExecutor.execute(...)          (Core)
    │             └── ReadFileTool.execute(...)    (Core)
    │
    └── SessionService.saveMessages(session, messages)
            └── MessageRepository.saveAll(...)     (Repository)
    │
    ▼
  ChatResponse{content, toolCalls}
```

## 10. Spring Boot Module Layout (Current → Future)

### Current (single module under `backend/`)

```
java-agent/
├── backend/
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/java/com/azhukov/agent/
│       ├── api/           # controllers + DTOs
│       ├── service/       # service layer
│       ├── repository/    # repository + entities
│       ├── core/          # domain layer
│       ├── cli/           # Picocli / JLine
│       ├── config/        # AgentProperties, beans
│       └── JavaAgentApplication.java
├── docs/
└── prototype/
```

### Future split

```
java-agent/
├── backend/               # Spring Boot app (api + service + repository)
├── agent-core/            # pure Java domain
├── agent-cli/             # Picocli + JLine
├── agent-gateway/         # HTTP / WebSocket adapters
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
