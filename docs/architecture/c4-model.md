# C4 Model — Java Agent Architecture

This document describes the Java Agent system using the C4 model (Context, Container, Component) with Mermaid diagrams renderable in GitLab/GitHub markdown.

---

## Level 1: System Context

The System Context diagram shows the Java Agent as a whole, its users, and the external systems it interacts with.

```mermaid
C4Context
title Java Agent — System Context

Person(user, "User", "Interacts with the agent via Telegram or CLI")
System(system_alias, "Java Agent", "AI agent with LLM integration, tool execution, memory, and multi-channel delivery (Telegram bot + CLI)")

System_Ext(llm, "LLM Provider", "Ollama / OpenAI-compatible API for model inference")
System_Ext(postgres, "PostgreSQL", "Persistent storage for sessions, messages, memory, checkpoints")
System_Ext(chromium, "Chromium CDP", "Headless browser for web automation tools")
System_Ext(telegram_api, "Telegram Bot API", "Telegram platform for bot message delivery")

Rel(user, system_alias, "Sends messages, receives streamed responses")
Rel(system_alias, llm, "Sends prompts, receives streamed completions")
Rel(system_alias, postgres, "Reads/writes sessions, messages, memory")
Rel(system_alias, chromium, "Controls browser via Chrome DevTools Protocol")
Rel(system_alias, telegram_api, "Polls updates, sends messages/media")

UpdateRelStyle(user, system_alias, $offsetX="-40", $offsetY="-10")
UpdateRelStyle(system_alias, llm, $offsetX="-20", $offsetY="0")
UpdateRelStyle(system_alias, postgres, $offsetX="-30", $offsetY="0")
UpdateRelStyle(system_alias, chromium, $offsetX="-20", $offsetY="10")
UpdateRelStyle(system_alias, telegram_api, $offsetX="-40", $offsetY="10")
```

### Description

| Element | Description |
|---------|-------------|
| **User** | A human who interacts with the agent through Telegram (bot) or a local terminal (CLI). |
| **Java Agent** | The complete system — a Spring Boot application suite providing AI agent capabilities: LLM orchestration, tool execution (45 tools), persistent memory, session management, and multi-channel delivery. |
| **LLM Provider** | External model inference API. Supports Ollama (local/cloud) and OpenAI-compatible endpoints. Used for generating responses, context compression, and tool-call decisions. |
| **PostgreSQL** | Primary datastore. Stores sessions, messages, memory entries, checkpoints, and agent metadata. Flyway manages migrations. |
| **Chromium CDP** | Headless Chromium browser controlled via Chrome DevTools Protocol. Used by browser automation tools (navigation, scraping, screenshots). |
| **Telegram Bot API** | Telegram's platform API. The bot polls for updates and sends messages, media, and inline keyboards back to users. |

---

## Level 2: Container

The Container diagram shows the deployable units within the Java Agent system and how they communicate.

