# Design Patterns Catalog

> **Project:** java-agent · **Java** 25 · **Spring Boot** 4.1
> **Source base:** `backend/src/main/java/com/azhukov/agent/` (unless otherwise noted)

This document catalogues every design pattern identified in the codebase, grouped by
category (GoF — Gang of Four, GRASP — General Responsibility Assignment Software Patterns).
Each entry lists the pattern name, category, where it is used (file + class/interface),
and the rationale for choosing it.

---

## GoF Patterns

| # | Pattern | Category | Where (file · class / interface) | Why chosen |
|---|---------|----------|----------------------------------|------------|
| 1 | **Strategy** | GoF — Behavioural | `core/client/ModelClient.java` · `ModelClient` → `NoOpModelClient`, `LangChain4jModelClient` | Swappable LLM backends (no-op for tests, LangChain4j for real calls); selected via `@ConditionalOnProperty` in `config/AgentConfig.java` |
| 2 | **Strategy** | GoF — Behavioural | `core/memory/MemoryProvider.java` · `MemoryProvider` → `DatabaseMemoryProvider`, `NoOpMemoryProvider` | Decouples persistence from memory logic; NoOp for offline/no-DB profiles |
| 3 | **Strategy** | GoF — Behavioural | `core/skill/SkillManager.java` · `SkillManager` → `DatabaseSkillManager`, `NoOpSkillManager` | Skill storage strategy — database or in-memory no-op for testing |
| 4 | **Strategy** | GoF — Behavioural | `service/tts/TtsProvider.java` · `TtsProvider` → `EdgeTtsProvider`, `OpenAiTtsProvider` | Multiple TTS engines behind a common interface; runtime selection |
| 5 | **Strategy** | GoF — Behavioural | `service/imagegen/ImageGenProvider.java` · `ImageGenProvider` → `OpenAiImageGenProvider` | Extensible image-generation interface; only OpenAI impl currently |
| 6 | **Strategy** | GoF — Behavioural | `service/transcription/TranscriptionProvider.java` · `TranscriptionProvider` → `OpenAiTranscriptionProvider` | Extensible transcription interface; only OpenAI impl currently |
| 7 | **Strategy** | GoF — Behavioural | `core/context/ContextEngine.java` · `ContextEngine` → `DefaultContextEngine` | Context-assembly strategy; single default impl but interface allows alternatives |
| 8 | **Strategy** | GoF — Behavioural | `core/context/ContextCompressor.java` · `ContextCompressor` → `DefaultContextCompressor` | Context-compression strategy; interface for future alternative algorithms |
| 9 | **Strategy** | GoF — Behavioural | `core/context/ContextReferenceService.java` · `ContextReferenceService` → `DefaultContextReferenceService` | Reference-resolution strategy |
| 10 | **Strategy** | GoF — Behavioural | `core/prompt/PromptBuilder.java` · `PromptBuilder` → `DefaultPromptBuilder` | System-prompt construction strategy; `DefaultPromptBuilder` builds the system message from session metadata |
| 11 | **Strategy** | GoF — Behavioural | `core/security/FileSafety.java` · `FileSafety` → `DefaultFileSafety` | File-path validation strategy; default checks against allowed-list |
| 12 | **Strategy** | GoF — Behavioural | `core/security/Redactor.java` · `Redactor` → `DefaultRedactor` | Secret-redaction strategy; default uses vendor patterns |
| 13 | **Strategy** | GoF — Behavioural | `core/security/UrlSafety.java` · `UrlSafety` → `DefaultUrlSafety` | URL-validation strategy |
| 14 | **Strategy** | GoF — Behavioural | `core/security/ToolGuardrails.java` · `ToolGuardrails` → `DefaultToolGuardrails` | Tool-call guardrail strategy (before/after hooks) |
| 15 | **Strategy** | GoF — Behavioural | `core/agent/AgentRuntime.java` · `AgentRuntime` → `DefaultAgentRuntime` | Agent-turn orchestration strategy |
| 16 | **Strategy** | GoF — Behavioural | `core/budget/IterationBudget.java` · `IterationBudget` → `DefaultIterationBudget` | Iteration-limit strategy for agent loops |
| 17 | **Strategy** | GoF — Behavioural | `core/state/AgentState.java` · `AgentState` → `DefaultAgentState` | Agent state-management strategy |
| 18 | **Strategy** | GoF — Behavioural | `core/state/AgentConstants.java` · `AgentConstants` → `DefaultAgentConstants` | Agent configuration constants strategy |
| 19 | **Strategy** | GoF — Behavioural | `core/tool/ToolRegistry.java` · `ToolRegistry` → `SpringToolRegistry` | Tool-discovery/registration strategy |
| 20 | **Factory** | GoF — Creational | `core/tool/SpringToolRegistry.java` · `SpringToolRegistry.registerBeans()` / `buildDefinition()` | Creates `ToolDefinition` objects from `@AgentTool` annotations and `ToolHandler` beans via reflection at startup; decouples tool definition from handler implementation |
| 21 | **Proxy** | GoF — Structural | `core/tool/ManagedToolGateway.java` · `ManagedToolGateway` | Wraps tool execution with a managed-gateway check (`isEnabled`, registered predicates); controls access without modifying the real tool handlers |
| 22 | **Adapter** | GoF — Structural | `gateway/telegram/TelegramAdapter.java` · `TelegramAdapter implements BasePlatformAdapter` | Adapts Telegram Bot API calls to the platform-agnostic `BasePlatformAdapter` interface |
| 23 | **Adapter** | GoF — Structural | `api/mapper/OpenAiMapper.java` · `OpenAiMapper` (MapStruct) | Adapts domain models (`Message`, `ToolDefinition`, `ToolCall`) to OpenAI-compatible DTOs (`OpenAiChatRequest`, `OpenAiChatResponse`, `OpenAiStreamChunk`) and back |
| 24 | **Template Method** | GoF — Behavioural | `gateway/BasePlatformAdapter.java` (interface) · `TelegramAdapter` overrides | `BasePlatformAdapter` defines the platform-adapter contract (`connect`, `send`, `sendImage`, `setMessageHandler`); `TelegramAdapter` provides concrete implementations |
| 25 | **Observer** | GoF — Behavioural | `core/agent/InterruptToken.java` · `InterruptToken` | Session-level cancellation event: `cancel()` sets a flag and fires registered `Runnable` callbacks; consumers (`TerminalTool`, `DefaultAgentRuntime`) observe the interrupt |
| 26 | **Observer** | GoF — Behavioural | `core/agent/SteerBuffer.java` · `SteerBuffer` | Session-level steer event: `steer()` deposits text, `consume()` retrieves it on next tool result; the agent loop observes pending steers |
| 27 | **Builder** | GoF — Creational | `core/model/Message.java` · `Message` record factory methods | Static factory methods (`user()`, `system()`, `assistant()`, `assistantWithToolCalls()`, `toolResult()`, `withContent()`) build immutable `Message` instances with sensible defaults |
| 28 | **Builder** | GoF — Creational | `core/prompt/DefaultPromptBuilder.java` · `DefaultPromptBuilder.buildSystemMessage()` | Builds the system-prompt `Message` from session metadata, skills, and configuration |
| 29 | **Chain of Responsibility** | GoF — Behavioural | `client/langchain4j/ErrorClassifier.java` · `ErrorClassifier.classify()` | Classifies exceptions through a chain of pattern-matching checks (billing → context-overflow → content-policy → rate-limit → retryable → permanent); first match wins |
| 30 | **State** | GoF — Behavioural | `core/state/TurnState.java` · `TurnState` + `core/state/TurnStateManager.java` · `TurnStateManager` | Tracks per-turn state (tool executions, failure counts, repeat-call counts, halted flag); `TurnStateManager` creates and manages `TurnState` instances per session/turn |
| 31 | **Decorator** | GoF — Structural | `core/security/RedactingLayout.java` · `RedactingLayout extends PatternLayout` | Wraps Logback `PatternLayout.doLayout()`, decorating output with `DefaultRedactor.redact()` before returning — secrets never appear in logs |
| 32 | **Command** | GoF — Behavioural | `cli/src/main/java/…/cli/SlashCommandRegistry.java` · `SlashCommandRegistry` + `SlashCommand` | 47 slash commands registered as function objects (`SlashCommand` lambdas); `SlashCommandRegistry.execute()` dispatches by name |
| 33 | **Command** | GoF — Behavioural | `telegram-bot/src/main/java/…/bot/commands/CommandRegistry.java` · `CommandRegistry` + `CommandHandler` | 56 bot commands + 10 aliases as `CommandHandler` beans; `CommandRegistry` dispatches by name with alias resolution |
| 34 | **Composite** | GoF — Structural | `core/tool/ToolExecutionService.java` · `ToolExecutionService.execute()` | Composes multiple tool results into agent-turn messages; executes tools in parallel via virtual threads, aggregates results, and feeds them back as composite `Message` objects to the LLM |

