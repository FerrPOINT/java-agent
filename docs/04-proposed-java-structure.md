# 04 — Java Module Structure

Current stack: Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + OpenAI-compatible LLM endpoint.

The application currently lives under `backend/` as a single-module Spring Boot app. Multi-module split (`agent-core`, `agent-gateway`, `agent-cli`, `agent-spring-boot-starter`) is deferred until `backend/` stabilizes.

## 1. Root Layout

```
java-agent/
├── backend/                # current Spring Boot application
├── docs/                   # architecture and planning
├── prototype/              # cloned reference repositories (not in git)
└── README.md
```

## 2. `backend` Package Layout

```
backend/src/main/java/com/azhukov/agent/
├── JavaAgentApplication.java        # Spring Boot entry point
├── api/                             # REST controllers
│   ├── AgentController.java
│   ├── OpenAiGatewayController.java
│   ├── ApprovalController.java
│   └── HealthController.java
├── cli/                             # Picocli / JLine REPL
│   └── AgentCli.java
├── client/                          # LLM client implementations
│   ├── ModelClient.java
│   ├── NoOpModelClient.java
│   └── langchain4j/
│       └── LangChain4jModelClient.java
├── config/                          # configuration, beans, properties
│   ├── AgentConfig.java
│   ├── AgentProperties.java
│   └── JacksonConfig.java
├── core/                            # domain layer (no Spring deps in model)
│   ├── agent/
│   │   ├── AgentRuntime.java
│   │   ├── DefaultAgentRuntime.java
│   │   └── TurnResult.java
│   ├── client/
│   │   └── ModelClient.java
│   ├── context/
│   │   ├── ContextEngine.java
│   │   └── DefaultContextEngine.java
│   ├── memory/
│   │   ├── MemoryProvider.java
│   │   ├── DatabaseMemoryProvider.java
│   │   └── NoOpMemoryProvider.java
│   ├── model/
│   │   ├── ChatResponse.java
│   │   ├── Message.java
│   │   ├── Role.java
│   │   ├── Session.java
│   │   ├── ToolCall.java
│   │   ├── ToolDefinition.java
│   │   ├── ToolResult.java
│   │   └── TurnResult.java
│   ├── prompt/
│   │   ├── PromptBuilder.java
│   │   └── DefaultPromptBuilder.java
│   ├── skill/
│   │   ├── SkillManager.java
│   │   ├── DatabaseSkillManager.java
│   │   └── NoOpSkillManager.java
│   └── tool/
│       ├── ToolRegistry.java
│       └── SpringToolRegistry.java
├── persistence/                     # JPA entities + repositories
│   ├── entity/
│   │   ├── MemoryEntity.java
│   │   ├── MessageEntity.java
│   │   ├── SessionEntity.java
│   │   ├── SkillEntity.java
│   │   └── TodoEntity.java
│   └── repository/
│       ├── MemoryRepository.java
│       ├── MessageRepository.java
│       ├── SessionRepository.java
│       ├── SkillRepository.java
│       └── TodoRepository.java
├── security/                        # approvals, path/URL safety, redaction
│   ├── ApprovalGate.java
│   ├── ApprovalQueue.java
│   ├── DefaultApprovalGate.java
│   ├── FileSafety.java
│   ├── Redactor.java
│   └── UrlSafety.java
└── tools/                           # @AgentTool implementations
    ├── AgentTool.java
    ├── ToolHandler.java
    ├── ToolParam.java
    ├── browser/
    │   ├── BrowserService.java
    │   ├── CdpClient.java
    │   ├── BrowserNavigateTool.java
    │   ├── BrowserSnapshotTool.java
    │   └── ...
    ├── code/
    │   └── ExecuteCodeTool.java
    ├── core/
    │   └── ... (core/utility tools)
    ├── delegate/
    │   └── DelegateTaskTool.java
    ├── file/
    │   ├── ReadFileTool.java
    │   └── WriteFileTool.java
    ├── memory/
    │   ├── MemoryRecallTool.java
    │   ├── MemoryStoreTool.java
    │   ├── SessionSearchTool.java
    │   ├── SkillManageTool.java
    │   └── SkillViewTool.java
    ├── mcp/
    │   └── McpTool.java
    ├── terminal/
    │   ├── TerminalTool.java
    │   └── ProcessTool.java
    └── web/
        ├── WebSearchTool.java
        └── WebExtractTool.java
```

## 3. Deferred Modules

| Module | Purpose | When to split |
|--------|---------|---------------|
| `agent-core` | Pure-Java runtime (no Spring) | When tools/runtime need to be reused outside Spring Boot |
| `agent-gateway` | Messaging gateway (Telegram, Discord, etc.) | When messaging integrations return to scope |
| `agent-cli` | Standalone Picocli/JLine CLI | When CLI should run without embedded web server |
| `agent-spring-boot-starter` | Spring Boot auto-configuration for `agent-core` | After `agent-core` is extracted |

## 4. Configuration Files

- `backend/build.gradle` — Gradle Groovy DSL, versions, dependencies
- `backend/settings.gradle` — project name
- `backend/src/main/resources/application.yml` — profiles, model, tools, timeouts
- `backend/src/main/resources/db/migration/V*.sql` — Flyway migrations

## 5. Key Design Rules

- `core/model/` has no Spring dependencies.
- `core/tool/` defines contracts; `tools/` contains concrete `@AgentTool` classes.
- Tool args are POJO/record classes with `@ToolParam`; deserialization uses a single `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=false`.
- `agent.name` is configurable; default is `Джава агент`.
