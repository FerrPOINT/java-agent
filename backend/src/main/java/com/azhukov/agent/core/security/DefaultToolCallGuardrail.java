package com.azhukov.agent.core.security;

import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.model.ToolResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.concurrent.ConcurrentHashMap;

/** One execution policy; mutable observations belong to a session and its current turn. */
public class DefaultToolCallGuardrail implements ToolCallGuardrail {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    // ── Hermes parity: IDEMPOTENT_TOOL_NAMES (tool_guardrails.py) ──────
    private static final Set<String> IDEMPOTENT_TOOLS = Set.of(
        "read_file", "search_files", "web_search", "web_extract",
        "session_search", "browser_snapshot", "browser_console",
        "browser_get_images", "skill_view", "skills_list",
        "vision_analyze"
    );

    private static final int IDENTICAL_RESULT_STUB_MIN_CHARS = 512;
    private final GuardrailConfig config;

    private final Map<UUID, GuardrailSessionState> sessionStates = new ConcurrentHashMap<>();

    private static class GuardrailSessionState {
        boolean halted;
        String haltMessage;
        final Map<String, Integer> repeatedFailureCounts = new HashMap<>();
        final Map<String, Integer> sameToolFailureCounts = new HashMap<>();
        final Map<String, String> idempotentResultHashes = new HashMap<>();
        final Map<String, Integer> idempotentRepeatCounts = new HashMap<>();
        final Map<String, Integer> turnToolCounts = new HashMap<>();
        final Map<String, String> stubStreakResultHashes = new HashMap<>();
        final Map<String, Integer> stubStreakCounts = new HashMap<>();
    }

    private GuardrailSessionState stateFor() {
        UUID sessionId = java.util.Objects.requireNonNull(InterruptToken.currentSessionId(),
                "Tool guardrail requires a session scope");
        return sessionStates.computeIfAbsent(sessionId, k -> new GuardrailSessionState());
    }

    /**
     * Returns the GuardrailSessionState for a specific session, or null if absent.
     */
    private GuardrailSessionState stateFor(UUID sessionId) {
        return sessionStates.get(java.util.Objects.requireNonNull(sessionId, "Session scope is required"));
    }

    /**
     * Removes the guardrail state for the given session.
     * Called when a session ends to prevent memory leaks.
     */
    @Override
    public void removeSession(UUID sessionId) {
        sessionStates.remove(java.util.Objects.requireNonNull(sessionId, "Session scope is required"));
    }

    public DefaultToolCallGuardrail(GuardrailConfig config) {
        this.config = java.util.Objects.requireNonNull(config, "Guardrail configuration is required");
    }

    @Override
    public GuardrailDecision beforeCall(String toolName, String args) {
        GuardrailSessionState state = stateFor();
        synchronized (state) {
            if (state.halted) {
                return GuardrailDecision.halt(toolName, "guardrail_halted", state.haltMessage);
            }
            if (toolName == null || toolName.isBlank()) {
                return GuardrailDecision.block(toolName, "unknown_tool", "Tool name is missing");
            }
            String cap = checkRunawayCap(state, toolName, args);
            return cap == null ? GuardrailDecision.allow(toolName)
                : GuardrailDecision.block(toolName, "loop_" + toolName + "_cap", cap);
        }
    }

    @Override
    public GuardrailDecision afterCall(String toolName, String args, ToolResult result, boolean failed) {
        GuardrailSessionState state = stateFor();
        synchronized (state) {
            String signature = toolName + ':' + hashArgs(args);
            if (!failed) {
                state.repeatedFailureCounts.clear();
                state.sameToolFailureCounts.clear();
                if (IDEMPOTENT_TOOLS.contains(toolName) && result != null && result.content() != null) {
                    String hash = hashResult(result.content());
                    String previous = state.idempotentResultHashes.put(signature, hash);
                    int count = hash.equals(previous) ? state.idempotentRepeatCounts.getOrDefault(signature, 1) + 1 : 1;
                    state.idempotentRepeatCounts.put(signature, count);
                    if (config.isWarningsEnabled() && count >= config.getWarnAfterIdempotentNoProgress()) {
                        return GuardrailDecision.warn(toolName, "idempotent_no_progress", toolName
                            + " returned the same result " + count + " times. Use the result already provided or change the query.");
                    }
                }
                return GuardrailDecision.allow(toolName);
            }
            state.idempotentResultHashes.remove(signature);
            state.idempotentRepeatCounts.remove(signature);
            int count = state.repeatedFailureCounts.merge(signature + ':' + hashResult(failureFingerprint(result)), 1, Integer::sum);
            int toolCount = state.sameToolFailureCounts.merge(toolName, 1, Integer::sum);
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
            if (config.isWarningsEnabled() && toolCount >= config.getWarnAfterSameToolFailure()) {
                return GuardrailDecision.warn(toolName, "repeated_tool_failure_warning", "Tool '" + toolName
                    + "' has failed " + toolCount + " times this turn. Diagnose the error before retrying.");
            }
            return GuardrailDecision.allow(toolName);
        }
    }

