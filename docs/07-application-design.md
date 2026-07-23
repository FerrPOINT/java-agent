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
| `execute_code` | `ExecuteCodeTool` | Python sandbox (strict/project mode) |
| `clarify` | `ClarifyTool` | ask user for choice |
| `delegate_task` | `DelegateTaskTool` | subagent spawn (background) |
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
| `browser_cdp` | `BrowserCdpTool` | raw CDP escape hatch |

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

Full configuration tree aligned with the upstream Python agent defaults where applicable.

```yaml
agent:
  name: ${AGENT_NAME:Джава агент}
  max-turns: 90
  max-iterations: 100
  api-max-retries: 3
  verify-on-stop: true
  reasoning:
    enabled: false
    mode: auto                    # auto | forced | disabled
  tool-use-enforcement: auto      # auto | strict | never
  intent-ack-continuation: auto
  task-completion-guidance: true
  parallel-tool-call-guidance: true
  response:
    max-tokens: 4096
    temperature: 0.7
    top-p: 1.0
  system-prompt:
    stable: ${user.home}/.java-agent/SOUL.md
    context: ${user.home}/.java-agent/AGENTS.md
    project: ${user.home}/.java-agent/.java-agent.md
  model:
    provider: openai-compatible
    base-url: ${AGENT_MODEL_BASE_URL:http://localhost:11434/v1}
    api-key: ${AGENT_MODEL_API_KEY:}
    model-name: ${AGENT_MODEL_NAME:qwen2.5:3b}
    timeout-seconds: 60
    max-retries: 2
    context-length: 131072
  vision:
    provider: openai-compatible
    base-url: ${AGENT_VISION_BASE_URL:}
    api-key: ${AGENT_VISION_API_KEY:}
    model-name: ${AGENT_VISION_MODEL_NAME:}
    max-download-bytes: 52428800
    download-timeout-seconds: 30
  auxiliary:
    provider: openai-compatible
    base-url: ${AGENT_AUX_BASE_URL:}
    api-key: ${AGENT_AUX_API_KEY:}
    model-name: ${AGENT_AUX_MODEL_NAME:}
    timeout-seconds: 60
  browser:
    engine: local-chromium        # local-chromium | browserbase | browser-use | camofox
    binary-path: ${AGENT_BROWSER_BINARY_PATH:}
    cdp-url: ${AGENT_BROWSER_CDP_URL:}
    default-timeout-ms: 30000
    navigation-timeout-ms: 60000
    page-load-timeout-ms: 30000
    inactivity-timeout-ms: 120000
    headed: false
    restrict-evaluate: true
    allow-list: []                # URL patterns allowed for navigation
    block-list: []                # URL patterns blocked
  terminal:
    backend: local                # local | docker | ssh | singularity
    timeout-seconds: 180
    dangerous-approval: true
    workdir: ${AGENT_TERMINAL_WORKDIR:}
    pty: false
    allowed-shells: [bash, sh, zsh]
  file:
    read-max-chars: 100000
    blocked-patterns: []
    cross-profile-guard: true
  web:
    search-backend: searxng       # searxng | ddgs | firecrawl | exa | tavily
    extract-backend: parallel     # parallel | firecrawl | tavily | readability
    extract-char-limit: 50000
    cache-enabled: true
  memory:
    enabled: true
    char-limit: 2000
    user-char-limit: 8000
    provider: postgres
  skills:
    enabled: true
    path: ${AGENT_SKILLS_PATH:${user.home}/.java-agent/skills}
  session-search:
    enabled: true
    default-limit: 3
  context:
    compression:
      enabled: true
      threshold-messages: 40
      threshold-tokens: 60000
      keep-first: 4
      keep-last: 12
  security:
    approval-required: false
    dangerous-patterns: []        # additional regexes
    redaction-enabled: true
  delegation:
    enabled: true
    provider: openai-compatible
    model-name: ${AGENT_DELEGATION_MODEL_NAME:}
    max-iterations: 5
  mcp:
    discovery-timeout-seconds: 60
    tool-call-timeout-seconds: 300
    auto-reload-on-config-change: true
    servers: []                   # static server list in config
```

