package com.azhukov.agent.core.client;

/**
 * Runtime model request options derived from session CLI state or chat request.
 *
 * @param modelName per-request model override (from /model command or API);
 *                  null = use the configured default model
 */
public record ModelRequestOptions(
    String modelName,
    String reasoningEffort,
    Boolean fastMode,
    Boolean voiceMode,
    String personality,
    String subgoal,
    Integer maxCompletionTokens
) {
    public static ModelRequestOptions empty() {
        return new ModelRequestOptions(null, null, null, null, null, null, null);
    }
}
