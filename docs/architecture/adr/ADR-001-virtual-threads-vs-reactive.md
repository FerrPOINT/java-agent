# ADR-001: Virtual Threads over Reactive for Concurrency

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2025-06-01 |
| **Deciders** | Project lead |
| **Tags** | concurrency, java-25, performance |

## Context

The agent runtime needs to:

1. Execute multiple tool calls in parallel within a single turn.
2. Sync memory asynchronously after each turn without blocking the response.
3. Handle concurrent sessions (REST + streaming) efficiently.
4. Run background tasks (curator, review, reconnect watcher) on daemon threads.

The classic approaches are:

- **Reactive** (Project Reactor / WebFlux): Non-blocking event loop, `Mono`/`Flux` chains.
- **Platform thread pools**: `ExecutorService` with fixed/cached threads.
- **Virtual threads** (Java 21+, stable in Java 25): Lightweight threads managed by the JVM.

## Decision

Use **Java 25 virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`) for all concurrency.

Reactive programming was rejected — it introduces a paradigm shift (non-blocking chains, `subscribeOn`/`publishOn`, reactor context) that increases cognitive load and makes debugging harder. The codebase is already complex (tool loop, guardrails, approval gates, steer injection); adding reactive would compound that.

Platform thread pools were rejected — they don't scale for I/O-heavy workloads (each LLM call blocks for seconds; each tool call may do network I/O). Thread-per-request with platform threads caps at ~hundreds of concurrent requests.

## Consequences

**Positive:**

- Simple blocking code model — write `Thread.sleep`, `.join()`, `.get()` without worrying about pinning.
- Millions of concurrent virtual threads possible (JVM-managed, ~few KB per thread).
- No reactive paradigm — easier onboarding, debugging, and maintenance.
- `try-with-resources` on `ExecutorService` auto-closes after tasks complete.
- Fits naturally with Spring Boot 4.1 (which supports virtual threads for request handling).

**Negative:**

- `synchronized` blocks can pin carrier threads — use `ReentrantLock` in hot paths if needed.
- Stack traces are deep (virtual thread + carrier thread) — debugging needs `-Djdk.tracePinnedThreads`.
- Not all libraries are virtual-thread-safe (e.g., legacy `synchronized` JDBC drivers).

**Mitigations:**

- Use `ReentrantLock` instead of `synchronized` in concurrent hot paths.
- Verify JDBC driver (PostgreSQL) supports virtual threads — it does in recent versions.
- Daemon `ScheduledExecutorService` for background tasks to prevent JVM hang on shutdown.

## Examples

```java
// Parallel tool execution
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (ToolCall call : toolCalls) {
        futures.add(CompletableFuture.supplyAsync(() -> execute(call), executor));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}

// Async memory sync
var executor = Executors.newVirtualThreadPerTaskExecutor();
executor.submit(() -> memoryProvider.syncTurn(sessionId, messages));
executor.shutdown();
```

## References

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- `DefaultAgentRuntime.executeToolsInParallel()`
- `DefaultAgentRuntime.runTurnInternal()` — memory sync
- `ToolExecutionService` — dedicated virtual thread executor
