# Design Patterns Catalog

> Patterns used throughout the Java Agent codebase.
> Practical — not ceremonial. Each pattern solves a real problem.

---

## 1. Strategy Pattern — ToolRegistry

**Problem:** Tools need to be discovered, registered, and dispatched dynamically without hardcoding each tool.

**Solution:** `ToolRegistry` interface with `SpringToolRegistry` implementation. Spring auto-discovers `@AgentTool` beans at startup.

```java
public interface ToolRegistry {
    List<ToolDefinition> getDefinitions();
    List<ToolDefinition> getDefinitions(Set<String> toolsets);
    ToolResult execute(String toolName, String toolCallId, String arguments, Message lastAssistant, Session session);
    void registerDynamic(String toolName, ToolDefinition definition, ToolHandler handler);
    void deregisterDynamic(String toolName);
}
```

**How:** Each tool is a `@Component` implementing `ToolHandler`. `SpringToolRegistry` collects all `ToolHandler` beans via constructor injection (`List<ToolHandler> handlers`). The registry maps tool names to handlers and supports toolset-based filtering.

**Where:** `core/tool/ToolRegistry.java`, `core/tool/SpringToolRegistry.java`, `tools/*`

---

## 2. Strategy Pattern — ModelClient

**Problem:** Support multiple LLM providers (OpenAI, Ollama, NoOp) without coupling the runtime to any specific one.

**Solution:** `ModelClient` interface with multiple implementations.

```java
public interface ModelClient {
    ChatResponse complete(List<Message> messages, List<ToolDefinition> tools, ModelRequestOptions options);
}
```

| Implementation | Profile | Purpose |
|---------------|---------|---------|
| `LangChain4jModelClient` | dev, prod | Real LLM calls via LangChain4j |
| `NoOpModelClient` | noop | Stub for tests and offline dev |

**Where:** `core/client/ModelClient.java`, `client/langchain4j/LangChain4jModelClient.java`, `client/NoOpModelClient.java`

---

## 3. Template Method — AgentRuntime Turn Loop

**Problem:** The turn loop has a fixed structure (prepare → call model → execute tools → repeat) but individual steps need to be overrideable.

**Solution:** `AgentRuntime` interface with `DefaultAgentRuntime` implementing the full turn loop. The loop is a single `runTurnLoop` method with well-defined extension points (guardrails, budget, interrupt, approval, steer).

```java
for (int i = 0; i < maxTurns; i++) {
    if (guardrail.isHalted()) return;        // Extension: guardrails
    if (budget.isExhausted(budget)) return;  // Extension: iteration budget
    response = callModelWithRetry(...);      // Extension: retry + compression
    if (!response.hasToolCalls()) return;     // Exit condition
    toolResults = executeTools(...);          // Extension: parallel/sequential
    // Extension: steer buffer injection
}
```

**Where:** `core/agent/DefaultAgentRuntime.java`

---

## 4. Registry Pattern — CommandRegistry (Bot & CLI)

**Problem:** Bot commands (56+) and CLI commands (74+) need to be looked up by name with alias support.

**Solution:** Both modules use a registry that auto-collects handlers via Spring DI.

```java
// Bot: CommandRegistry collects all CommandHandler beans
public CommandRegistry(List<CommandHandler> handlers) {
    for (CommandHandler handler : handlers) {
        this.handlers.put(handler.name(), handler);
    }
}
// CLI: SlashCommandRegistry collects all SlashCommand beans
```

**Alias resolution:** Bot `CommandRegistry` has a static `ALIASES` map (10 aliases: `sethome→set_home`, `fork→branch`, etc.).

**Where:** `bot/commands/CommandRegistry.java`, `cli/SlashCommandRegistry.java`

---

## 5. ObjectProvider — Circular Dependency Resolution

**Problem:** Some Spring beans have circular dependencies that prevent constructor injection.

**Solution:** Use `ObjectProvider<T>` (lazy resolution) instead of direct constructor injection. Spring resolves the bean on first access, breaking the cycle.

```java
@RequiredArgsConstructor
public class SomeService {
    private final ObjectProvider<OtherService> otherProvider;

    void doWork() {
        OtherService other = otherProvider.getObject(); // resolved lazily
    }
}
```

