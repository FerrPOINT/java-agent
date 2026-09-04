package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;

import java.util.regex.Pattern;

final class OpenAiModelRouting {

    private static final String DEFAULT_ADVERTISED_MODEL = "java-agent";
    private static final Pattern PROVIDER_PREFIX = Pattern.compile("^[a-zA-Z0-9_.-]{2,64}$");

    private OpenAiModelRouting() {
    }

    static String advertisedModel(AgentProperties properties) {
        AgentProperties.ApiProperties api = properties.getApi();
        if (api != null && hasText(api.getModelName())) {
            return api.getModelName().trim();
        }
        return DEFAULT_ADVERTISED_MODEL;
    }

    static String runtimeModelName(AgentProperties properties, Object requestedModel) {
        AgentProperties.ApiProperties api = properties.getApi();
        String requested = clean(requestedModel);
        String routedModel = routedModel(api, requested);
        if (hasText(routedModel)) {
            return routedModel;
        }

        String advertisedModel = advertisedModel(properties);
        if (hasText(requested) && requested.equals(advertisedModel)) {
            String configuredModel = configuredModel(properties);
            return hasText(configuredModel) ? configuredModel : null;
        }
        if (api != null && api.isDirectModelRequests() && hasText(requested)) {
            return requested;
        }
        return null;
    }

    static String routedModel(AgentProperties.ApiProperties api, Object requestedModel) {
        AgentProperties.ApiProperties.ModelRouteProperties route = routedRoute(api, requestedModel);
        return route != null ? route.getModel().trim() : null;
    }

    static AgentProperties.ApiProperties.ModelRouteProperties routedRoute(AgentProperties.ApiProperties api,
                                                                          Object requestedModel) {
        String requested = clean(requestedModel);
        if (api == null || !hasText(requested) || api.getModelRoutes() == null) {
            return null;
        }
        AgentProperties.ApiProperties.ModelRouteProperties route = api.getModelRoutes().get(requested);
        if (route == null || !hasText(route.getModel())) {
            return null;
        }
        return route;
    }

    static String configuredModel(AgentProperties properties) {
        if (properties.getModel() != null && hasText(properties.getModel().getModelName())) {
            return properties.getModel().getModelName().trim();
        }
        return null;
    }

    static RequestedModel requestedModelAndProvider(Object rawModel, Object rawProvider) {
        return requestedModelAndProvider(rawModel, rawProvider, true);
    }

    static RequestedModel requestedModelAndProvider(Object rawModel,
                                                    Object rawProvider,
                                                    boolean splitProviderPrefix) {
        String provider = clean(rawProvider);
        String model = clean(rawModel);
        if (splitProviderPrefix && hasText(model) && model.contains("::")) {
            String[] parts = model.split("::", 2);
            String prefix = parts[0].trim();
            String splitModel = parts.length > 1 ? parts[1].trim() : "";
            if (PROVIDER_PREFIX.matcher(prefix).matches() && hasText(splitModel)) {
                if (!hasText(provider)) {
                    provider = prefix;
                }
                model = splitModel;
            }
        }
        return new RequestedModel(hasText(provider) ? provider : null, hasText(model) ? model : null);
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String clean(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    record RequestedModel(String provider, String model) {}
}
