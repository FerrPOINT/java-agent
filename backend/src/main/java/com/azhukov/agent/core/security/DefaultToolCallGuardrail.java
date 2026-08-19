package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.state.TurnState;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefaultToolCallGuardrail implements ToolCallGuardrail {

    private final GuardrailConfig config;

    // ─── Per-session state ───

    /**
     * All mutable guardrail state is keyed by sessionId so concurrent sessions
     * don't interfere with each other. The old singleton fields (history,
     * halted, consecutiveFailures, etc.) are now contained in this record.
     */
    private final Map<UUID, GuardrailSessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * Per-session guardrail state. Each field was previously a singleton-level
     * instance variable on DefaultToolCallGuardrail, causing cross-session
     * interference when multiple sessions ran concurrently.
     */
    private static class GuardrailSessionState {
        final Deque<ToolCallRecord> history = new ArrayDeque<>();
        volatile boolean halted = false;
        int consecutiveFailures = 0;
        final Deque<String> recentToolNames = new ArrayDeque<>();
        final Deque<String> recentErrorMessages = new ArrayDeque<>();

        void clear() {
            history.clear();
            halted = false;
            consecutiveFailures = 0;
            recentToolNames.clear();
            recentErrorMessages.clear();
        }
    }

    // Fallback session ID for backward compatibility when no session context is available
    private static final UUID GLOBAL_SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /**
     * Returns the GuardrailSessionState for the current thread's session context,
     * or the global fallback if no session is set.
     */
    private GuardrailSessionState stateFor() {
        UUID sessionId = InterruptToken.currentSessionId();
        if (sessionId == null) {
            sessionId = GLOBAL_SESSION_ID;
        }
        return sessionStates.computeIfAbsent(sessionId, k -> new GuardrailSessionState());
    }

    /**
     * Returns the GuardrailSessionState for a specific session, or null if absent.
     */
    private GuardrailSessionState stateFor(UUID sessionId) {
        if (sessionId == null) {
            sessionId = GLOBAL_SESSION_ID;
        }
        return sessionStates.get(sessionId);
    }

    /**
     * Removes the guardrail state for the given session.
     * Called when a session ends to prevent memory leaks.
     */
    public void removeSession(UUID sessionId) {
        if (sessionId != null) {
            sessionStates.remove(sessionId);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultToolCallGuardrail(AgentProperties properties) {
        this(new GuardrailConfig());
        if (properties != null && properties.getBudget() != null) {
            // Optional: bind additional guardrail thresholds from properties if added later.
        }
    }

    public DefaultToolCallGuardrail(GuardrailConfig config) {
        this.config = config;
    }

    @Override
    public GuardrailDecision beforeCall(String toolName, String args) {
        GuardrailSessionState state = stateFor();
        if (state.halted) {
            return GuardrailDecision.halt(toolName, "guardrail_halted", "Turn already halted by guardrails");
        }
        if (toolName == null || toolName.isBlank()) {
            return GuardrailDecision.block(toolName, "unknown_tool", "Tool name is missing");
        }
        return GuardrailDecision.allow(toolName);
    }

    @Override
    public GuardrailDecision afterCall(String toolName, String args, ToolResult result, boolean failed) {
        return afterCall(toolName, args, result, failed, null);
    }

    @Override
    public GuardrailDecision afterCall(String toolName, String args, ToolResult result, boolean failed, TurnState stateArg) {
        GuardrailSessionState state = stateFor();
        state.history.addLast(new ToolCallRecord(toolName, args, failed, result != null ? result.content() : null));
        if (state.history.size() > 20) {
            state.history.removeFirst();
        }

        state.recentToolNames.addLast(toolName);
        if (state.recentToolNames.size() > 10) state.recentToolNames.removeFirst();

        if (failed) {
            state.consecutiveFailures++;
            state.recentErrorMessages.addLast(result != null && result.error() != null ? result.error() : "unknown");
            if (state.recentErrorMessages.size() > 10) state.recentErrorMessages.removeFirst();

            if (config.isHardStopEnabled() && state.consecutiveFailures >= config.getHardStopAfterExactFailure()) {
                state.halted = true;
                return GuardrailDecision.halt(toolName, "repeated_failures", "Too many consecutive tool failures");
            }
            if (config.isWarningsEnabled() && state.consecutiveFailures == config.getWarnAfterExactFailure()) {
                return GuardrailDecision.warn(toolName, "repeated_failures_warning", "Multiple consecutive tool failures");
            }

            if (config.isHardStopEnabled() && sameToolFailureCount(state, toolName) >= config.getHardStopAfterSameToolFailure()) {
                state.halted = true;
                return GuardrailDecision.halt(toolName, "same_tool_repeated_failures", "Tool " + toolName + " keeps failing");
            }
            if (config.isWarningsEnabled() && sameToolFailureCount(state, toolName) == config.getWarnAfterSameToolFailure()) {
                return GuardrailDecision.warn(toolName, "same_tool_repeated_failures_warning", "Tool " + toolName + " is failing repeatedly");
            }

            if (config.isHardStopEnabled() && idempotentNoProgress(state, toolName, result) >= config.getHardStopAfterIdempotentNoProgress()) {
                state.halted = true;
                return GuardrailDecision.halt(toolName, "idempotent_no_progress", "Tool is looping without progress");
            }
            if (config.isWarningsEnabled() && idempotentNoProgress(state, toolName, result) == config.getWarnAfterIdempotentNoProgress()) {
                return GuardrailDecision.warn(toolName, "idempotent_no_progress_warning", "Tool output is not changing");
            }
        } else {
            state.consecutiveFailures = 0;
            state.recentErrorMessages.clear();
        }

        return GuardrailDecision.allow(toolName);
    }

    @Override
    public boolean isHalted() {
        GuardrailSessionState state = stateFor();
        return state.halted;
    }

    @Override
    public boolean isHalted(UUID sessionId) {
        GuardrailSessionState state = stateFor(sessionId);
        return state != null && state.halted;
    }

    @Override
    public void reset() {
        UUID sessionId = InterruptToken.currentSessionId();
        if (sessionId != null) {
            sessionStates.remove(sessionId);
        } else {
            sessionStates.clear();
        }
    }

    @Override
    public void reset(UUID sessionId) {
        if (sessionId != null) {
            sessionStates.remove(sessionId);
        } else {
            sessionStates.clear();
        }
    }

    // ─── Test-helper accessors (for unit tests that need to inspect state) ───

    /**
     * Returns the history deque for testing. Uses the current thread's session context.
     */
    Deque<ToolCallRecord> getHistory() {
        return stateFor().history;
    }

    /**
     * Returns whether the current thread's session is halted.
     */
    boolean isHaltedFlag() {
        return stateFor().halted;
    }

    /**
     * Returns the consecutive failure count for the current thread's session.
     */
    int getConsecutiveFailures() {
        return stateFor().consecutiveFailures;
    }

    /**
     * Returns the recent tool names for the current thread's session.
     */
    Deque<String> getRecentToolNames() {
        return stateFor().recentToolNames;
    }

    /**
     * Returns the recent error messages for the current thread's session.
     */
    Deque<String> getRecentErrorMessages() {
        return stateFor().recentErrorMessages;
    }

    private long sameToolFailureCount(GuardrailSessionState state, String toolName) {
        return state.recentToolNames.stream().filter(n -> Objects.equals(n, toolName)).count();
    }

    private int idempotentNoProgress(GuardrailSessionState state, String toolName, ToolResult result) {
        int count = 0;
        String current = result != null ? result.content() : null;
        for (ToolCallRecord r : state.history) {
            if (r.failed && Objects.equals(r.toolName, toolName) && Objects.equals(r.output, current)) {
                count++;
            }
        }
        return count;
    }

    private record ToolCallRecord(String toolName, String args, boolean failed, String output) {}
}