**Environment variables summary:** `AGENT_NAME`, `AGENT_MODEL_BASE_URL`, `AGENT_MODEL_API_KEY`, `AGENT_MODEL_NAME`, `DB_PASSWORD` (used by Spring datasource), `AGENT_SKILLS_PATH`.



### Additional config sections not in the YAML tree above

| Section | Key Hermes defaults | Java mapping |
|---|---|---|
| `terminal` | `backend: local`, `timeout: 180`, `persistent_shell: true`, `cwd: .` | `TerminalProperties` |
| `web` | `backend: ""`, `extract_char_limit: 15000` | `WebProperties` |
| `browser` | `engine: auto`, `cdp_url: ""`, `inactivity_timeout: 120`, `command_timeout: 30`, `allow_private_urls: false` | `BrowserProperties` |
| `memory` | `memory_enabled: true`, `user_profile_enabled: true`, `memory_char_limit: 2200`, `user_char_limit: 1375` | `MemoryProperties` |
| `delegation` | `max_concurrent_children: 3`, `max_spawn_depth: 1`, `orchestrator_enabled: true` | `DelegationProperties` |
| `mcp` | `auto_reload_on_config_change: true` | `McpProperties` |
| `mcp_servers` | map of `{name: {command, args, env, url, headers, timeout}}` | `Map<String, McpServerProperties>` |
| `security` | `allow_private_urls: false`, `redact_secrets: true` | `SecurityProperties` |
| `approvals` | `mode: smart`, `timeout: 300` | `ApprovalProperties` |
| `tool_loop_guardrails` | `warn_after.exact_failure: 2`, `hard_stop_enabled: false` | `ToolLoopGuardrailsProperties` |
| `context` | `engine: compressor` | `ContextProperties` |
| `checkpoints` | `enabled: false` | out of scope |
| `tts/stt/voice` | — | out of scope |
| `slack/discord/telegram` | — | out of scope |
| `lsp` | — | out of scope |
| `desktop/dashboard` | — | out of scope |
| `goals/moa/honcho` | — | out of scope |
| `x_search` | — | out of scope |
| `secrets` | — | out of scope |

## 8. Persistence Schema

Tables managed by Flyway. PostgreSQL replaces SQLite; full-text search uses `pg_trgm` and `tsvector` instead of FTS5.

### Core tables

```sql
CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    user_id TEXT NOT NULL,
    profile TEXT NOT NULL DEFAULT 'default',
    name TEXT,
    model_name TEXT,
    provider TEXT,
    extra JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    ordinal INT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('system','user','assistant','tool')),
    content TEXT,
    reasoning_content TEXT,
    tool_calls JSONB,
    tool_call_id TEXT,
    tool_name TEXT,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_session ON messages(session_id, ordinal);

CREATE TABLE session_model_usage (
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    model TEXT NOT NULL,
    provider TEXT NOT NULL,
    api_call_count INT NOT NULL DEFAULT 0,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    cache_read_tokens INT NOT NULL DEFAULT 0,
    cache_write_tokens INT NOT NULL DEFAULT 0,
    reasoning_tokens INT NOT NULL DEFAULT 0,
    estimated_cost_usd NUMERIC(12,8) NOT NULL DEFAULT 0,
    actual_cost_usd NUMERIC(12,8),
    first_seen TIMESTAMPTZ,
    last_seen TIMESTAMPTZ,
    PRIMARY KEY (session_id, model, provider)
);

CREATE TABLE memory (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL,
    fact TEXT NOT NULL,
    category TEXT,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_memory_user ON memory(user_id);

CREATE TABLE memory_fts (
    memory_id BIGINT PRIMARY KEY REFERENCES memory(id) ON DELETE CASCADE,
    search_vector tsvector
);
CREATE INDEX idx_memory_fts_vector ON memory_fts USING GIN(search_vector);

CREATE TABLE todos (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID REFERENCES sessions(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','in_progress','completed','cancelled')),
    ordinal INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_todos_session ON todos(session_id, ordinal);

CREATE TABLE skills (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    category TEXT,
    description TEXT,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE skill_files (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    file_path TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(skill_id, file_path)
);

CREATE TABLE async_delegations (
    id UUID PRIMARY KEY,
    session_id UUID REFERENCES sessions(id) ON DELETE SET NULL,
    goal TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','running','succeeded','failed','cancelled')),
    result TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE compression_locks (
    session_id UUID PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    holder TEXT NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_compression_locks_expires ON compression_locks(expires_at);

CREATE TABLE gateway_routing (
    scope TEXT NOT NULL DEFAULT '',
    session_key TEXT NOT NULL,
    entry_json JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, session_key)
);

CREATE TABLE session_search_meta (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE REFERENCES sessions(id) ON DELETE CASCADE,
    title TEXT,
    summary TEXT,
    search_vector tsvector
);
CREATE INDEX idx_session_search_vector ON session_search_meta USING GIN(search_vector);
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

## 10. Provider Registry

Model providers are pluggable. The first implementation supports one provider; later the registry allows multiple.

### Bundled providers

| Provider | Class | Notes |
|---|---|---|
| `openai-compatible` | `OpenAiCompatibleProvider` | generic, default, used for Ollama, Kimi, Moonshot, OpenRouter |
| `ollama` | `OllamaProvider` | optional direct `langchain4j-ollama` |

### Registry model

```java
public interface ModelProvider {
    boolean supports(String providerName);
    ChatResponse complete(CompletionRequest request);
    boolean isHealthy();
}