```mermaid
graph TB
    subgraph "Java Agent System"
        BACKEND["<b>backend</b><br/>Spring Boot 4.1 · Java 25<br/>Port 8090 (dev) / 8080<br/><br/>REST API (53 endpoints)<br/>SSE Streaming<br/>LLM Client (LangChain4j)<br/>45 Tool Implementations<br/>JPA Persistence (12 entities)<br/>Security Layer<br/>Memory Provider<br/>Context Engine<br/>Agent Runtime"]
        BOT["<b>telegram-bot</b><br/>Spring Boot 4.1 · Java 25<br/><br/>56 Commands<br/>Long Polling<br/>SSE Stream Consumer<br/>Media Handling<br/>Inline Keyboards<br/>Typing Indicators<br/>RestClient → Backend"]
        CLI["<b>cli</b><br/>Spring Boot 4.1 · Java 25<br/>Non-web<br/><br/>JLine REPL<br/>47 Slash Commands<br/>SSE Stream Consumer<br/>Autocomplete<br/>Session Management<br/>RestClient → Backend"]
    end

    POSTGRES[("PostgreSQL 16<br/>Sessions<br/>Messages<br/>Memory<br/>Checkpoints")]
    LLM_API["LLM Provider<br/>Ollama / OpenAI-compatible<br/>Inference API"]
    CHROMIUM["Chromium CDP<br/>Headless Browser<br/>Chrome DevTools Protocol"]
    TELEGRAM_API["Telegram Bot API<br/>Updates polling<br/>Message delivery"]
    USER_TG["Telegram User"]
    USER_CLI["CLI User (Terminal)"]

    USER_TG -->|"Messages / media"| TELEGRAM_API
    TELEGRAM_API -->|"Updates (long poll)"| BOT
    BOT -->|"Messages / inline keyboards"| TELEGRAM_API
    TELEGRAM_API -->|"Delivery"| USER_TG

    USER_CLI -->|"Text input / slash commands"| CLI
    CLI -->|"Streamed output"| USER_CLI

    BOT -->|"REST API (53 endpoints)<br/>SSE streaming"| BACKEND
    CLI -->|"REST API (53 endpoints)<br/>SSE streaming"| BACKEND

    BACKEND -->|"JDBC / JPA<br/>Flyway migrations"| POSTGRES
    BACKEND -->|"HTTP prompts<br/>Streamed completions"| LLM_API
    BACKEND -->|"CDP commands<br/>JSON-over-WS"| CHROMIUM

    classDef container fill:#1168bd,stroke:#0b4884,color:#ffffff,stroke-width:2px
    classDef external fill:#999999,stroke:#6b6b6b,color:#ffffff,stroke-width:2px
    classDef db fill:#f5a623,stroke:#d4880b,color:#ffffff,stroke-width:2px
    classDef user fill:#08427b,stroke:#052e56,color:#ffffff,stroke-width:2px

    class BACKEND,BOT,CLI container
    class POSTGRES db
    class LLM_API,CHROMIUM,TELEGRAM_API external
    class USER_TG,USER_CLI user
```

### Container Descriptions

| Container | Technology | Responsibility |
|----------|------------|----------------|
| **backend** | Spring Boot 4.1, Java 25, port 8090/8080 | Core agent engine. REST API (7 controllers, 53 endpoints). SSE streaming. LLM client (LangChain4j 1.18). 45 `@AgentTool` implementations. JPA persistence (12 entities, 12 repositories, 4 MapStruct mappers). Security layer (FileSafety, Redactor, UrlSafety, ToolGuardrails, ApprovalGate). Memory provider. Context engine. Agent runtime. |
| **telegram-bot** | Spring Boot 4.1, Java 25 | Telegram delivery channel. 56 commands. Long polling for updates. SSE stream consumer (real-time token output). Media handling (photos, voice, documents). Inline keyboards, typing indicators. Communicates with backend exclusively via REST. |
| **cli** | Spring Boot 4.1, Java 25, non-web | Local terminal interface. JLine REPL with autocomplete. 47 slash commands (`/new`, `/status`, `/compress`, `/undo`, `/checkpoint`, `/rollback`, `/memory`, `/skills`, `/help`, `/exit`). SSE stream consumer. Session management. Communicates with backend exclusively via REST. |

### External Systems

| System | Protocol | Purpose |
|--------|----------|---------|
| **PostgreSQL 16** | JDBC (port 5432) | Primary datastore. Flyway manages schema migrations. |
| **LLM Provider** | HTTP (Ollama / OpenAI-compatible) | Model inference. Streamed completions via SSE. Retries with jittered backoff. |
| **Chromium CDP** | WebSocket (Chrome DevTools Protocol) | Headless browser automation. Page navigation, DOM interaction, screenshots. |
| **Telegram Bot API** | HTTPS (long polling) | Message delivery and update polling. No webhook required. |

### Communication Patterns

