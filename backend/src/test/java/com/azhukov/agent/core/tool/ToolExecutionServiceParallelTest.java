package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.security.DefaultToolCallGuardrail;
import com.azhukov.agent.security.SecretRedactor;
import com.azhukov.agent.security.ToolCallGuardrail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for P0 gap: Parallel tool execution.
 * <p>
 * Tests {@link ToolExecutionService} for current behaviour and documents gaps:
 * - GAP: Tools are executed SEQUENTIALLY in the agent runtime loop, not in parallel.
 *   The ToolExecutionService itself uses a virtual thread executor for each call,
 *   but DefaultAgentRuntime calls execute() in a for-loop one at a time.
 * - The executor in ToolExecutionService is for timeout enforcement (submit + get with timeout),
 *   not for parallel execution of multiple tools.
 * - AGENTS.md says "Virtual threads для tool execution" and "parallel tool calls"
 *   but the actual runtime executes tools sequentially.
 */
class ToolExecutionServiceParallelTest {

    private AgentProperties properties = new AgentProperties();

    private ToolCallGuardrail guardrail() {
        return new DefaultToolCallGuardrail(properties);
    }

    private SecretRedactor redactor() {
        return new SecretRedactor(properties);
    }

    private ToolExecutionService buildService(ToolRegistry registry) {
        return new ToolExecutionService(registry, properties, guardrail(), redactor(),
            new ToolResultClassifier(), new ToolOutputLimiter(properties));
    }

    // ─── Sequential execution verification ───

    @Nested
    @DisplayName("Sequential execution (current behaviour)")
    class SequentialExecution {

        @Test
        @DisplayName("Tools execute one at a time — execution is sequential within the service")
        void singleToolExecutesWithExecutor() {
            AtomicInteger callCount = new AtomicInteger(0);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("tool-a"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    callCount.incrementAndGet();
                    return ToolResult.ok("result-a");
                });

            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("tool-a", "c1", "{}", null, null);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("result-a");
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("GAP: Multiple tool calls from runtime are processed sequentially (not parallel)")
        void gap_toolsAreSequentialNotParallel() {
            // Simulate what DefaultAgentRuntime does: it calls toolExecutionService.execute()
            // in a for-loop, one tool call at a time. This test verifies the sequential nature.
            AtomicInteger concurrentExecutions = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);

            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(any(String.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int current = concurrentExecutions.incrementAndGet();
                    maxConcurrent.accumulateAndGet(current, Math::max);
                    Thread.sleep(100); // Simulate work
                    concurrentExecutions.decrementAndGet();
                    String toolName = inv.getArgument(0);
                    return ToolResult.ok("result-" + toolName);
                });

            ToolExecutionService service = buildService(registry);