public record ModelRoute(
    String provider,
    String modelName,
    String apiKey,
    String baseUrl,
    Duration timeout,
    int maxRetries
) {}
```

- `ProviderRegistry` holds all providers.
- `ModelRouteResolver` maps `model` string to `ModelRoute` using config.
- `FallbackModelClient` retries on transient failures across fallbacks.

## 11. Tool Registry

The tool system mirrors the upstream Python agent's `tools/registry.py`: dynamic discovery, schemas, handlers, toolsets, availability checks.

### Tool definition

```java
@AgentTool(
    name = "read_file",
    description = "Read a file with pagination and line numbers.",
    toolset = "file"
)
public class ReadFileTool {
    public ToolResult execute(ReadFileArgs args, ToolContext ctx) { ... }
}

public record ReadFileArgs(
    @ToolParam(description = "absolute or relative path") String path,
    @ToolParam int offset,
    @ToolParam int limit
) {}
```

### Registry API

```java
public interface ToolRegistry {
    List<ToolDefinition> getDefinitions(Set<String> toolsetNames);
    List<ToolDefinition> getDefinitions();
    ToolResult execute(String toolName, String toolCallId, JsonNode args, ToolContext ctx);
    List<String> getToolsets();
    boolean isAvailable(String toolName);
}
```

- `ToolRegistry` scans Spring beans annotated with `@AgentTool` on startup.
- `ToolsetResolver` resolves composite names (`cli`, `web`, `file`, `browser`, `coding`).
- Tool availability (`check_fn` in Python) maps to `ToolAvailabilityChecker` bean per tool.
- Schema generation uses Jackson + custom `@ToolParam` annotations.

### Toolsets (aligned with upstream Python agent)

| Toolset | Description |
|---|---|
| `core` | minimal tools available everywhere |
| `web` | `web_search`, `web_extract` |
| `file` | `read_file`, `write_file`, `patch`, `search_files` |
| `browser` | all CDP browser tools |
| `vision` | `vision_analyze` |
| `terminal` | `terminal`, `process` |
| `memory` | `memory`, `todo`, `session_search` |
| `skills` | `skills_list`, `skill_view`, `skill_manage` |
| `code-execution` | `execute_code` |
| `agent-control` | `clarify`, `delegate_task` |
| `cli` | union of core + web + file + browser + terminal + memory + skills + code-execution + agent-control + `session_search` |

Toolset aliases mirror the Python upstream: `hermes-cli` → `cli`, `web`, `file`, `browser`, `cli`.


### Progressive disclosure

When MCP servers or plugins add many tools, the registry may replace non-core tools with meta tools:

- `tool_search(query)` — find a tool
- `tool_describe(name)` — read schema
- `tool_call(name, args)` — invoke tool

This reduces the tool list sent to the model.

## 12. Conversation Loop / AgentRuntime

```java
public class AgentRuntime {
    public TurnResult runTurn(Session session, String userInput) {
        // 1. append user message
        // 2. build context (messages + memory + skills + system prompt)
        // 3. while iterations < maxIterations:
        //    a. call ModelClient
        //    b. if no tool calls -> return assistant message
        //    c. for each tool call -> execute via ToolExecutor
        //    d. append results
        //    e. if context too big -> ContextCompressor.compress()
        // 4. persist all messages
    }
}
```

- `IterationBudget` tracks model API calls and tool calls.
- `ToolCallPlanner` decides parallel vs sequential execution.
- `ContextCompressor` truncates middle messages keeping system + first + last.
- `PromptBuilder` assembles system prompt from templates, skills, memory, coding context.
- `ToolResultObserver` emits post-tool hooks (e.g. memory extraction).

## 13. Security & Safety

| Layer | Component | Responsibility |
|---|---|---|
| Path | `PathSecurity` | normalize, realpath, block device files, check cwd |
| File | `FileSafety` | block sensitive paths, cross-profile guard, max read chars |
| Terminal | `DangerousCommandGuard` | pattern-based dangerous command approval |
| Execute code | `ExecuteCodeGuard` | scan Python/groovy code before sandbox run |
| Network | `UrlSafety` | allow-list/block-list, private IP checks |
| Output | `Redactor` | remove secrets before storing memory or returning |
| Browser | `BrowserSecurity` | block navigation to forbidden URLs, redact cookies |
| General | `ApprovalGate` | configurable per-tool approval flow |

Approval flow:

1. Tool called.
2. `ApprovalGate` checks `security.approval-required` and tool class annotation.
3. If approval needed, tool result returns `PENDING_APPROVAL` state.
4. CLI/web shows request; user confirms/rejects.
5. On confirm tool re-runs with `force=true`.

## 14. Gateway / HTTP API Surface

REST controllers in `api/`. First version implements:

| Method | Path | Handler |
|---|---|---|
| GET | `/health` | `HealthController` |
| GET | `/health/detailed` | `HealthController` |
| GET | `/v1/health` | `HealthController` |
| GET | `/v1/models` | `OpenAiCompatibleController` |
| POST | `/v1/chat/completions` | `OpenAiCompatibleController` (streaming and non-streaming) |
| GET | `/api/v1/sessions` | `SessionController` |
| POST | `/api/v1/sessions` | `SessionController` |
| GET | `/api/v1/sessions/{id}` | `SessionController` |
| DELETE | `/api/v1/sessions/{id}` | `SessionController` |
| GET | `/api/v1/sessions/{id}/messages` | `SessionController` |
| POST | `/api/v1/agent/chat` | `AgentController` |
| GET | `/api/v1/tools` | `ToolController` |
| GET | `/api/v1/skills` | `SkillController` |
| GET | `/api/v1/skills/{name}` | `SkillController` |

Scope note: MVP implements only OpenAI-compatible `chat_completions` and session management. Hermes also exposes `/v1/responses`, `/v1/runs`, `/api/jobs`, `/api/cron/fire`, and platform webhook ingress — these are explicitly **out of scope** for the first Java version.


## 15. CLI / REPL

Picocli subcommands:

```
java -jar backend.jar                # start web server
java -jar backend.jar repl           # interactive REPL
java -jar backend.jar tools          # list tools
java -jar backend.jar skill view X   # view skill
java -jar backend.jar skill list     # list skills
java -jar backend.jar config get X   # read config key
java -jar backend.jar config set X Y # write config key
```

REPL commands (inside session):

| Command | Action |
|---|---|
| `/new` | new session |
| `/resume <id>` | resume session |
| `/tools` | show enabled tools |
| `/verbose` | toggle verbose mode |
| `/compress` | trigger context compression |
| `/memory` | show memory for user |
| `/todo` | show todos |
| `/browser connect` | start Chromium CDP |

## 16. Observability

- SLF4J + Logback with MDC `sessionId`, `taskId`.
- Spring Boot Actuator health endpoint.
- Virtual thread monitoring via `management.observations.annotations.enabled`.
- Per-call metrics: `agent.turn.duration`, `agent.tool.calls`, `agent.model.tokens.*`.
- Distributed tracing later: Micrometer + Brave.

## 17. Testing Strategy

| Level | Scope | Examples |
|---|---|---|
| Unit | Core classes | `MessageTest`, `ToolRegistryTest`, `PromptBuilderTest`, `PathSecurityTest` |
| Integration | DB + services | `SessionServiceIT`, `AgentRuntimeIT` with Testcontainers PostgreSQL |
| Component | Mocked LLM | `ReadFileTurnIT` with stubbed `ModelClient` |
| E2E | Real Ollama | `ReplE2E` using Ollama `qwen2.5:3b` |

- Testcontainers PostgreSQL 16.
- `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)`.
- Virtual threads should be enabled in tests too (`spring.threads.virtual.enabled=true`).

## 18. Dependencies to add

```groovy
// WebSocket CDP client
implementation 'org.java-websocket:Java-WebSocket:1.6.0'
// or
implementation 'org.eclipse.jetty.websocket:jetty-websocket-jetty-client:12.0.19'