- **Bot ↔ Backend**: REST over HTTP. Bot calls backend's 53 endpoints. SSE for streaming responses. Bot is a pure HTTP client — no shared code with backend.
- **CLI ↔ Backend**: REST over HTTP. Identical API usage as bot. SSE for streaming. CLI is a standalone JAR (`java -jar`).
- **Backend ↔ LLM**: HTTP with SSE streaming. LangChain4j client. Retry with jittered exponential backoff. Supports Ollama and OpenAI-compatible providers.
- **Backend ↔ PostgreSQL**: JDBC via Spring Data JPA / Hibernate. Flyway migrations. H2 in PostgreSQL mode for tests.
- **Backend ↔ Chromium**: WebSocket CDP. `SsrfSafeHttpClient` ensures only approved CDP endpoints are used.

---

## Level 3: Component (Backend)

The Component diagram shows the internal structure of the `backend` container.

```mermaid
graph TB
    subgraph "backend (Spring Boot · port 8090/8080)"
        subgraph "API Layer"
            API["<b>api/</b><br/>7 Controllers<br/>53 Endpoints<br/><br/>SessionController<br/>MessageController<br/>ToolController<br/>MemoryController<br/>CheckpointController<br/>SkillController<br/>SystemController"]
            API_MAPPER["<b>api.mapper/</b><br/>DomainDtoMapper<br/>Domain ↔ DTO conversion"]
        end

        subgraph "Core Layer"
            RUNTIME["<b>AgentRuntime</b><br/>Orchestrates agent turns<br/>Tool dispatch<br/>LLM interaction loop"]
            CONTEXT["<b>ContextEngine</b><br/>Message assembly<br/>Context compression<br/>Token management"]
            MEMORY["<b>MemoryProvider</b><br/>Memory prefetch<br/>Async sync_turn<br/>Memory retrieval"]
            TOOL_EXEC["<b>ToolExecutionService</b><br/>Virtual thread executor<br/>Parallel tool calls<br/>45 @AgentTool implementations"]
        end

        subgraph "Service Layer"
            AGENT_SVC["<b>AgentRuntimeService</b><br/>Turn lifecycle<br/>Session management<br/>TransactionTemplate"]
            STREAM_SVC["<b>AgentStreamingService</b><br/>SSE streaming<br/>Token-by-token output<br/>Interrupt / steer handling"]
            TTS_SVC["<b>TTS Service</b><br/>Text-to-speech<br/>Voice message generation"]
            IMG_SVC["<b>ImageGen Service</b><br/>Image generation<br/>DALL-E / compatible"]
            TRANSCRIBE_SVC["<b>Transcription Service</b><br/>Audio transcription<br/>Voice → text"]
        end

        subgraph "Persistence Layer"
            ENTITIES["<b>persistence.entity/</b><br/>12 JPA Entities<br/>Session, Message, Memory,<br/>Checkpoint, Skill, etc."]
            REPOS["<b>persistence.repository/</b><br/>12 Spring Data Repositories<br/>JPA queries, custom queries"]
            PERSIST_MAPPER["<b>persistence.mapper/</b><br/>4 MapStruct Mappers<br/>MessageMapper<br/>SessionEntityMapper<br/>OpenAiMapper<br/>DomainDtoMapper"]
        end

        subgraph "Tools Layer"
            TOOLS["<b>tools/</b><br/>45 @AgentTool implementations<br/><br/>WebSearch, FileRead,<br/>FileWrite, ShellExec,<br/>BrowserNav, Screenshot,<br/>CodeRun, HttpRequest,<br/>Memory tools, etc."]
        end

        subgraph "Gateway Layer"
            GATEWAY["<b>gateway/</b><br/>Telegram adapter<br/>Webhook handler<br/>Bot message routing"]
        end

        subgraph "Security Layer"
            FILE_SAFETY["<b>FileSafety</b><br/>Write denylist<br/>Read blocking<br/>Sensitive path protection"]
            REDACTOR["<b>Redactor</b><br/>Secret masking<br/>API key / token redaction"]
            URL_SAFETY["<b>UrlSafety</b><br/>URL validation<br/>SSRF prevention"]
            GUARDRAILS["<b>ToolGuardrails</b><br/>Tool access control<br/>Per-session tool enablement"]
            APPROVAL["<b>ApprovalGate</b><br/>Destructive tool confirmation<br/>User approval flow"]
        end
    end

    LLM_API_EXT["LLM Provider<br/>Ollama / OpenAI"]
    POSTGRES_EXT[("PostgreSQL")]
    CDP_EXT["Chromium CDP"]

    API -->|"DTO ↔ Domain"| API_MAPPER
    API_MAPPER -->|"Domain objects"| AGENT_SVC
    AGENT_SVC -->|"orchestrate"| RUNTIME
    RUNTIME -->|"assemble context"| CONTEXT
    RUNTIME -->|"execute tools"| TOOL_EXEC
    RUNTIME -->|"persist / recall"| MEMORY
    RUNTIME -->|"stream output"| STREAM_SVC
    TOOL_EXEC -->|"dispatch"| TOOLS
    TOOLS -->|"file ops"| FILE_SAFETY
    TOOLS -->|"HTTP requests"| URL_SAFETY
    TOOLS -->|"output"| REDACTOR
    TOOLS -->|"destructive ops"| APPROVAL
    TOOLS -->|"destructive ops"| GUARDRAILS
    AGENT_SVC -->|"entity ↔ domain"| PERSIST_MAPPER
    PERSIST_MAPPER -->|"JPA entities"| ENTITIES
    ENTITIES -->|"CRUD"| REPOS
    REPOS -->|"JDBC"| POSTGRES_EXT
    RUNTIME -->|"LLM calls"| LLM_API_EXT
    TOOLS -->|"browser automation"| CDP_EXT
    GATEWAY -->|"route messages"| AGENT_SVC
    TTS_SVC -->|"audio output"| STREAM_SVC
    IMG_SVC -->|"image output"| STREAM_SVC
    TRANSCRIBE_SVC -->|"transcribed text"| AGENT_SVC

    classDef apilayer fill:#438dd5,stroke:#2e6295,color:#ffffff,stroke-width:2px
    classDef corelayer fill:#1168bd,stroke:#0b4884,color:#ffffff,stroke-width:2px
    classDef servicelayer fill:#2e6da4,stroke:#1f4f7a,color:#ffffff,stroke-width:2px
    classDef persistlayer fill:#f5a623,stroke:#d4880b,color:#ffffff,stroke-width:2px
    classDef toolslayer fill:#8e44ad,stroke:#6c3483,color:#ffffff,stroke-width:2px
    classDef gatewaylayer fill:#16a085,stroke:#0e6655,color:#ffffff,stroke-width:2px
    classDef securitylayer fill:#e74c3c,stroke:#a93226,color:#ffffff,stroke-width:2px
    classDef external fill:#999999,stroke:#6b6b6b,color:#ffffff,stroke-width:2px
    classDef db fill:#f5a623,stroke:#d4880b,color:#ffffff,stroke-width:2px

    class API,API_MAPPER apilayer
    class RUNTIME,CONTEXT,MEMORY,TOOL_EXEC corelayer
    class AGENT_SVC,STREAM_SVC,TTS_SVC,IMG_SVC,TRANSCRIBE_SVC servicelayer
    class ENTITIES,REPOS,PERSIST_MAPPER persistlayer
    class TOOLS toolslayer
    class GATEWAY gatewaylayer
    class FILE_SAFETY,REDACTOR,URL_SAFETY,GUARDRAILS,APPROVAL securitylayer
    class LLM_API_EXT,CDP_EXT external
    class POSTGRES_EXT db
```