**Where:** Used in services where runtime references are circular (e.g., `AgentRuntimeService` ↔ `AgentStreamingService`).

---

## 6. @PostConstruct — Derived Fields

**Problem:** Some fields are derived from injected properties but can't be computed in the constructor (Lombok `@RequiredArgsConstructor` generates the constructor).

**Solution:** Use `@PostConstruct` to compute derived values after dependency injection.

```java
@RequiredArgsConstructor
public class WebSearchTool {
    private final AgentProperties agentProperties;  // injected by Lombok constructor
    private int configuredLimit;                      // derived → non-final

    @PostConstruct
    void init() {
        configuredLimit = agentProperties.getWeb().getSearchResults();
    }
}
```

**Testing convention:** `new WebSearchTool(props); tool.init();` — call `init()` after construction in unit tests.

**Where:** Throughout `tools/` and `service/` — any bean with derived configuration.

---

## 7. Virtual Threads — Lightweight Parallelism

**Problem:** Parallel tool execution needs a thread per tool call, but platform threads are expensive.

**Solution:** Java 25 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`.

```java
try (ExecutorService parallelExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (ToolCall call : toolCalls) {
        futures.add(CompletableFuture.supplyAsync(() ->
            toolExecutionService.execute(call.name(), ...), parallelExecutor));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

**Where:**
- `DefaultAgentRuntime.executeToolsInParallel()` — parallel tool execution
- `DefaultAgentRuntime.runTurnInternal()` — async memory sync via virtual thread
- `ToolExecutionService` — dedicated virtual thread executor for tools

---

## 8. Programmatic Transactions — TransactionTemplate

**Problem:** `@Transactional` doesn't work via self-invocation (Spring proxy bypass). Streaming code calls transactional methods from the same class.

**Solution:** Use `TransactionTemplate` for programmatic transactions instead of annotation-based `@Transactional`.

```java
@RequiredArgsConstructor
public class AgentStreamingService {
    private final TransactionTemplate transactionTemplate;

    void saveMessages(List<Message> messages) {
        transactionTemplate.execute(status -> {
            messageRepository.saveAll(entities);
            return null;
        });
    }
}
```

**Where:** `AgentStreamingService`, any service that needs transactions in self-invoked methods.

---

## 9. Observer / Hook — Turn Lifecycle

**Problem:** Multiple subsystems need to react to turn events (start, complete, fail) without coupling.

**Solution:** `TurnFinalizer` acts as a lifecycle hook, invoked at every turn exit point with the reason (`COMPLETED`, `BUDGET_EXHAUSTED`, `MODEL_CALL_FAILED`, `GUARDRAIL_HALTED`, `INTERRUPTED`, `MAX_TURNS_REACHED`).

```java
turnFinalizer.finalize(session.id(), turnMessages, success, TurnExitReason.COMPLETED);
```

**Where:** `core/agent/TurnFinalizer.java`, `core/agent/TurnExitReason.java`

---

## 10. Bounded Retry with Error Classification

**Problem:** LLM API calls fail for different reasons (rate limit, network, context overflow, billing). Blind retry wastes resources on non-retryable errors.

**Solution:** `ErrorClassifier` categorises errors; `callModelWithRetry` applies different backoff strategies per error type.

| Error Type | Action |
|------------|--------|
| `RETRYABLE` | Backoff: 500ms × 2^attempt + jitter, cap 5s |
| `RATE_LIMIT` | Longer backoff: 2s × 2^attempt, cap 30s |
| `CONTEXT_OVERFLOW` | Compress context, retry without consuming attempt |
| `PERMANENT` / `BILLING` / `CONTENT_POLICY` | Fail immediately |

**Where:** `client/langchain4j/ErrorClassifier.java`, `DefaultAgentRuntime.callModelWithRetry()`

---

## 11. Builder Pattern — Message Factory Methods

**Problem:** `Message` is a record with 7 fields; most construction only needs a few.

**Solution:** Static factory methods on the record, each filling defaults for the rest.

```java
public static Message user(String content)              { ... }
public static Message system(String content)            { ... }
public static Message assistant(String content, int ti) { ... }
public static Message assistantToolCalls(List<ToolCall> toolCalls, int ti) { ... }
public static Message toolResult(String toolCallId, String content, int ti) { ... }
```

**Where:** `core/model/Message.java`

---

## 12. Decorator — Security Layers

**Problem:** Tool execution, HTTP calls, and output need security wrapping (SSRF, file safety, redaction) without modifying core logic.

**Solution:** Security components wrap core services as cross-cutting concerns:

| Decorator | Wraps | Purpose |
|-----------|-------|---------|
| `SsrfSafeHttpClient` | HTTP client | Blocks private/local IPs |
| `DefaultFileSafety` | File tool operations | Validates paths against allowed list |
| `DefaultUrlSafety` | URL operations | Validates URLs |
| `DefaultRedactor` | Output/logs | Masks API keys, tokens, PII |
| `ToolCallGuardrail` | Tool execution | Halts on suspicious tool patterns |
| `MessageSanitizer` | Message processing | Sanitises messages before model calls |

**Where:** `security/` package

---

## 13. Callback / Handler — Streaming

**Problem:** LLM streaming responses arrive incrementally; the consumer needs to handle each chunk without blocking.

**Solution:** `StreamingResponseHandler` callback interface. The model client invokes callbacks for each token chunk, tool call, and completion.

```java
handler.onContent(chunk);      // token arrived
handler.onToolCalls(toolCalls); // model requests tools
handler.onComplete(usage);      // stream finished
handler.onError(exception);     // stream failed
```

**Where:** `core/client/StreamingResponseHandler.java`, `client/langchain4j/LangChain4jModelClient.java`

---

## 14. Interrupt Token — Cooperative Cancellation

**Problem:** Long-running turns (100 max iterations) need user-initiated cancellation without killing threads.

**Solution:** `InterruptToken` — a `ConcurrentHashMap<UUID, Boolean>` checked at each iteration of the turn loop.

```java
// Set by user (REST endpoint or bot command)
interruptToken.cancel(sessionId);

// Checked in the turn loop
if (interruptToken.isCancelled(session.id())) {
    return new TurnResult(messages, true, "cancelled");
}
```

**Where:** `core/agent/InterruptToken.java`

---

## 15. Steer Buffer — Mid-Turn Injection

**Problem:** User wants to inject guidance mid-turn (e.g., "use a different approach") without interrupting.

**Solution:** `SteerBuffer` — a `ConcurrentHashMap<UUID, String>`. The turn loop checks and consumes the steer note after tool execution, injecting it into the last tool result.

```java
String steerText = steerBuffer.consume(session.id());
if (steerText != null) {
    String enhanced = lastToolResult.content() + "\n\n[STEER NOTE] " + steerText;
    toolResults.set(lastIndex, Message.toolResult(id, enhanced, turnIndex));
}
```

**Where:** `core/agent/SteerBuffer.java`, `DefaultAgentRuntime.runTurnLoop()`

---

## 16. Configured Properties — Type-Safe Configuration

**Problem:** 30+ configuration sections with nested properties, env var overrides, and validation.

**Solution:** `@ConfigurationProperties` with nested static classes, validated by `@Validated`.

```java
@Validated
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private final ModelProperties model = new ModelProperties();
    private final SecurityProperties security = new SecurityProperties();
    // ... 30+ nested property classes
}
```

All settings in `application.yml` with env var overrides (`${ENV:default}`).

**Where:** `config/AgentProperties.java`, `bot/config/BotProperties.java`

---

## 17. Event-Driven Polling — Telegram Long Polling

**Problem:** Telegram bot needs to receive updates without a public webhook endpoint.

**Solution:** `LongPollingService` with `ReconnectWatcher` (exponential backoff). The polling loop runs on a daemon `ScheduledExecutorService`.

```
getUpdates → process → getUpdates → ...
    ↓ on error
ReconnectWatcher → exponential backoff → reconnect
```

**Where:** `bot/polling/LongPollingService.java`, `bot/polling/ReconnectWatcher`

---

## 18. Batch Debouncer — Message Aggregation

**Problem:** Users send multiple rapid messages (text or photos); each should be processed as one combined input.

**Solution:** `TextBatchDebouncer` and `PhotoBatchDebouncer` collect messages within a time window, then dispatch as a single `UpdateEvent`.

**Where:** `bot/batch/TextBatchDebouncer.java`, `bot/batch/PhotoBatchDebouncer.java`