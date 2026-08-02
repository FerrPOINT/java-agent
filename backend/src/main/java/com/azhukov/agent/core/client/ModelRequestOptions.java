package com.azhukov.agent.core.client;

/**
 * Runtime model request options derived from session CLI state or chat request.
 */
public record ModelRequestOptions(
    String reasoningEffort,
    Boolean fastMode,
    Boolean voiceMode,
    String personality,
    String subgoal,
    Integer maxCompletionTokens
) {
    public static ModelRequestOptions empty() {
        return new ModelRequestOptions(null, null, null, null, null, null);
    }
}
