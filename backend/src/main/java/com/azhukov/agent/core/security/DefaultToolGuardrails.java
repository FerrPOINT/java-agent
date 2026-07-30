package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolCall;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class DefaultToolGuardrails implements ToolGuardrails {

    private final AgentProperties properties;

    // ─── Stateful tracking for loop detection ───

    private static final int IDENTICAL_CALL_HALT_THRESHOLD = 5;
    private static final int CONSECUTIVE_FAILURE_HALT_THRESHOLD = 3;
    private static final int TOTAL_CALL_WARN_THRESHOLD = 10;

    private final Map<String, Integer> toolCallCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> toolFailureCount = new ConcurrentHashMap<>();
    private final Map<String, String> lastToolArgs = new ConcurrentHashMap<>();
    private final Map<String, Integer> identicalArgsCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private volatile boolean halted = false;
    private volatile boolean warned = false;

    private Set<String> blockedTools = Set.of();

    @Override
    public boolean isToolAllowed(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (blockedTools.contains(toolName)) {
            return false;
        }
        if (halted) {
            return false;
        }
        return true;
    }

    @Override
    public boolean requiresApproval(ToolCall call) {
        if (!properties.getSecurity().isApprovalsEnabled() || call == null) {
            return false;
        }
        List<String> destructive = properties.getSecurity().getAlwaysRequireApprovalTools();
        return destructive != null && destructive.contains(call.name());
    }

    @Override
    public void recordToolCall(String toolName, String args, boolean success) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }

        // Track total call count
        int calls = toolCallCount.merge(toolName, 1, Integer::sum);

        // Track identical args (same tool + same args)
        String prevArgs = lastToolArgs.put(toolName, args != null ? args : "");
        if (prevArgs != null && prevArgs.equals(args != null ? args : "")) {
            int identical = identicalArgsCount.merge(toolName, 1, Integer::sum);
            if (identical >= IDENTICAL_CALL_HALT_THRESHOLD) {
                halted = true;
            }
        } else {
            identicalArgsCount.put(toolName, 1);
        }

        // Track consecutive failures
        if (!success) {
            int failures = consecutiveFailures.merge(toolName, 1, Integer::sum);
            toolFailureCount.merge(toolName, 1, Integer::sum);
            if (failures >= CONSECUTIVE_FAILURE_HALT_THRESHOLD) {
                halted = true;
            }
        } else {
            consecutiveFailures.put(toolName, 0);
        }

        // Warn threshold: any tool called 10+ times total
        if (!warned && calls >= TOTAL_CALL_WARN_THRESHOLD) {
            warned = true;
        }
    }

    @Override
    public boolean isHalted() {
        return halted;
    }

    @Override
    public void reset() {
        toolCallCount.clear();
        toolFailureCount.clear();
        lastToolArgs.clear();
        identicalArgsCount.clear();
        consecutiveFailures.clear();
        halted = false;
        warned = false;
    }

    @Override
    public Set<String> getBlockedTools() {
        return blockedTools;
    }

    @Override
    public void setBlockedTools(Set<String> blockedTools) {
        this.blockedTools = blockedTools != null ? blockedTools : Set.of();
    }
}