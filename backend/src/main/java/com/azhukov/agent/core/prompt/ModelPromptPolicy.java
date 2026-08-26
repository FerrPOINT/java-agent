package com.azhukov.agent.core.prompt;

import java.util.Set;

/**
 * Pure model-name policy used while selecting prompt roles and guidance.
 */
final class ModelPromptPolicy {

    /** Models whose system prompt must use the OpenAI developer role. */
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
        return hasPrefix(modelName, DEVELOPER_ROLE_MODELS);
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

    private static boolean hasPrefix(String modelName, Set<String> prefixes) {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return prefixes.stream().anyMatch(lower::startsWith);
    }
}