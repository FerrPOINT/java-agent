package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelRequestOptions;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class OpenAiRequestModelOptions {

    private OpenAiRequestModelOptions() {
    }

    static ModelRequestOptions from(AgentProperties properties,
                                    Object requestedModel,
                                    Object modelOptions,
                                    Integer topLevelMaxTokens) {
        return from(properties, requestedModel, null, modelOptions, topLevelMaxTokens, false);
    }

    static ModelRequestOptions from(AgentProperties properties,
                                    Object requestedModel,
                                    Object requestedProvider,
                                    Object modelOptions,
                                    Integer topLevelMaxTokens) {
        return from(properties, requestedModel, requestedProvider, modelOptions, topLevelMaxTokens, false);
    }

    static ModelRequestOptions from(AgentProperties properties,
                                    Object requestedModel,
                                    Object modelOptions,
                                    Integer topLevelMaxTokens,
                                    boolean allowBareModel) {
        return from(properties, requestedModel, null, modelOptions, topLevelMaxTokens, allowBareModel);
    }

    static ModelRequestOptions from(AgentProperties properties,
                                    Object requestedModel,
                                    Object requestedProvider,
                                    Object modelOptions,
                                    Integer topLevelMaxTokens,
                                    boolean allowBareModel) {
        return from(properties, requestedModel, requestedProvider, modelOptions, topLevelMaxTokens, allowBareModel, true);
    }

    static ModelRequestOptions from(AgentProperties properties,
                                    Object requestedModel,
                                    Object requestedProvider,
                                    Object modelOptions,
                                    Integer topLevelMaxTokens,
                                    boolean allowBareModel,
                                    boolean splitProviderPrefix) {
        Map<String, Object> options = modelOptionsMap(modelOptions);
        OpenAiModelRouting.RequestedModel requested =
            OpenAiModelRouting.requestedModelAndProvider(requestedModel, requestedProvider, splitProviderPrefix);
        AgentProperties.ApiProperties.ModelRouteProperties route =
            OpenAiModelRouting.routedRoute(properties.getApi(), requested.model());
        return new ModelRequestOptions(
            runtimeModelName(properties, requested.model(), requested.provider(), allowBareModel),
            reasoningEffort(options),
            fastMode(options),
            booleanOption(firstOption(options, "voice", "voice_mode", "voiceMode")),
            stringOption(options, "personality"),
            stringOption(options, "subgoal", "sub_goal", "subGoal"),
            topLevelMaxTokens != null
                ? topLevelMaxTokens
                : positiveIntOption(options, "max_completion_tokens", "maxCompletionTokens", "max_tokens", "maxTokens"),
            cleanRouteValue(OpenAiModelRouting.hasText(requested.provider())
                ? requested.provider()
                : route != null ? route.getProvider() : null),
            cleanRouteValue(route != null ? route.getBaseUrl() : null),
            cleanRouteValue(route != null ? route.getApiKey() : null),
            serviceTier(options)
        );
    }

    private static Map<String, Object> modelOptionsMap(Object modelOptions) {
        if (!(modelOptions instanceof Map<?, ?> rawOptions) || rawOptions.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> options = new LinkedHashMap<>();
        rawOptions.forEach((key, value) -> options.put(String.valueOf(key), value));
        return options;
    }

    private static String runtimeModelName(AgentProperties properties,
                                           String requestedModel,
                                           String requestedProvider,
                                           boolean allowBareModel) {
        if (!allowBareModel) {
            String routedModel = OpenAiModelRouting.routedModel(properties.getApi(), requestedModel);
            if (OpenAiModelRouting.hasText(routedModel)) {
                return routedModel;
            }
            if (OpenAiModelRouting.hasText(requestedProvider)
                && OpenAiModelRouting.hasText(requestedModel)) {
                AgentProperties.ApiProperties api = properties.getApi();
                String advertisedModel = api != null ? api.getModelName() : null;
                if (OpenAiModelRouting.hasText(advertisedModel)
                    && requestedModel.equals(advertisedModel)
                    && OpenAiModelRouting.hasText(OpenAiModelRouting.configuredModel(properties))) {
                    return OpenAiModelRouting.configuredModel(properties);
                }
                return requestedModel.trim();
            }
            return OpenAiModelRouting.runtimeModelName(properties, requestedModel);
        }
        AgentProperties.ApiProperties api = properties.getApi();
        String routedModel = OpenAiModelRouting.routedModel(api, requestedModel);
        if (OpenAiModelRouting.hasText(routedModel)) {
            return routedModel;
        }
        String advertisedModel = OpenAiModelRouting.advertisedModel(properties);
        String configuredModel = OpenAiModelRouting.configuredModel(properties);
        if (OpenAiModelRouting.hasText(requestedModel)
            && OpenAiModelRouting.hasText(advertisedModel)
            && requestedModel.equals(advertisedModel)
            && OpenAiModelRouting.hasText(configuredModel)) {
            return configuredModel;
        }
        return requestedModel;
    }

    static String reasoningEffort(Map<String, Object> modelOptions) {
        Object raw = firstOption(modelOptions, "reasoning_effort", "reasoningEffort");
        Object reasoning = firstOption(modelOptions, "reasoning");
        if (reasoning instanceof Map<?, ?> reasoningMap) {
            Object enabled = reasoningMap.get("enabled");
            if (enabled instanceof Boolean enabledBool && !enabledBool) {
                return "none";
            }
            if (raw == null) {
                raw = reasoningMap.get("effort");
            }
        }
        String value = stringValue(raw).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra" -> value;
            default -> null;
        };
    }

    static Boolean fastMode(Map<String, Object> modelOptions) {
        Object fast = firstOption(modelOptions, "fast", "fast_mode", "fastMode");
        return booleanOption(fast);
    }

    static String serviceTier(Map<String, Object> modelOptions) {
        if (modelOptions == null || modelOptions.isEmpty()) {
            return null;
        }
        if (modelOptions.containsKey("service_tier") || modelOptions.containsKey("serviceTier")) {
            return cleanRuntimeId(firstOption(modelOptions, "service_tier", "serviceTier"), 32);
        }
        return Boolean.TRUE.equals(booleanOption(firstOption(modelOptions, "fast")))
            ? "priority"
            : null;
    }

    static String stringOption(Map<String, Object> options, String... keys) {
        Object value = firstOption(options, keys);
        if (value instanceof String string && !string.isBlank()) {
            return string.trim();
        }
        return null;
    }

    static Boolean booleanOption(Object value) {
        return OpenAiRequestBooleans.coerceOptional(value);
    }

    static Integer positiveIntOption(Map<String, Object> options, String... keys) {
        Object value = firstOption(options, keys);
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                int parsed = Integer.parseInt(string.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Object firstOption(Map<String, Object> options, String... keys) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (options.containsKey(key)) {
                return options.get(key);
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String cleanRouteValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String cleanRuntimeId(Object value, int maxLen) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || text.length() > maxLen) {
            return null;
        }
        return text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\0') >= 0
            ? null
            : text;
    }
}