### Component Descriptions

#### API Layer (`api/`)

| Component | Description |
|-----------|-------------|
| **Controllers (7)** | `SessionController`, `MessageController`, `ToolController`, `MemoryController`, `CheckpointController`, `SkillController`, `SystemController`. Expose 53 REST endpoints. Return DTOs (records), never entities. |
| **`api.mapper/`** | `DomainDtoMapper` converts domain models to/from DTOs using MapStruct (`unmappedTargetPolicy = ERROR`). |

#### Core Layer (`core/`)

| Component | Description |
|-------------|-------------|
| **`AgentRuntime`** | The agent loop: receives user message → assembles context → calls LLM → dispatches tool calls → streams response → persists. Orchestrates the entire turn lifecycle. |
| **`ContextEngine`** | Assembles the prompt: system instructions + memory + conversation history + tool definitions. Manages context window limits. Performs context compression (with anti-injection prefix) when token limits are approached. |
| **`MemoryProvider`** | Manages long-term memory. Prefetches relevant memories before a turn (reduces latency). Persists new memories asynchronously via `sync_turn` on a background daemon thread. |
| **`ToolExecutionService`** | Executes `@AgentTool`-annotated methods in parallel using `Executors.newVirtualThreadPerTaskExecutor()`. Handles tool result aggregation, timeouts, and error isolation. |

