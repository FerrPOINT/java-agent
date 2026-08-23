package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.metrics.AgentMetrics;
import com.azhukov.agent.core.security.SecretRedactor;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.azhukov.agent.core.state.TurnState;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
@RequiredArgsConstructor
public class ToolExecutionService {

    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;
    private final ToolCallGuardrail guardrail;
    private final SecretRedactor redactor;
    private final ToolResultClassifier toolResultClassifier;
    private final ToolOutputLimiter toolOutputLimiter;
    private final AgentMetrics agentMetrics;
    // Subdirectory hints (Hermes agent/subdirectory_hints.py: appended to the
    // tool RESULT, never the system prompt). Optional wiring — tests construct
    // this service directly with @RequiredArgsConstructor semantics.
    private com.azhukov.agent.core.context.SubdirectoryHintsService subdirectoryHints;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Retry retry = Retry.of("tool", RetryConfig.custom()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(500))
            .retryExceptions(RuntimeException.class)
            .ignoreExceptions(IllegalArgumentException.class)
            .build());

    public ToolResult execute(String toolName, String toolCallId, String arguments, Message lastAssistant, Session session, TurnState turnState) {
        var before = turnState == null ? guardrail.beforeCall(toolName, arguments) : guardrail.beforeCall(toolName, arguments, turnState);
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

        if (agentMetrics != null) {
            agentMetrics.incrementToolCalls(toolName);
            if (failed) {
                agentMetrics.incrementToolErrors(toolName);
            }
        }

        if (turnState != null) {
            turnState.recordExecution(new com.azhukov.agent.core.model.ToolCall(toolCallId, toolName, arguments), result, duration);
        }

        var after = turnState == null
            ? guardrail.afterCall(toolName, arguments, result, failed)
            : guardrail.afterCall(toolName, arguments, result, failed, turnState);
        if (after.isBlockOrHalt()) {
            log.warn("Guardrail {} after tool {}: {}", after.action(), toolName, after.message());
            result = ToolResult.fail((result.error() != null ? result.error() + "\n" : "") + "Guardrail: " + after.message());
        }

        String safeContent = result.success() ? redactor.redact(result.content()) : redactor.redact(result.error());
        // Subdirectory hints (Hermes tool_executor.py:1768): on first visit to a
        // directory via a path/command argument, append AGENTS.md/CLAUDE.md/
        // .cursorrules content to the tool result. Only successful results get
        // hints (a failed call tells the model nothing about the project layout).
        if (subdirectoryHints != null && result.success()) {
            try {
                java.util.Map<String, Object> argsMap = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readValue(arguments, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                String hints = subdirectoryHints.checkToolCall(toolName, argsMap);
                if (hints != null && !hints.isBlank()) {
                    safeContent = safeContent + hints;
                }
            } catch (Exception hintEx) {
                log.debug("subdirectory hints skipped for {}: {}", toolName, hintEx.getMessage());
            }
        }
        ToolResult safeResult = result.success() ? ToolResult.ok(safeContent) : ToolResult.fail(safeContent);
        // Classify result
        if (toolResultClassifier != null) {
            var resultType = toolResultClassifier.classify(safeResult);
            log.debug("Tool {} result classified as: {}", toolName, resultType);
        }
        // Truncate output
        if (toolOutputLimiter != null) {
            return toolOutputLimiter.truncate(safeResult);
        }
        return truncateIfNeeded(safeResult, toolName);
    }

    public ToolResult execute(String toolName, String toolCallId, String arguments, Message lastAssistant, Session session) {
        return execute(toolName, toolCallId, arguments, lastAssistant, session, null);
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

    /**
     * Optional wiring for subdirectory hints (Hermes parity). Setter injection
     * keeps the @RequiredArgsConstructor signature stable for the many direct
     * constructions in tests.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSubdirectoryHints(com.azhukov.agent.core.context.SubdirectoryHintsService hints) {
        this.subdirectoryHints = hints;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
