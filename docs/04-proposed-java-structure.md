# 04 — Java Module Structure

Current stack: Java 25 LTS + Spring Boot 4.1.0 + Gradle 9.6.1 (Groovy DSL) + Groovy 5.0.7 + PostgreSQL 16 + H2 (noop profile) + OpenAI-compatible LLM endpoint.

The application is a single-module Spring Boot app under `backend/`. Multi-module split is deferred until the API surface stabilizes.

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
├── JavaAgentApplication.java              # Spring Boot entry point
├── api/                                   # REST controllers + DTOs
│   ├── AgentController.java
│   ├── ChatCompletionsController.java
│   ├── McpController.java
│   ├── VisionController.java
│   ├── HealthController.java
│   ├── GlobalExceptionHandler.java
│   ├── telegram/
│   │   └── TelegramWebhookController.java
│   ├── filter/
│   │   └── RateLimitFilter.java
│   ├── health/
│   │   ├── DatabaseHealthIndicator.java
│   │   ├── ChromiumHealthIndicator.java
│   │   └── ModelHealthIndicator.java
│   └── dto/*
├── cli/                                   # Picocli / JLine REPL
│   └── AgentCliRunner.java
├── client/                                # LLM clients
│   ├── ModelClient.java
│   ├── NoOpModelClient.java
│   ├── langchain4j/
│   │   └── LangChain4jModelClient.java
│   └── mcp/
│       ├── McpLifecycleManager.java
│       └── JacksonMcpJsonMapper.java
├── config/                                # configuration, beans, properties
│   ├── AgentConfig.java
│   ├── AgentProperties.java
│   ├── FlywayConfig.java
│   ├── JacksonConfig.java
│   └── TelegramConfig.java
├── core/                                  # domain + runtime
│   ├── agent/
│   │   ├── AgentRuntime.java
│   │   └── DefaultAgentRuntime.java
│   ├── budget/
│   │   ├── IterationBudget.java
│   │   └── DefaultIterationBudget.java
│   ├── client/
│   │   ├── ModelClient.java
│   │   └── StreamingResponseHandler.java
│   ├── context/
│   │   ├── ContextEngine.java
│   │   ├── DefaultContextEngine.java
│   │   ├── ContextCompressor.java
│   │   ├── DefaultContextCompressor.java
│   │   ├── ContextReferenceService.java
│   │   └── DefaultContextReferenceService.java
│   ├── memory/
│   │   ├── MemoryProvider.java
│   │   ├── DatabaseMemoryProvider.java
│   │   └── NoOpMemoryProvider.java
│   ├── model/*                            # ChatResponse, Message, Role, ToolCall, etc.
│   ├── prompt/
│   │   ├── PromptBuilder.java
│   │   └── DefaultPromptBuilder.java
│   ├── sanitizer/
│   │   ├── MessageSanitizer.java
│   │   └── DefaultMessageSanitizer.java
│   ├── security/
│   │   ├── ApprovalGate.java
│   │   ├── DefaultApprovalGate.java
│   │   ├── ApprovalQueue.java
│   │   ├── Redactor.java
│   │   ├── DefaultRedactor.java
│   │   ├── UrlSafety.java
│   │   ├── DefaultUrlSafety.java
│   │   ├── FileSafety.java
│   │   ├── DefaultFileSafety.java
│   │   ├── ToolGuardrails.java
│   │   └── DefaultToolGuardrails.java
│   ├── skill/
│   │   ├── SkillManager.java
│   │   ├── DatabaseSkillManager.java
│   │   └── NoOpSkillManager.java
│   ├── state/
│   │   ├── AgentState.java
│   │   ├── DefaultAgentState.java
│   │   ├── AgentConstants.java
│   │   └── DefaultAgentConstants.java
│   └── tool/
│       ├── ToolRegistry.java
│       ├── SpringToolRegistry.java
│       └── ToolExecutionService.java
├── gateway/                               # messaging gateway adapters
│   ├── BasePlatformAdapter.java
│   ├── GatewayRoutingService.java
│   ├── telegram/
│   │   ├── TelegramAdapter.java
│   │   ├── TelegramBotApiClient.java
│   │   ├── TelegramLongPollingService.java
│   │   ├── TelegramRestClientFactory.java
│   │   └── TelegramWebhookController.java
│   └── model/*                            # MessageEvent, Platform, SessionSource, etc.
├── persistence/                           # JPA entities + repositories
│   ├── entity/
│   │   ├── AuditLogEntity.java
│   │   ├── CompressionLockEntity.java
│   │   ├── MemoryEntity.java
│   │   ├── MessageEntity.java
│   │   ├── SessionEntity.java
│   │   ├── SkillEntity.java
│   │   └── TodoEntity.java
│   └── repository/*
├── service/                               # application services
│   ├── AgentRuntimeService.java
│   ├── AgentStreamingService.java
│   └── SessionTitleService.java
└── tools/                                 # @AgentTool implementations
    ├── AgentTool.java
    ├── ToolHandler.java
    ├── ToolParam.java
    ├── browser/*
    ├── code/
    │   └── ExecuteCodeTool.java
    ├── delegate/
    │   └── DelegateTaskTool.java
    ├── file/*
    ├── gateway/
    │   └── SendMessageTool.java
    ├── memory/*
    ├── mcp/
    │   └── McpTool.java
    ├── terminal/*
    ├── vision/
    │   └── VisionAnalyzeTool.java
    └── web/*
```

## 3. Configuration Files

- `backend/build.gradle` — Gradle Groovy DSL, versions, dependencies
- `backend/settings.gradle` — project name
- `backend/src/main/resources/application.yml` — profiles, model, tools, timeouts, gateway
- `backend/src/main/resources/db/migration/V*.sql` — Flyway migrations

## 4. Key Design Rules

- `core/model/` has no Spring dependencies.
- `core/tool/` defines contracts; `tools/` contains concrete `@AgentTool` classes.
- Tool args use `@ToolParam`; deserialization uses Jackson with `FAIL_ON_UNKNOWN_PROPERTIES=false`.
- `agent.name` is configurable; default is `Джава агент`.
- Gateway adapters are profile-activated (`noop` disables all gateways).
- Security defaults: approvals, file safety, URL safety, and redaction are enabled.
