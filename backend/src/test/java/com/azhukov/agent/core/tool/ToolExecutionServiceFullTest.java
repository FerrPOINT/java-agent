package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.DefaultToolCallGuardrail;
import com.azhukov.agent.core.security.SecretRedactor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutionServiceFullTest {

    private AgentProperties properties = new AgentProperties();

    private com.azhukov.agent.core.security.ToolCallGuardrail guardrail() {
        return new DefaultToolCallGuardrail(properties);
    }

    private SecretRedactor redactor() {
        return new SecretRedactor(properties);
    }

    private ToolExecutionService buildService(ToolRegistry registry) {
        return buildService(registry, properties);
    }

    private ToolExecutionService buildService(ToolRegistry registry, AgentProperties props) {
        return new ToolExecutionService(registry, props, guardrail(), redactor(),
            new ToolResultClassifier(), new ToolOutputLimiter(props), null);
    }

    private ToolRegistry buildRegistry(ToolResult result) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.execute(eq("ok"), any(), any(), any(), any())).thenReturn(result);
        when(registry.execute(eq("throw"), any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
        when(registry.execute(eq("arg-error"), any(), any(), any(), any())).thenThrow(new IllegalArgumentException("bad"));
        return registry;
    }

    private ToolRegistry buildSlowRegistry(long delayMs, AtomicInteger calls) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.execute(eq("slow"), any(), any(), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            Thread.sleep(delayMs);
            return ToolResult.ok("done");
        });
        return registry;
    }

    @Test
    void returnsSuccessfulToolResult() {
        ToolRegistry registry = buildRegistry(ToolResult.ok("hello"));
        ToolExecutionService service = buildService(registry);

        ToolResult result = service.execute("ok", "c1", "{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void truncatesLongOutput() {
        properties.getToolOutput().setMaxChars(5);
        ToolRegistry registry = buildRegistry(ToolResult.ok("1234567890"));
        ToolExecutionService service = buildService(registry);

        ToolResult result = service.execute("ok", "c1", "{}", null, null);

        // head+tail truncation (Hermes parity): 5-char cap → 2 head + notice + 3 tail
        assertThat(result.content()).startsWith("12");
        assertThat(result.content()).endsWith("890");
        assertThat(result.content()).contains("OUTPUT TRUNCATED");
    }

    @Test
    void retriesRuntimeExceptionAndFails() {
        ToolRegistry registry = buildRegistry(null);
        ToolExecutionService service = buildService(registry);

        ToolResult result = service.execute("throw", "c1", "{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("boom");
    }

    @Test
    void doesNotRetryIllegalArgumentException() {
        ToolRegistry registry = buildRegistry(null);
        ToolExecutionService service = buildService(registry);

        ToolResult result = service.execute("arg-error", "c1", "{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("bad");
    }

    @Test
    void timesOutSlowTool() {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setTimeoutSeconds(1);
        props.getToolOutput().setMaxChars(1000);
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = buildSlowRegistry(5000, calls);
        ToolExecutionService service = buildService(registry, props);

        ToolResult result = service.execute("slow", "c1", "{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timed out");
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void timeoutCancelsRunningToolTask() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getToolOutput().setTimeoutSeconds(1);
        props.getToolOutput().setMaxChars(1000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.execute(eq("slow"), any(), any(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
                return ToolResult.ok("late");
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                interrupted.countDown();
                Thread.currentThread().interrupt();
                return ToolResult.fail("interrupted");
            }
        });
        ToolExecutionService service = buildService(registry, props);

        ToolResult result = service.execute("slow", "c1", "{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timed out");
        assertThat(started.await(100, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(wasInterrupted.get()).isTrue();
        service.shutdown();
    }

    @Test
    void retryAttemptsUpToThreeTimes() {
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.execute(eq("flaky"), any(), any(), any(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("retry");
            }
            return ToolResult.ok("finally");
        });
        ToolExecutionService service = buildService(registry);

        ToolResult result = service.execute("flaky", "c1", "{}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("finally");
        assertThat(calls.get()).isEqualTo(3);
    }
}
