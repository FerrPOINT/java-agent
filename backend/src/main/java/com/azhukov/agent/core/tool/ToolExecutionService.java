package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.security.SecretRedactor;
import com.azhukov.agent.security.ToolCallGuardrail;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component
public class ToolExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;
    private final ExecutorService executor;
    private final Retry retry;
    private final ToolCallGuardrail guardrail;
    private final SecretRedactor redactor;

    public ToolExecutionService(ToolRegistry toolRegistry, AgentProperties properties, ToolCallGuardrail guardrail, SecretRedactor redactor) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
        this.guardrail = guardrail;
        this.redactor = redactor;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        RetryConfig config = RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .retryExceptions(RuntimeException.class)
            .ignoreExceptions(IllegalArgumentException.class)
            .build();
        this.retry = Retry.of("tool", config);
    }

    public ToolResult execute(String toolName, String toolCallId, String arguments, Message lastAssistant, Session session) {
        var before = guardrail.beforeCall(toolName, arguments);
        if (before.isBlockOrHalt()) {
            log.warn("Guardrail {} tool {}: {}", before.action(), toolName, before.message());
            return ToolResult.fail(before.message());
        }

        long start = System.currentTimeMillis();
        Callable<ToolResult> callable = () -> toolRegistry.execute(toolName, toolCallId, arguments, lastAssistant, session);
        Supplier<ToolResult> decorated = Retry.decorateSupplier(retry, () -> {
            try {
                return executor.submit(callable).get(
                    properties.getToolOutput().getTimeoutSecondsOrDefault(120), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Tool {} timed out after {}s", toolName, properties.getToolOutput().getTimeoutSecondsOrDefault(120));
                return ToolResult.fail("Tool timed out: " + toolName);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.fail("Interrupted: " + toolName);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException r) {
                    throw r;
                }
                throw new RuntimeException(cause);
            }
        });

        ToolResult result;
        boolean failed;
        try {
            result = decorated.get();
            failed = !result.success();
        } catch (Exception e) {
            log.warn("Tool {} execution failed after retries: {}", toolName, e.getMessage());
            result = ToolResult.fail("Tool execution failed: " + toolName + " - " + e.getMessage());
            failed = true;
        }
        long duration = System.currentTimeMillis() - start;
        log.debug("Tool {} executed in {} ms (success={}, length={})",
            toolName, duration, result.success(), result.content() != null ? result.content().length() : 0);

        var after = guardrail.afterCall(toolName, arguments, result, failed);
        if (after.isBlockOrHalt()) {
            log.warn("Guardrail {} after tool {}: {}", after.action(), toolName, after.message());
            result = ToolResult.fail((result.error() != null ? result.error() + "\n" : "") + "Guardrail: " + after.message());
        }

        String safeContent = result.success() ? redactor.redact(result.content()) : redactor.redact(result.error());
        ToolResult safeResult = result.success() ? ToolResult.ok(safeContent) : ToolResult.fail(safeContent);
        return truncateIfNeeded(safeResult, toolName);
    }

    private ToolResult truncateIfNeeded(ToolResult result, String toolName) {
        int max = properties.getToolOutput().getMaxChars();
        if (result.content() == null || result.content().length() <= max) {
            return result;
        }
        String truncated = result.content().substring(0, max);
        log.warn("Tool {} output truncated from {} to {} chars", toolName, result.content().length(), max);
        return ToolResult.ok(truncated + "\n[output truncated]");
    }
}
