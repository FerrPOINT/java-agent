package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.DefaultToolCallGuardrail;
import com.azhukov.agent.core.security.SecretRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ToolExecutionService retry behavior.
 * Converted from @SpringBootTest to pure unit test with mocks.
 */
@Tag("slow")
class ToolExecutionServiceRetryTest {

    private ToolRegistry toolRegistry;
    private AgentProperties properties;
    private ToolExecutionService service;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getDefinitions(any())).thenReturn(java.util.List.of());
        when(toolRegistry.getDefinitions()).thenReturn(java.util.List.of());
        when(toolRegistry.getToolsets()).thenReturn(Set.of());

        properties = new AgentProperties();
        service = new ToolExecutionService(
            toolRegistry, properties,
            new DefaultToolCallGuardrail(properties),
            new SecretRedactor(properties),
            new ToolResultClassifier(),
            new ToolOutputLimiter(properties),
            null
        );
    }

    @Test
    void retrySucceedsAfterTransientFailures() {
        AtomicInteger counter = new AtomicInteger(0);
        when(toolRegistry.execute(eq("flaky-tool"), anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> {
                int attempt = counter.incrementAndGet();
                if (attempt < 3) {
                    throw new RuntimeException("simulated failure attempt " + attempt);
                }
                return ToolResult.ok("ok-after-retry");
            });

        Session session = Session.create("test", "noop", "");
        ToolResult result = service.execute("flaky-tool", "call-1", "{}", null, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("ok-after-retry");
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void maxRetriesExceededReturnsErrorResult() {
        AtomicInteger counter = new AtomicInteger(0);
        when(toolRegistry.execute(eq("always-fails"), anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> {
                counter.incrementAndGet();
                throw new RuntimeException("permanent failure");
            });

        Session session = Session.create("test", "noop", "");
        ToolResult result = service.execute("always-fails", "call-1", "{}", null, session);

        // After 3 failed attempts, should return a failure result
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("always-fails");
        assertThat(result.error()).contains("permanent failure");
        // Verify exactly 3 attempts were made (maxAttempts=3)
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void nonRetryableExceptionIsNotRetried() {
        // IllegalArgumentException is configured as ignoreExceptions → not retried
        AtomicInteger counter = new AtomicInteger(0);
        when(toolRegistry.execute(eq("bad-args"), anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> {
                counter.incrementAndGet();
                throw new IllegalArgumentException("invalid arguments");
            });

        Session session = Session.create("test", "noop", "");
        ToolResult result = service.execute("bad-args", "call-1", "{}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("bad-args");
        assertThat(result.error()).contains("invalid arguments");
        // Should only be attempted once — IllegalArgumentException is non-retryable
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void successfulExecutionOnFirstAttemptDoesNotRetry() {
        AtomicInteger counter = new AtomicInteger(0);
        when(toolRegistry.execute(eq("good-tool"), anyString(), anyString(), any(), any()))
            .thenAnswer(inv -> {
                counter.incrementAndGet();
                return ToolResult.ok("immediate success");
            });

        Session session = Session.create("test", "noop", "");
        ToolResult result = service.execute("good-tool", "call-1", "{}", null, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("immediate success");
        assertThat(counter.get()).isEqualTo(1);
    }
}