// HTML parsing
implementation 'org.jsoup:jsoup:1.19.1'

// Commons utilities
implementation 'org.apache.commons:commons-lang3:3.17.0'
implementation 'commons-io:commons-io:2.19.0'

// Markdown
implementation 'com.vladsch.flexmark:flexmark-all:0.64.8'

// Cron utilities (optional, for cron job parsing)
implementation 'com.cronutils:cron-utils:9.2.1'

// Image metadata / manipulation (optional)
implementation 'org.apache.commons:commons-imaging:1.0-alpha3'
```

`Java-WebSocket` is lighter; use it unless we need Jetty integration. Add only when implementing browser CDP.

## 19. Implementation Roadmap

### Phase 0 — Foundation (1–2 days)

1. Update `application.yml` with full config tree.
2. Expand `AgentProperties` with nested records.
3. Add dependencies: Java-WebSocket, jsoup, commons-lang3/commons-io.
4. Create `V2__agent_schema.sql` with all tables.

### Phase 1 — Runtime core (2–3 days)

1. Message model + `MessageMapper`.
2. `ModelClient` interface + `OpenAiCompatibleClient` via LangChain4j.
3. `ToolRegistry` + `@AgentTool` scanning + `ToolDefinition`.
4. `ToolExecutor` + `ToolResult`.
5. `AgentRuntime.runTurn()` happy path.

### Phase 2 — Basic tools (2–3 days)

1. `read_file`, `write_file`, `patch`, `search_files`.
2. `terminal`, `process`.
3. `web_search`, `web_extract`.
4. `memory`, `todo`, `session_search`.

### Phase 3 — API + CLI (2 days)

1. `SessionController`, `AgentController`, `OpenAiCompatibleController`.
2. `AgentCli` + `Repl` + subcommands.
3. E2E test: REPL → read_file.

### Phase 4 — Browser + Vision (3 days)

1. `ChromiumLauncher` + `CdpClient` WebSocket.
2. Browser tools.
3. `vision_analyze` via `ModelClient`.

### Phase 5 — Skills + MCP (3 days)

1. `SkillService`, `SkillManager`, skill tools.
2. `McpClientManager` + MCP tool discovery.
3. Progressive disclosure `tool_search`.

### Phase 6 — Polish

1. Security layer (`PathSecurity`, `ApprovalGate`, `Redactor`).
2. Observability metrics.
3. Performance optimization.
4. Documentation sync.

## 20. Explicitly Out of Scope

These upstream Hermes features are intentionally deferred to keep the first Java version deliverable:

| Feature | Reason |
|---|---|
| `video_analyze` | Requires video-capable multimodal model; can be added later |
| Voice / TTS / STT | Heavy native deps, separate infra |
| Computer-use / CUA | OS-level desktop automation, security surface |
| Messenger integrations (Telegram, Discord, Slack, etc.) | Gateway/platform adapters out of scope |
| `codex_responses` / `anthropic_messages` / `bedrock_converse` API modes | Only `chat_completions` in MVP |
| Billing / cost tracking | No SaaS model yet |
| LSP integration | IDE protocol complexity |
| Checkpoints / rewind | SQLite-level snapshots; deferred |
| MOA / multi-agent orchestration | Single-agent loop first |
| Secrets managers (Bitwarden, 1Password) | External vault integrations |
| Desktop/TUI/Electron UI | Web + CLI only |


## 21. Definition of Ready

Project is ready for development when:

1. `backend/build.gradle` has all dependencies.
2. `application.yml` covers every config section from section 7.
3. `AgentProperties` mirrors the YAML tree.
4. `V2__agent_schema.sql` matches section 8.
5. Package layout matches sections 9 and 10.
6. CI runs `./gradlew clean build` green.
7. Testcontainers PostgreSQL test passes.

## 22. Immediate Next Step

Implement **Phase 0** + the first part of **Phase 1**: `AgentProperties`, message model, `ModelClient`, `ToolRegistry`, `ReadFileTool`, and a single-turn `AgentRuntime`.

## 23. Spring Boot Module Layout (Current → Future)

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

## 24. CLI Entry Points

- `java -jar backend.jar` — starts web server.
- `java -jar backend.jar repl` — starts interactive REPL.
- `java -jar backend.jar tools` — lists registered tools.
- `java -jar backend.jar skill view <name>` — views a skill.

Implemented with Picocli subcommands.

## 25. Success Criteria

1. Start REPL.
2. User asks: "Read /tmp/hello.txt".
3. Agent emits `read_file` tool call.
4. Tool returns content.
5. Agent answers with file content.
6. Output contains only actionable text.



## 26. Decisions Log

| # | Question | Decision | Rationale |
|---|---|---|---|
| 1 | `execute_code` temp dir vs project CWD | Project CWD | Matches Python Hermes default |
| 2 | `delegate_task` max depth | Configurable `max-depth` (default 3) | User wants nested delegation; guardrails prevent runaway |
| 3 | MCP discovery | Auto-discover at `AgentRuntime` init | Faster first tool call, explicit failures at startup |
| 4 | Skills in system prompt | Full local index with char cap | Simpler; progressive disclosure can be added later |
| 5 | Memory vs todos scope | Memory per-user, todos per-session | Matches Hermes design |
| 6 | Vision fallback | Auxiliary model first, main model fallback | Matches `tools/vision_tools.py` `_analyze_image` |
| 7 | CDP WebSocket client | Java-WebSocket | Lighter footprint |
| 8 | Terminal backend | Local `ProcessBuilder` + optional Docker | Docker added to scope per user request |
| 9 | Approvals gate | Synchronous for CLI, async pending for HTTP | Different UX per transport |
| 10 | Auto-title sessions | Auxiliary model, like Hermes | `agent/conversation_loop.py` uses auxiliary model |

## 27. Dev / Prod Profiles

`application.yml` contains three documents:

1. **Default** — environment-variable driven; empty secrets.
2. **Dev (`spring.profiles.active=dev`)** — Ollama `http://localhost:11434/v1` with `OLLAMA_API_KEY`, model `qwen2.5:3b`, DEBUG logging.
3. **Prod (`spring.profiles.active=prod`)** — expects `OPENAI_API_KEY`, configurable model default `gpt-4o-mini`, INFO logging.

Run dev server:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

## 28. Phase 0 Status

Completed:
- `AgentProperties.java` expanded with all config sections.
- `application.yml` with environment-variable bindings and dev/prod profiles.
- `build.gradle` with CDP, HTML/markdown, commons, cron, imaging dependencies.
- `V2__agent_schema.sql` with sessions, messages, memory, todos, skills, context_references, compression_locks, approvals, gateway_routing, session_model_usage.

Next: Phase 1 — implement `AgentRuntime`, `ToolRegistry`, `ModelClient`, `ReadFileTool`, and REPL happy path.
