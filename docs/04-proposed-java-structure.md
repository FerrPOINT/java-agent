# 04 — Proposed Java Module Structure

Target stack: Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + OpenAI-compatible LLM endpoint.

This is a draft Gradle multi-module layout for the Java port. Each module maps to a slice of agent functionality.

## 1. Root Project

```
java-agent/
├── backend/           # current Spring Boot application
├── docs/              # architecture and planning docs
├── agent-core/        # future pure-Java runtime module
├── agent-gateway/     # future gateway module
├── agent-cli/         # future CLI module
└── agent-spring-boot-starter/
```

Currently the application lives under `backend/` as a single-module Spring Boot app. Split into modules once `agent-core` is stable.

## 2. Module: `agent-core`

The runtime. Depends only on JVM + small libraries (Jackson, Pebble, PostgreSQL, Resilience4j).

```
agent-core/
└── src/main/java/com/ferrpoint/agent/core/
    ├── agent/
    │   ├── AgentRuntime.java
    │   ├── TurnResult.java
    │   ├── IterationBudget.java
    │   └── Session.java
    ├── model/
    │   ├── Message.java (sealed interface)
    │   ├── SystemMessage.java
    │   ├── UserMessage.java
    │   ├── AssistantMessage.java
    │   ├── ToolResultMessage.java
    │   ├── ToolCall.java
    │   ├── ToolResult.java
    │   ├── ToolDefinition.java
    │   ├── ImageContent.java
    │   ├── TextContent.java
    │   └── ChatResponse.java
    ├── client/
    │   ├── ModelClient.java
    │   ├── OpenAiCompatibleClient.java
    │   ├── OllamaClient.java
    │   └── ProviderAdapter.java
    ├── tool/
    │   ├── AgentTool.java (annotation)
    │   ├── ToolRegistry.java
    │   ├── ToolExecutor.java
    │   ├── ToolScanner.java
    │   ├── ToolsetResolver.java
    │   └── schema/
    │       └── JsonSchemaGenerator.java
    ├── tools/builtin/
    │   ├── ReadFileTool.java
    │   ├── WriteFileTool.java
    │   ├── PatchTool.java
    │   ├── SearchFilesTool.java
    │   ├── TerminalTool.java
    │   ├── ProcessTool.java
    │   ├── WebSearchTool.java
    │   ├── WebExtractTool.java
    │   ├── VisionAnalyzeTool.java
    │   ├── SkillsListTool.java
    │   ├── SkillViewTool.java
    │   └── SkillManageTool.java
    ├── tools/browser/
    │   ├── BrowserPool.java
    │   ├── ChromiumLauncher.java
    │   ├── CdpClient.java
    │   ├── BrowserSession.java
    │   ├── BrowserSnapshot.java
    │   ├── BrowserVisionAnalyzer.java
    │   ├── BrowserNavigateTool.java
    │   ├── BrowserSnapshotTool.java
    │   ├── BrowserClickTool.java
    │   ├── BrowserTypeTool.java
    │   ├── BrowserScrollTool.java
    │   ├── BrowserBackTool.java
    │   ├── BrowserPressTool.java
    │   ├── BrowserConsoleTool.java
    │   ├── BrowserGetImagesTool.java
    │   └── BrowserVisionTool.java
    ├── prompt/
    │   ├── PromptBuilder.java
    │   └── PromptTemplateLoader.java
    ├── context/
    │   ├── ContextEngine.java
    │   └── ContextCompressor.java
    ├── memory/
    │   ├── MemoryManager.java
    │   ├── MemoryProvider.java
    │   └── PostgresMemoryProvider.java
    ├── skill/
    │   ├── SkillManager.java
    │   ├── Skill.java
    │   └── SkillLoader.java
    ├── config/
    │   ├── AgentConfig.java
    │   └── AgentConfigLoader.java
    ├── security/
    │   ├── PathSecurity.java
    │   ├── Redactor.java
    │   └── ApprovalGate.java
    └── util/
        ├── MessageSanitizer.java
        └── Json.java
```

## 3. Module: `agent-gateway`

Messaging gateway skeleton. Depends on `agent-core` and Spring Boot websocket.

```
agent-gateway/
└── src/main/java/com/ferrpoint/agent/gateway/
    ├── GatewayRuntime.java
    ├── GatewayConfig.java
    ├── ConversationSession.java
    ├── MessageDelivery.java
    ├── PlatformAdapter.java
    ├── CommandRouter.java
    └── platforms/
        └── telegram/
            └── TelegramPlatformAdapter.java   # optional/deferred
```

## 4. Module: `agent-cli`

Command-line REPL and web server. Depends on `agent-core` and `agent-gateway`.

```
agent-cli/
└── src/main/java/com/ferrpoint/agent/cli/
    ├── AgentCliApplication.java
    ├── Repl.java
    ├── CliCommands.java
    └── web/
        ├── ChatController.java
        └── OpenAiCompatibleController.java
```

## 5. Module: `agent-spring-boot-starter`

Auto-configuration for Spring Boot consumers. Provides `AgentRuntime` bean, tool auto-discovery, YAML config properties.

```
agent-spring-boot-starter/
└── src/main/java/com/ferrpoint/agent/spring/
    ├── AgentAutoConfiguration.java
    ├── AgentProperties.java
    └── AgentRuntimeBean.java
```

## 6. Deliverable Priority

| Phase | Module | Goal |
|-------|--------|------|
| 1 | `agent-core` + tests | Run one tool turn with mocked model |
| 2 | `agent-cli` | REPL that can chat and call `read_file` |
| 3 | `agent-gateway` skeleton | Adapter interface, no real channels |
| 4 | `agent-spring-boot-starter` | Spring Boot auto-config |
| 5 | Web API server | OpenAI-compatible `/v1/chat/completions` |

## 7. Package Naming Convention

- Base: `com.azhukov.agent.*`
- Public API: `com.azhukov.agent.core.api.*`
- SPI (plugin): `com.azhukov.agent.spi.*`
- Internal: `com.azhukov.agent.internal.*`

## 8. Notes

- Avoid cyclic module dependencies. `agent-core` must not depend on `agent-gateway` or `agent-cli`.
- Tool implementations live in `agent-core` for builtin tools; external tools use the SPI.
- Keep `agent-core` free of Spring annotations so it can be used in non-Spring contexts.
- Use `module-info.java` optionally; not required for prototype.
- Browser tools live under `tools/browser/` and use a lightweight CDP client, not Playwright, to avoid heavy native deps in the prototype.
- Vision uses the same `ModelClient` with image_url base64 payloads, defaulting to the configured Ollama vision model or any OpenAI-compatible vision endpoint.