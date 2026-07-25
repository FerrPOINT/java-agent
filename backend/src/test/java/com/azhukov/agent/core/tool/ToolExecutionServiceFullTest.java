package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutionServiceFullTest {

    private AgentProperties properties = new AgentProperties();

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
        ToolExecutionService service = new ToolExecutionService(registry, properties);

        ToolResult result = service.execute("ok", "c1", "{}", Message.user("hi"), Session.create("u", "p", "m"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void truncatesLongOutput() {
        properties.getToolOutput().setMaxChars(5);
        ToolRegistry registry = buildRegistry(ToolResult.ok("1234567890"));
        ToolExecutionService service = new ToolExecutionService(registry, properties);

        ToolResult result = service.execute("ok", "c1", "{}", null, null);

        assertThat(result.content()).isEqualTo("12345\n[output truncated]");
    }

    @Test
    void retriesRuntimeExceptionAndFails() {
        ToolRegistry registry = buildRegistry(null);
        ToolExecutionService service = new ToolExecutionService(registry, properties);

        ToolResult result = service.execute("throw", "c1", "{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("boom");
    }

    @Test
    void doesNotRetryIllegalArgumentException() {
        ToolRegistry registry = buildRegistry(null);
        ToolExecutionService service = new ToolExecutionService(registry, properties);

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
        ToolExecutionService service = new ToolExecutionService(registry, props);

        ToolResult result = service.execute("slow", "c1", "{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timed out");
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
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
        ToolExecutionService service = new ToolExecutionService(registry, properties);

        ToolResult result = service.execute("flaky", "c1", "{}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("finally");
        assertThat(calls.get()).isEqualTo(3);
    }
}
