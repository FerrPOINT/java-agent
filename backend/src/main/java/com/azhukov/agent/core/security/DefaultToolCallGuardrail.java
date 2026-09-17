package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.state.TurnState;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
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
        String haltMessage;
        final Map<String, Integer> repeatedFailureCounts = new HashMap<>();
        final Deque<String> recentErrorMessages = new ArrayDeque<>();

        void clear() {
            history.clear();
            halted = false;
            haltMessage = null;
            repeatedFailureCounts.clear();
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
        synchronized (state) {
            String fingerprint = failureFingerprint(result);
            state.history.addLast(new ToolCallRecord(toolName, args, failed,
                result != null ? result.content() : null, fingerprint));
            if (state.history.size() > 20) {
                state.history.removeFirst();
            }
            if (!failed) {
                // A successful invocation proves the prior failure sequence did
                // not require an automatic stop; a later retry starts fresh.
                state.repeatedFailureCounts.clear();
                state.recentErrorMessages.clear();
                return GuardrailDecision.allow(toolName);
            }

            String signature = toolName + '\u0001' + args + '\u0001' + fingerprint;
            int count = state.repeatedFailureCounts.merge(signature, 1, Integer::sum);
            state.recentErrorMessages.addLast(fingerprint);
            if (state.recentErrorMessages.size() > 10) state.recentErrorMessages.removeFirst();

            String message = "Tool '" + toolName + "' repeatedly failed for the same reason. "
                + "Review its diagnostic, repair the dependency or change approach before retrying.";
            if (config.isHardStopEnabled() && count >= config.getHardStopAfterExactFailure()) {
                state.halted = true;
                state.haltMessage = message;
                return GuardrailDecision.halt(toolName, "repeated_identical_failure", message);
            }
            if (config.isWarningsEnabled() && count == config.getWarnAfterExactFailure()) {
                return GuardrailDecision.warn(toolName, "repeated_identical_failure_warning", message);
            }
            return GuardrailDecision.allow(toolName);
        }
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
    public String haltMessage(UUID sessionId) {
        GuardrailSessionState state = stateFor(sessionId);
        return state != null && state.haltMessage != null
            ? state.haltMessage : ToolCallGuardrail.super.haltMessage(sessionId);
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
        return stateFor().repeatedFailureCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Returns the recent tool names for the current thread's session.
     */
    Deque<String> getRecentToolNames() {
        return new ArrayDeque<>();
    }

    /**
     * Returns the recent error messages for the current thread's session.
     */
    Deque<String> getRecentErrorMessages() {
        return stateFor().recentErrorMessages;
    }

    private static String failureFingerprint(ToolResult result) {
        String value = result != null && result.error() != null && !result.error().isBlank()
            ? result.error() : result != null ? result.content() : "unknown";
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private record ToolCallRecord(String toolName, String args, boolean failed, String output,
                                  String failureFingerprint) {}
}