            // Simulate the runtime's sequential tool execution loop
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "tool-a", "{}"),
                new ToolCall("c2", "tool-b", "{}"),
                new ToolCall("c3", "tool-c", "{}")
            );

            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : calls) {
                results.add(service.execute(call.name(), call.id(), call.arguments(), null, null));
            }

            // All tools executed successfully
            assertThat(results).hasSize(3);
            assertThat(results).allMatch(ToolResult::success);
            // GAP: max concurrent execution was 1 — tools ran sequentially
            assertThat(maxConcurrent.get()).isEqualTo(1);
            // If tools ran in parallel, maxConcurrent would be 3
        }

        @Test
        @DisplayName("GAP: If tools were parallel, total time would be ~max(individual) not sum")
        void gap_sequentialTimingVsParallel() {
            // This test documents the timing gap: sequential execution takes sum of all
            // tool durations, while parallel would take max.
            long[] timestamps = new long[3];
            AtomicInteger idx = new AtomicInteger(0);

            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(any(String.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int i = idx.getAndIncrement();
                    timestamps[i] = System.currentTimeMillis();
                    Thread.sleep(100);
                    return ToolResult.ok("ok");
                });

            ToolExecutionService service = buildService(registry);

            long start = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) {
                service.execute("tool", "c" + i, "{}", null, null);
            }
            long total = System.currentTimeMillis() - start;

            // Sequential: total should be ~300ms (3 * 100ms)
            assertThat(total).isGreaterThanOrEqualTo(250); // Allow some jitter
            // GAP: if parallel, total would be ~100ms
        }
    }

    // ─── Tool result output limiting ───

    @Nested
    @DisplayName("Tool result output limiting")
    class OutputLimiting {

        @Test
        @DisplayName("Output is truncated to maxChars when exceeding limit")
        void outputTruncatedToMaxChars() {
            properties.getToolOutput().setMaxChars(10);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("big"), any(), any(), any(), any()))
                .thenReturn(ToolResult.ok("1234567890ABCDEF"));

            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("big", "c1", "{}", null, null);

            assertThat(result.content()).hasSizeLessThanOrEqualTo(50); // 10 + suffix
            assertThat(result.content()).startsWith("1234567890");
            assertThat(result.content()).contains("[output truncated");
        }

        @Test
        @DisplayName("Output within limit is not truncated")
        void outputWithinLimitNotTruncated() {
            properties.getToolOutput().setMaxChars(1000);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("small"), any(), any(), any(), any()))
                .thenReturn(ToolResult.ok("short output"));

            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("small", "c1", "{}", null, null);

            assertThat(result.content()).isEqualTo("short output");
        }

        @Test
        @DisplayName("Failed tool result error is truncated when exceeding limit")
        void errorOutputTruncated() {
            properties.getToolOutput().setMaxChars(10);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("fail"), any(), any(), any(), any()))
                .thenReturn(ToolResult.fail("Very long error message that exceeds the limit 1234567890ABCDEF"));

            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("fail", "c1", "{}", null, null);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("[output truncated");
        }

        @Test
        @DisplayName("Default maxChars is 16000")
        void defaultMaxChars() {
            // Verify the default configuration
            assertThat(properties.getToolOutput().getMaxChars()).isEqualTo(16000);
        }
    }

    // ─── Interrupt between tool calls ───

    @Nested
    @DisplayName("Interrupt between tool calls")
    class InterruptBehaviour {

        @Test
        @DisplayName("GAP: ToolExecutionService has no interrupt mechanism — relies on runtime")
        void gap_noInterruptMechanismInService() {
            // ToolExecutionService doesn't check any interrupt token between calls.
            // The interrupt check is in DefaultAgentRuntime's loop (interruptToken.isCancelled).
            // This test verifies that ToolExecutionService itself doesn't have interrupt logic.
            AtomicInteger callCount = new AtomicInteger(0);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(any(String.class), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    callCount.incrementAndGet();
                    return ToolResult.ok("ok");
                });

            ToolExecutionService service = buildService(registry);

            // Call execute 3 times — all should succeed, no interrupt possible at service level
            for (int i = 0; i < 3; i++) {
                ToolResult result = service.execute("tool", "c" + i, "{}", null, null);
                assertThat(result.success()).isTrue();
            }
            assertThat(callCount.get()).isEqualTo(3);
            // GAP: interrupt is only checked between tool calls in DefaultAgentRuntime,
            // not within ToolExecutionService itself
        }

        @Test
        @DisplayName("Tool execution can be interrupted via Thread.interrupt (timeout path)")
        void toolExecutionInterruptionViaTimeout() {
            // When a tool times out, the executor.get() throws TimeoutException
            // and the service returns a failure result
            properties.getToolOutput().setTimeoutSeconds(1);
            properties.getToolOutput().setMaxChars(1000);

            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("slow"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Thread.sleep(5000);
                    return ToolResult.ok("done");
                });

            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("slow", "c1", "{}", null, null);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("timed out");
        }
    }

    // ─── Tool execution failure doesn't stop subsequent tools ───

    @Nested
    @DisplayName("Tool failure and subsequent tools")
    class ToolFailureAndSubsequent {

        @Test
        @DisplayName("A failed tool returns failure result — runtime decides whether to continue")
        void failedToolReturnsFailure() {
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("fail-tool"), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("tool crashed"));
            when(registry.execute(eq("ok-tool"), any(), any(), any(), any()))
                .thenReturn(ToolResult.ok("ok"));

            ToolExecutionService service = buildService(registry);

            // First tool fails
            ToolResult failResult = service.execute("fail-tool", "c1", "{}", null, null);
            assertThat(failResult.success()).isFalse();
            assertThat(failResult.error()).contains("tool crashed");

            // Second tool succeeds — service doesn't prevent subsequent calls
            ToolResult okResult = service.execute("ok-tool", "c2", "{}", null, null);
            assertThat(okResult.success()).isTrue();
            assertThat(okResult.content()).isEqualTo("ok");
        }

        @Test
        @DisplayName("GAP: In DefaultAgentRuntime, tool failure doesn't stop the turn — all tools execute")
        void gap_toolFailureDoesNotStopSubsequentInRuntime() {
            // This test simulates what DefaultAgentRuntime does: it executes all tool calls
            // in the response, even if one fails. The runtime doesn't check result.success()
            // to decide whether to continue the loop.
            AtomicInteger executionOrder = new AtomicInteger(0);
            List<String> executionSequence = new java.util.concurrent.CopyOnWriteArrayList<>();

            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("tool-1"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    executionSequence.add("tool-1:" + executionOrder.incrementAndGet());
                    return ToolResult.ok("result-1");
                });
            when(registry.execute(eq("tool-2"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    executionSequence.add("tool-2:" + executionOrder.incrementAndGet());
                    throw new RuntimeException("tool-2 failed");
                });
            when(registry.execute(eq("tool-3"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    executionSequence.add("tool-3:" + executionOrder.incrementAndGet());
                    return ToolResult.ok("result-3");
                });

            ToolExecutionService service = buildService(registry);

            // Simulate runtime's for-loop over tool calls
            List<ToolCall> calls = List.of(
                new ToolCall("c1", "tool-1", "{}"),
                new ToolCall("c2", "tool-2", "{}"),
                new ToolCall("c3", "tool-3", "{}")
            );

            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : calls) {
                results.add(service.execute(call.name(), call.id(), call.arguments(), null, null));
            }

            // All 3 tools were executed (tool-2 failure didn't stop tool-3)
            // tool-2 throws RuntimeException, so it's retried 3 times before failing
            assertThat(executionSequence).contains("tool-1:1");
            assertThat(executionSequence).contains("tool-3:" + (executionOrder.get()));
            // tool-2 was attempted 3 times (retry mechanism)
            long tool2Count = executionSequence.stream().filter(s -> s.startsWith("tool-2:")).count();
            assertThat(tool2Count).isEqualTo(3); // 3 retry attempts
            assertThat(results).hasSize(3);
            assertThat(results.get(0).success()).isTrue();
            assertThat(results.get(1).success()).isFalse();
            assertThat(results.get(2).success()).isTrue();
        }

        @Test
        @DisplayName("Tool that throws RuntimeException is retried up to 3 times")
        void RuntimeExceptionRetriedUpToThreeTimes() {
            AtomicInteger calls = new AtomicInteger(0);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("flaky"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int attempt = calls.incrementAndGet();
                    if (attempt < 3) {
                        throw new RuntimeException("transient error");
                    }
                    return ToolResult.ok("finally succeeded");
                });

            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("flaky", "c1", "{}", null, null);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("finally succeeded");
            assertThat(calls.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("Tool that keeps throwing RuntimeException fails after 3 retries")
        void persistentRuntimeExceptionFailsAfterRetries() {
            AtomicInteger calls = new AtomicInteger(0);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("always-fail"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("permanent failure");
                });

            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("always-fail", "c1", "{}", null, null);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("permanent failure");
            assertThat(calls.get()).isEqualTo(3); // 3 retry attempts
        }

        @Test
        @DisplayName("IllegalArgumentException is NOT retried (fails immediately)")
        void illegalArgumentNotRetried() {
            AtomicInteger calls = new AtomicInteger(0);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("bad-args"), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    calls.incrementAndGet();
                    throw new IllegalArgumentException("invalid arguments");
                });

            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("bad-args", "c1", "{}", null, null);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("invalid arguments");
            assertThat(calls.get()).isEqualTo(1); // No retry for IllegalArgumentException
        }
    }

    // ─── Secret redaction in tool output ───

    @Nested
    @DisplayName("Secret redaction")
    class SecretRedaction {

        @Test
        @DisplayName("API keys in tool output are redacted")
        void apiKeysRedacted() {
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("show-config"), any(), any(), any(), any()))
                .thenReturn(ToolResult.ok("config: api_key=sk-abc123def456ghi789jkl012mno345pqr"));

            properties.getToolOutput().setMaxChars(10000);
            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("show-config", "c1", "{}", null, null);

            assertThat(result.content()).doesNotContain("sk-abc123def456ghi789jkl012mno345pqr");
            assertThat(result.content()).contains("[REDACTED:");
        }

        @Test
        @DisplayName("Secrets in error messages are also redacted")
        void secretsInErrorsRedacted() {
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.execute(eq("fail-with-secret"), any(), any(), any(), any()))
                .thenReturn(ToolResult.fail("Error: password=mypass12345678"));

            properties.getToolOutput().setMaxChars(10000);
            ToolExecutionService service = buildService(registry);
            ToolResult result = service.execute("fail-with-secret", "c1", "{}", null, null);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).doesNotContain("mypass12345678");
        }
    }
}