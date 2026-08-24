package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Hermes parity (agent/thinking_timeout_guidance.py):
 * Detects when a reasoning model's thinking phase hit a transport-level
 * timeout (upstream proxy idle-killed the stream before first content token)
 * and provides user-facing guidance specific to this case.
 *
 * Without this, a transport disconnect on a reasoning model shows a generic
 * "Model call failed" message — the user has no idea the real cause is a
 * proxy idle timeout on the thinking phase, not a context overflow or
 * configuration error (#52310).
 */
@Slf4j
public final class ThinkingTimeoutGuidance {

    private ThinkingTimeoutGuidance() {}

    /** Transport-kill substrings (thinking_timeout_guidance.py:41-49). */
    private static final Set<String> TRANSPORT_KILL_SUBSTRINGS = Set.of(
        "broken pipe",
        "errno 32",
        "remote protocol",
        "connection reset",
        "connection lost",
        "peer closed",
        "server disconnected"
    );

    /**
     * Reasoning model identifiers — models with extended thinking phases
     * that are vulnerable to upstream idle timeouts. Matches Hermes
     * reasoning_timeouts.get_reasoning_stale_timeout_floor allowlist.
     */
    private static final Set<String> REASONING_MODEL_KEYWORDS = Set.of(
        "nemotron", "o1", "o3", "o4",
        "opus-4", "opus 4", "thinking",
        "deepseek-r1", "deepseek r1", "deepseek-reasoner",
        "qwq", "qwen-qwq", "qwen qwq",
        "grok-4", "grok 4", "grok-reasoning"
    );

    /**
     * Detect a thinking-phase timeout on a reasoning model.
     *
     * @param errorType classified error type (must be TIMEOUT)
     * @param modelName the model slug at failure time
     * @param errorMsg  lowercased string representation of the exception
     * @return true if this is a thinking-phase transport kill on a reasoning model
     */
    public static boolean isThinkingTimeout(
            ErrorClassifier.ErrorType errorType,
            String modelName,
            String errorMsg) {
        // Condition 1: classifier says timeout
        if (errorType != ErrorClassifier.ErrorType.TIMEOUT) {
            return false;
        }
        // Condition 2: reasoning model allowlist
        if (!isReasoningModel(modelName)) {
            return false;
        }
        // Condition 3: transport-kill substring
        String lower = errorMsg == null ? "" : errorMsg.toLowerCase();
        return TRANSPORT_KILL_SUBSTRINGS.stream().anyMatch(lower::contains);
    }

    /**
     * Check if a model name matches the reasoning model allowlist.
     */
    public static boolean isReasoningModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return REASONING_MODEL_KEYWORDS.stream().anyMatch(lower::contains);
    }

    /**
     * Build user-facing guidance for a thinking-phase timeout.
     * Mirrors build_thinking_timeout_guidance() from Hermes.
     */
    public static String buildGuidance(String provider, String model) {
        String label = model != null && !model.isBlank() ? model : "this model";
        String providerStr = provider != null && !provider.isBlank() ? provider : "your-provider";
        String modelStr = model != null && !model.isBlank() ? model : "your-model";
        return "\n\nThe model's thinking phase exceeded the upstream proxy's "
            + "idle timeout before the first content token arrived. This is a "
            + "known issue with reasoning models (like " + label + ") behind cloud "
            + "gateways (NVIDIA NIM, OpenAI, Anthropic, DeepSeek). Workarounds "
            + "in priority order:\n"
            + "1. Set `providers." + providerStr + ".models." + modelStr + ".stale_timeout_seconds: 900` "
            + "in your config to extend the per-call timeout. "
            + "(The built-in floor is 600s for known reasoning models — "
            + "if you still see this after raising, the upstream cap is even "
            + "shorter.)\n"
            + "2. Lower `reasoning_budget` or set `reasoning_effort: medium` on this "
            + "model if the provider supports it.\n"
            + "3. Use a smaller / faster reasoning model if the task doesn't "
            + "require deep thinking.";
    }
}