---

## GRASP Patterns

| # | Pattern | Category | Where (file · class / package) | Why chosen |
|---|---------|----------|--------------------------------|------------|
| 1 | **Controller** | GRASP | `api/AgentController.java` · `AgentController` | Handles all `/api/v1/agent/*` REST requests; delegates to services, never touches entities directly |
| 2 | **Controller** | GRASP | `api/ChatCompletionsController.java` · `ChatCompletionsController` | Handles OpenAI-compatible `/v1/chat/completions` endpoint; delegates to `AgentRuntime`, `PromptBuilder`, `OpenAiMapper` |
| 3 | **Controller** | GRASP | `api/CronJobController.java` · `CronJobController` | Handles cron-job management endpoints |
| 4 | **Controller** | GRASP | `api/VisionController.java` · `VisionController` | Handles vision/image-analysis endpoints |
| 5 | **Controller** | GRASP | `api/HealthController.java` · `HealthController` | Handles health-check endpoints |
| 6 | **Controller** | GRASP | `api/McpController.java` · `McpController` | Handles MCP (Model Context Protocol) management endpoints |
| 7 | **Creator** | GRASP | `config/AgentConfig.java` · `AgentConfig` (`@Configuration` with `@Bean` methods) | Spring `@Bean` factory methods create and wire all core beans (`ModelClient`, `MemoryProvider`, `SkillManager`, `ContextEngine`, `AgentRuntime`, security beans, etc.) with `@ConditionalOnProperty` / `@ConditionalOnMissingBean` for profile-based selection |
| 8 | **High Cohesion** | GRASP | Package structure: `api/`, `core/`, `persistence/`, `security/`, `tools/`, `service/`, `gateway/`, `config/` | Each package has a single, well-defined responsibility — controllers only handle HTTP, core contains domain logic, persistence handles JPA, security handles guardrails/sanitisation |
| 9 | **Low Coupling** | GRASP | Inter-module communication | The three Gradle modules (`backend`, `telegram-bot`, `cli`) communicate exclusively via REST APIs; no direct code-level dependencies between modules |
| 10 | **Polymorphism** | GRASP | All Strategy interfaces (see GoF #1–#19) | Every interface with multiple implementations enables polymorphic dispatch — `AgentRuntime` receives a `ModelClient`, `MemoryProvider`, `ContextEngine`, etc. without knowing the concrete class |
| 11 | **Pure Fabrication** | GRASP | `persistence/mapper/MessageMapper.java`, `SessionEntityMapper.java`; `api/mapper/DomainDtoMapper.java`, `OpenAiMapper.java` (MapStruct) | Mapper classes do not represent domain concepts — they exist purely to convert between layers (entity ↔ domain, domain ↔ DTO). MapStruct generates the implementations at compile time |
| 12 | **Information Expert** | GRASP | `tools/ToolHandler.java` · `ToolHandler` implementations (e.g. `TerminalTool`, `WebSearchTool`, `BrowserNavigateTool`, `MemoryTool`, … — 45 tool classes) | Each `ToolHandler` implementation is the information expert for its specific tool: it knows its own arguments, execution logic, and result formatting. The runtime delegates execution without needing tool-specific knowledge |

---

## Summary

| Category | Count |
|----------|-------|
| GoF patterns | 34 occurrences (14 distinct pattern types) |
| GRASP patterns | 12 occurrences (7 distinct pattern types) |
| **Total** | **46 pattern instances** |

### Pattern frequency

| Pattern | Occurrences |
|---------|------------|
| Strategy (GoF) | 19 |
| Controller (GRASP) | 6 |
| Command (GoF) | 2 |
| Adapter (GoF) | 2 |
| Observer (GoF) | 2 |
| Builder (GoF) | 2 |
| Factory (GoF) | 1 |
| Proxy (GoF) | 1 |
| Template Method (GoF) | 1 |
| Chain of Responsibility (GoF) | 1 |
| State (GoF) | 1 |
| Decorator (GoF) | 1 |
| Composite (GoF) | 1 |
| Creator (GRASP) | 1 |
| High Cohesion (GRASP) | 1 |
| Low Coupling (GRASP) | 1 |
| Polymorphism (GRASP) | 1 |
| Pure Fabrication (GRASP) | 1 |
| Information Expert (GRASP) | 1 |