    @Override
    public boolean isHalted(UUID sessionId) {
        GuardrailSessionState state = stateFor(sessionId);
        if (state == null) return false;
        synchronized (state) { return state.halted; }
    }

    @Override
    public String haltMessage(UUID sessionId) {
        GuardrailSessionState state = stateFor(sessionId);
        if (state == null) throw new IllegalStateException("Session has no halted execution");
        synchronized (state) {
            if (!state.halted) throw new IllegalStateException("Session has no halted execution");
            return state.haltMessage;
        }
    }

    @Override
    public void reset(UUID sessionId) {
        sessionStates.remove(java.util.Objects.requireNonNull(sessionId, "Session scope is required"));
    }

    private static String failureFingerprint(ToolResult result) {
        String value = result != null && result.error() != null && !result.error().isBlank()
            ? result.error() : result != null ? result.content() : "unknown";
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    @Override
    public String resultReferenceStub(String toolName, String arguments, String result, boolean failed) {
        GuardrailSessionState state = stateFor();
        synchronized (state) {
            if (failed || result == null || result.length() < IDENTICAL_RESULT_STUB_MIN_CHARS) {
                // Reset streak tracking on failure/short result for this signature
                String sig = toolName + ":" + hashArgs(arguments);
                state.stubStreakResultHashes.remove(sig);
                state.stubStreakCounts.remove(sig);
                return null;
            }
            String signature = toolName + ":" + hashArgs(arguments);
            String resultHash = hashResult(result);
            String prevHash = state.stubStreakResultHashes.put(signature, resultHash);
            if (prevHash == null || !prevHash.equals(resultHash)) {
                state.stubStreakCounts.put(signature, 1);
                return null; // first occurrence (or changed) — keep full payload
            }
            int streak = state.stubStreakCounts.merge(signature, 1, Integer::sum);
            return "[Duplicate result — identical to the previous " + toolName
                + " result in this turn (call " + streak + " of the streak). "
                + "Refer to the full payload above instead of repeating the call.]";
        }
    }

    /**
     * Check per-turn runaway caps for web_search and delegate_task.
     * Hermes parity: LoopCapConfig — hard ceiling regardless of hard_stop.
     */
    private String checkRunawayCap(GuardrailSessionState state, String toolName, String arguments) {
        if ("web_search".equals(toolName)) {
            int count = state.turnToolCounts.getOrDefault("web_search", 0);
            if (config.getMaxWebSearches() > 0 && count >= config.getMaxWebSearches()) {
                return String.format(
                    "Blocked web_search: this turn has already made %d " +
                    "web searches, the per-turn limit. Work with the results " +
                    "you already have and give the user your answer.",
                    count
                );
            }
            state.turnToolCounts.merge("web_search", 1, Integer::sum);
        }

        if ("delegate_task".equals(toolName)) {
            int count = state.turnToolCounts.getOrDefault("delegate_task", 0);
            int spawnCount = subagentSpawnCount(arguments);
            if (spawnCount == 0) {
                return null;
            }
            if (config.getMaxSubagents() > 0 && spawnCount > config.getMaxSubagents() - count) {
                return String.format(
                    "Blocked delegate_task: this turn has already spawned " +
                    "%d subagents (limit %d). Finish the work with the results " +
                    "you have and answer the user.",
                    count, config.getMaxSubagents()
                );
            }
            state.turnToolCounts.merge("delegate_task", spawnCount, Integer::sum);
        }

        return null;
    }

    private int subagentSpawnCount(String arguments) {
        try {
            JsonNode root = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            if (root != null && root.isObject()) {
                String action = root.hasNonNull("action")
                    ? root.get("action").asText("").trim().toLowerCase(Locale.ROOT)
                    : "";
                if ("list".equals(action) || "steer".equals(action) || "stop".equals(action)) {
                    return 0;
                }
                JsonNode tasks = root.get("tasks");
                if (tasks != null && tasks.isArray() && !tasks.isEmpty()) {
                    return tasks.size();
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Invalid JSON is handled by the call validator; count it as a single
            // attempted spawn if execution reaches this runtime guardrail.
        }
        return 1;
    }

    private static String hashArgs(String arguments) {
        String source = arguments == null ? "" : arguments;
        try {
            Object parsed = MAPPER.readValue(source.isBlank() ? "{}" : source, Object.class);
            source = CANONICAL_MAPPER.writeValueAsString(parsed);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Invalid arguments retain their exact identity until the tool rejects them.
        }
        return hashResult(source);
    }

    private static String hashResult(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
