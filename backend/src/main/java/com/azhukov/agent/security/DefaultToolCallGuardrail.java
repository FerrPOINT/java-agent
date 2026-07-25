package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

@Component
public class DefaultToolCallGuardrail implements ToolCallGuardrail {

    private final GuardrailConfig config;
    private final Deque<ToolCallRecord> history = new ArrayDeque<>();
    private boolean halted = false;
    private int consecutiveFailures = 0;
    private final Deque<String> recentToolNames = new ArrayDeque<>();
    private final Deque<String> recentErrorMessages = new ArrayDeque<>();

    public DefaultToolCallGuardrail(AgentProperties properties) {
        this.config = new GuardrailConfig();
        if (properties != null && properties.getBudget() != null) {
            // Optional: bind additional guardrail thresholds from properties if added later.
        }
    }

    @Override
    public GuardrailDecision beforeCall(String toolName, String args) {
        if (halted) {
            return GuardrailDecision.halt(toolName, "guardrail_halted", "Turn already halted by guardrails");
        }
        if (toolName == null || toolName.isBlank()) {
            return GuardrailDecision.block(toolName, "unknown_tool", "Tool name is missing");
        }
        return GuardrailDecision.allow(toolName);
    }

    @Override
    public GuardrailDecision afterCall(String toolName, String args, ToolResult result, boolean failed) {
        history.addLast(new ToolCallRecord(toolName, args, failed, result != null ? result.content() : null));
        if (history.size() > 20) {
            history.removeFirst();
        }

        recentToolNames.addLast(toolName);
        if (recentToolNames.size() > 10) recentToolNames.removeFirst();

        if (failed) {
            consecutiveFailures++;
            recentErrorMessages.addLast(result != null && result.error() != null ? result.error() : "unknown");
            if (recentErrorMessages.size() > 10) recentErrorMessages.removeFirst();

            if (config.isHardStopEnabled() && consecutiveFailures >= config.getHardStopAfterExactFailure()) {
                halted = true;
                return GuardrailDecision.halt(toolName, "repeated_failures", "Too many consecutive tool failures");
            }
            if (config.isWarningsEnabled() && consecutiveFailures == config.getWarnAfterExactFailure()) {
                return GuardrailDecision.warn(toolName, "repeated_failures_warning", "Multiple consecutive tool failures");
            }

            if (config.isHardStopEnabled() && sameToolFailureCount(toolName) >= config.getHardStopAfterSameToolFailure()) {
                halted = true;
                return GuardrailDecision.halt(toolName, "same_tool_repeated_failures", "Tool " + toolName + " keeps failing");
            }
            if (config.isWarningsEnabled() && sameToolFailureCount(toolName) == config.getWarnAfterSameToolFailure()) {
                return GuardrailDecision.warn(toolName, "same_tool_repeated_failures_warning", "Tool " + toolName + " is failing repeatedly");
            }

            if (config.isHardStopEnabled() && idempotentNoProgress(toolName, result) >= config.getHardStopAfterIdempotentNoProgress()) {
                halted = true;
                return GuardrailDecision.halt(toolName, "idempotent_no_progress", "Tool is looping without progress");
            }
            if (config.isWarningsEnabled() && idempotentNoProgress(toolName, result) == config.getWarnAfterIdempotentNoProgress()) {
                return GuardrailDecision.warn(toolName, "idempotent_no_progress_warning", "Tool output is not changing");
            }
        } else {
            consecutiveFailures = 0;
            recentErrorMessages.clear();
        }

        return GuardrailDecision.allow(toolName);
    }

    @Override
    public boolean isHalted() {
        return halted;
    }

    @Override
    public void reset() {
        halted = false;
        history.clear();
        consecutiveFailures = 0;
        recentToolNames.clear();
        recentErrorMessages.clear();
    }

    private long sameToolFailureCount(String toolName) {
        return recentToolNames.stream().filter(n -> Objects.equals(n, toolName)).count();
    }

    private int idempotentNoProgress(String toolName, ToolResult result) {
        int count = 0;
        String current = result != null ? result.content() : null;
        for (ToolCallRecord r : history) {
            if (r.failed && Objects.equals(r.toolName, toolName) && Objects.equals(r.output, current)) {
                count++;
            }
        }
        return count;
    }

    private record ToolCallRecord(String toolName, String args, boolean failed, String output) {}
}