#### Service Layer (`service/`)

| Component | Description |
|-------------|-------------|
| **`AgentRuntimeService`** | Turn lifecycle management. Session state transitions. Uses `TransactionTemplate` for programmatic transactions in streaming contexts (avoids `@Transactional` self-invocation pitfall). |
| **`AgentStreamingService`** | SSE token streaming. Manages `SseEmitter` lifecycle. Handles interrupts (user cancellation) and steer (mid-turn message injection) via `ConcurrentHashMap`-backed session state. |
| **TTS Service** | Text-to-speech generation. Produces audio for voice messages (Telegram). |
| **ImageGen Service** | Image generation via DALL-E or compatible API. |
| **Transcription Service** | Audio transcription (voice → text). Feeds transcribed text into the agent runtime. |

#### Persistence Layer (`persistence/`)

| Component | Description |
|-------------|-------------|
| **Entities (12)** | `Session`, `Message`, `Memory`, `Checkpoint`, `Skill`, and 7 others. JPA entities annotated with `@Entity` + Lombok `@Data`. |
| **Repositories (12)** | Spring Data JPA repositories. Custom queries where needed. |
| **Mappers (4)** | `MessageMapper` (MessageEntity ↔ Message), `SessionEntityMapper` (SessionEntity ↔ Session), `OpenAiMapper` (Domain ↔ OpenAI DTOs), `DomainDtoMapper` (Session → SessionSummaryDto). MapStruct-generated, Spring-managed, `unmappedTargetPolicy = ERROR`. |

#### Tools Layer (`tools/`)

| Component | Description |
|-------------|-------------|
| **45 `@AgentTool` implementations** | Web search, file read/write, shell execution, browser navigation, screenshots, code execution, HTTP requests, memory tools, and more. Each annotated with `@AgentTool`, discovered at startup, and registered with `ToolExecutionService`. |

#### Gateway Layer (`gateway/`)

| Component | Description |
|-------------|-------------|
| **Telegram adapter** | Routes incoming Telegram updates to the appropriate agent service. Handles media (photos, voice, documents). Manages bot-specific formatting (Markdown → Telegram HTML). |
| **Webhook handler** | Optional webhook endpoint (long polling is the default). |

#### Security Layer (`security/`)

| Component | Description |
|-------------|-------------|
| **`FileSafety`** | Write denylist: blocks writes to sensitive paths (`/etc/`, `~/.ssh/`, `.env`). Read blocking: prevents reading secrets/credentials. |
| **`Redactor`** | Masks API keys, tokens, and secrets in tool output before sending to the LLM. |
| **`UrlSafety`** | Validates URLs to prevent SSRF. Blocks private/local IP ranges. Used by `SsrfSafeHttpClient`. |
| **`ToolGuardrails`** | Per-session tool enablement. Controls which tools are available in a given context. |
| **`ApprovalGate`** | Destructive tools (file delete, shell command) require explicit user confirmation before execution. |