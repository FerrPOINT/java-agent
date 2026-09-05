package com.azhukov.agent.core.client;

/**
 * Runtime model request options derived from session CLI state or chat request.
 *
 * @param modelName per-request model override (from /model command or API);
 *                  null = use the configured default model
 * @param provider per-request provider hint for OpenAI-compatible model_routes
 * @param baseUrl per-request base URL override for OpenAI-compatible model_routes
 * @param apiKey per-request API key override for OpenAI-compatible model_routes
 * @param serviceTier OpenAI-compatible service_tier request override
 */
public record ModelRequestOptions(
    String modelName,
    String reasoningEffort,
    Boolean fastMode,
    Boolean voiceMode,
    String personality,
    String subgoal,
    Integer maxCompletionTokens,
    String provider,
    String baseUrl,
    String apiKey,
    String serviceTier,
    Boolean yoloMode,
    Boolean verboseMode
) {
    public ModelRequestOptions(String modelName,
                               String reasoningEffort,
                               Boolean fastMode,
                               Boolean voiceMode,
                               String personality,
                               String subgoal,
                               Integer maxCompletionTokens) {
        this(modelName, reasoningEffort, fastMode, voiceMode, personality, subgoal,
            maxCompletionTokens, null, null, null, null, null, null);
    }

    public ModelRequestOptions(String modelName,
                               String reasoningEffort,
                               Boolean fastMode,
                               Boolean voiceMode,
                               String personality,
                               String subgoal,
                               Integer maxCompletionTokens,
                               String provider,
                               String baseUrl,
                               String apiKey) {
        this(modelName, reasoningEffort, fastMode, voiceMode, personality, subgoal,
            maxCompletionTokens, provider, baseUrl, apiKey, null, null, null);
    }

    public ModelRequestOptions(String modelName,
                               String reasoningEffort,
                               Boolean fastMode,
                               Boolean voiceMode,
                               String personality,
                               String subgoal,
                               Integer maxCompletionTokens,
                               String provider,
                               String baseUrl,
                               String apiKey,
                               String serviceTier) {
        this(modelName, reasoningEffort, fastMode, voiceMode, personality, subgoal,
            maxCompletionTokens, provider, baseUrl, apiKey, serviceTier, null, null);
    }

    public static ModelRequestOptions empty() {
        return new ModelRequestOptions(null, null, null, null, null, null, null);
    }

    public boolean hasTransportOverride() {
        return hasText(baseUrl) || hasText(apiKey);
    }

    @Override
    public String toString() {
        return "ModelRequestOptions[" +
            "modelName=" + modelName +
            ", reasoningEffort=" + reasoningEffort +
            ", fastMode=" + fastMode +
            ", voiceMode=" + voiceMode +
            ", personality=" + personality +
            ", subgoal=" + subgoal +
            ", maxCompletionTokens=" + maxCompletionTokens +
            ", provider=" + provider +
            ", baseUrl=" + baseUrl +
            ", apiKey=" + (hasText(apiKey) ? "<redacted>" : apiKey) +
            ", serviceTier=" + serviceTier +
            ", yoloMode=" + yoloMode +
            ", verboseMode=" + verboseMode +
            ']';
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
