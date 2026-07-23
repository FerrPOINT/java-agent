# 04 — Proposed Java Module Structure

This is a draft Maven multi-module layout for the Java port. Each module maps to a slice of Hermes functionality.

## 1. Root POM

```
hermes-java/
├── pom.xml
├── hermes-core/
├── hermes-gateway/
├── hermes-cli/
├── hermes-spring-boot-starter/
└── hermes-test/
```

## 2. Module: `hermes-core`

The runtime. Depends only on JVM + small libraries (Jackson, Pebble, SQLite, Resilience4j).

```
hermes-core/
├── pom.xml
└── src/main/java/com/nous/hermes/core/
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
    │   ├── HermesTool.java (annotation)
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
    │   └── SqliteMemoryProvider.java
    ├── skill/
    │   ├── SkillManager.java
    │   ├── Skill.java
    │   └── SkillLoader.java
    ├── config/
    │   ├── HermesConfig.java
    │   └── HermesConfigLoader.java
    ├── security/
    │   ├── PathSecurity.java
    │   ├── Redactor.java
    │   └── ApprovalGate.java
    └── util/
        ├── MessageSanitizer.java
        └── Json.java
```

## 3. Module: `hermes-gateway`

Messaging gateway skeleton. Depends on `hermes-core` and Spring Boot websocket.

```
hermes-gateway/
├── pom.xml
└── src/main/java/com/nous/hermes/gateway/
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

## 4. Module: `hermes-cli`

Command-line REPL and web server. Depends on `hermes-core` and `hermes-gateway`.

```
hermes-cli/
├── pom.xml
└── src/main/java/com/nous/hermes/cli/
    ├── HermesCliApplication.java
    ├── Repl.java
    ├── CliCommands.java
    └── web/
        ├── ChatController.java
        └── OpenAiCompatibleController.java
```

## 5. Module: `hermes-spring-boot-starter`

Auto-configuration for Spring Boot consumers. Provides `HermesRuntime` bean, tool auto-discovery, YAML config properties.

```
hermes-spring-boot-starter/
├── pom.xml
└── src/main/java/com/nous/hermes/spring/
    ├── HermesAutoConfiguration.java
    ├── HermesProperties.java
    └── HermesRuntimeBean.java
```

## 6. Module: `hermes-test`

Shared test utilities: fake model client, in-memory tool registry, temp directory fixtures.

```
hermes-test/
├── pom.xml
└── src/test/java/com/nous/hermes/test/
    ├── FakeModelClient.java
    ├── FakeMemoryProvider.java
    └── TestFixtures.java
```

## 7. Deliverable Priority

| Phase | Module | Goal |
|-------|--------|------|
| 1 | `hermes-core` + tests | Run one tool turn with mocked model |
| 2 | `hermes-cli` | REPL that can chat and call `read_file` |
| 3 | `hermes-gateway` skeleton | Adapter interface, no real channels |
| 4 | `hermes-spring-boot-starter` | Spring Boot auto-config |
| 5 | Web API server | OpenAI-compatible `/v1/chat/completions` |

## 8. Package Naming Convention

- Base: `com.nous.hermes.*`
- Public API: `com.nous.hermes.core.api.*`
- SPI (plugin): `com.nous.hermes.spi.*`
- Internal: `com.nous.hermes.internal.*`

## 9. Notes

- Avoid cyclic module dependencies. `hermes-core` must not depend on `hermes-gateway` or `hermes-cli`.
- Tool implementations live in `hermes-core` for builtin tools; external tools use the SPI.
- Keep `hermes-core` free of Spring annotations so it can be used in non-Spring contexts.
- Use `module-info.java` optionally; not required for prototype.
- Browser tools live under `tools/browser/` and use a lightweight CDP client, not Playwright, to avoid heavy native deps in the prototype.
- Vision uses the same `ModelClient` with image_url base64 payloads, defaulting to Kimi K2.7-code or any configured OpenAI-compatible vision model.
