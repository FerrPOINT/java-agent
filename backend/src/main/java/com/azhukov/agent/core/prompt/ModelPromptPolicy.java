package com.azhukov.agent.core.prompt;

import java.util.Set;

/**
 * Pure model-name policy used while selecting prompt roles and guidance.
 */
final class ModelPromptPolicy {

    /**
     * Models whose system prompt must use the OpenAI developer role.
     * Hermes (prompt_builder.py:903 + transports/chat_completions.py:526)
     * matches by SUBSTRING, not prefix: "chatgpt-5.6-luna" contains "gpt-5"
     * and therefore receives the developer role. A startsWith check silently
     * sent "system" to strict deployments ("System messages are not allowed").
     */
    private static final Set<String> DEVELOPER_ROLE_MODELS = Set.of("gpt-5", "codex");
    private static final Set<String> OPENAI_FAMILY_PREFIXES = Set.of("gpt", "o1", "o3", "codex", "grok");
    private static final Set<String> EXECUTION_GUIDANCE_PREFIXES = Set.of(
        "gpt", "codex", "grok", "deepseek", "kimi", "qwen", "glm", "minimax", "mimo", "mistral"
    );
    private static final Set<String> GOOGLE_FAMILY_PREFIXES = Set.of("gemini", "gemma");
    private static final Set<String> TOOL_ENFORCEMENT_PREFIXES = Set.of(
        "gpt", "codex", "gemini", "gemma", "grok", "glm", "qwen", "deepseek"
    );

    private ModelPromptPolicy() {
    }

    static boolean usesDeveloperRole(String modelName) {
        // Hermes parity: substring match ("chatgpt-5.6-luna".contains("gpt-5")).
        return hasSubstring(modelName, DEVELOPER_ROLE_MODELS);
    }

    static String detectFamily(String modelName) {
        if (hasPrefix(modelName, OPENAI_FAMILY_PREFIXES)) {
            return "openai";
        }
        if (hasPrefix(modelName, GOOGLE_FAMILY_PREFIXES)) {
            return "google";
        }
        return null;
    }

    static String guidanceFor(String modelName, String openAiGuidance, String googleGuidance) {
        String family = detectFamily(modelName);
        if ("google".equals(family)) {
            return googleGuidance;
        }
        if ("openai".equals(family) || hasPrefix(modelName, EXECUTION_GUIDANCE_PREFIXES)) {
            return openAiGuidance;
        }
        return "";
    }

    static boolean needsToolUseEnforcement(String modelName) {
        return hasPrefix(modelName, TOOL_ENFORCEMENT_PREFIXES);
    }

    private static boolean hasSubstring(String modelName, Set<String> needles) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return needles.stream().anyMatch(lower::contains);
    }

    private static boolean hasPrefix(String modelName, Set<String> prefixes) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return prefixes.stream().anyMatch(lower::startsWith);
    }
}