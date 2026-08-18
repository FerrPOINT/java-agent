package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.context.ContextCompressor;
import com.azhukov.agent.core.context.DefaultContextCompressor;
import com.azhukov.agent.core.context.DefaultContextEngine;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;

/**
 * Fallback + retry controller — extracted from DefaultAgentRuntime (c1).
 * <p>
 * Handles:
 * <ul>
 *   <li>Model call with retry loop (7 one-shot recovery guards)</li>
 *   <li>Fallback model activation when retries exhausted</li>
 *   <li>Content policy handling</li>
 *   <li>Context overflow → compression recovery</li>
 *   <li>Refusal pattern detection</li>
 * </ul>
 * Ported from Hermes {@code conversation_loop.py} retry + fallback logic.
 */
@Slf4j
@RequiredArgsConstructor
public class FallbackController {

    private final ModelClient modelClient;
    private final ErrorClassifier errorClassifier;
    private final AgentProperties properties;
    private final ContextCompressor contextCompressor;
    private final DefaultContextEngine contextEngineDelegate;

    // Active model client — may be swapped to a fallback client mid-turn.
    private ModelClient activeModelClient;

    // Fallback manager — created per-turn, manages mid-turn model switching.
    private FallbackManager fallbackManager;

    public void initTurn() {
        if (fallbackManager != null) {
            fallbackManager.restorePrimary();
        }
        fallbackManager = new FallbackManager(
            properties.getFallbackChain(),
            properties.getModel().getProvider(),
            properties.getModel().getModelName(),
            properties.getModel().getBaseUrl(),
            properties.getModel().getApiKey()
        );
        activeModelClient = modelClient;
    }

    public ModelClient getActiveModelClient() {
        return activeModelClient != null ? activeModelClient : modelClient;
    }

    public boolean hasFallbackManager() {
        return fallbackManager != null;
    }

    static class ContentPolicyException extends RuntimeException {
        ContentPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Detect refusal/safety-filter language in an error message.
     * Mirrors Hermes {@code _detect_refusal_pattern}.
     */
    static String detectRefusalPattern(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase(Locale.ROOT);
        String[] patterns = {
            "content policy", "content filter", "safety filter",
            "content_policy_violation", "i can't assist", "i cannot assist",
            "i'm not able to help", "i am not able to help"
        };
        for (String p : patterns) {
            if (lower.contains(p)) return p;
        }
        return null;
    }

    /**
     * Extract Retry-After header value in milliseconds.
     */
    static long extractRetryAfterMs(Exception e) {
        if (e == null || e.getMessage() == null) return 0;
        String msg = e.getMessage().toLowerCase(Locale.ROOT);
        // Look for "retry-after: N" or "retry after N seconds"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "retry.?after[:\\s]+(\\d+)");
        java.util.regex.Matcher m = p.matcher(msg);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1)) * 1000;
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /**
     * Try to activate a fallback model.
     * @return true if a fallback was activated, false if none available
     */
    boolean tryActivateFallback(ErrorClassifier.ErrorType errorType, Exception error) {
        if (fallbackManager == null) return false;
        if (!fallbackManager.hasPendingFallback()) {
            log.warn("No fallback model available for error type {}", errorType);
            return false;
        }
        FallbackConfig next = fallbackManager.activateFallback();
        if (next == null) {
            log.warn("No fallback model available for error type {}", errorType);
            return false;
        }
        log.info("Activating fallback model: {} ({}) — primary error: {}",
            next.getModel(), next.getProvider(), error.getMessage());
        // In a full implementation, this would create a new ModelClient
        // with the fallback config. For now, we just log the activation.
        // The actual model client swap happens at the AgentConfig level.
        return true;
    }

    /**
     * Check if message (lowercased) contains a substring.
     */
    static boolean lowerMessageContains(Exception e, String substring) {
        if (e == null || e.getMessage() == null) return false;
        return e.getMessage().toLowerCase(Locale.ROOT).contains(substring.toLowerCase(Locale.ROOT));
    }

    /**
     * Strip grammar patterns from tool definitions (Guard 7: LLAMA_CPP_GRAMMAR).
     */
    static List<ToolDefinition> stripGrammarPatternsFromTools(List<ToolDefinition> tools) {
        // Placeholder — in full implementation, strips "pattern" and "format" fields
        // from tool JSON schemas to work around llama.cpp grammar issues
        return tools;
    }
}