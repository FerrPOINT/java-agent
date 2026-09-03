package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;

final class OpenAiRouteSelection {

    private OpenAiRouteSelection() {
    }

    static String routeProviderConflict(AgentProperties.ApiProperties api,
                                        Object requestedModel,
                                        Object requestedProvider) {
        return routeProviderConflict(api, requestedModel, requestedProvider, true);
    }

    static String routeProviderConflict(AgentProperties.ApiProperties api,
                                        Object requestedModel,
                                        Object requestedProvider,
                                        boolean splitProviderPrefix) {
        OpenAiModelRouting.RequestedModel requested =
            OpenAiModelRouting.requestedModelAndProvider(requestedModel, requestedProvider, splitProviderPrefix);
        String requestProvider = requested.provider();
        if (requestProvider == null) {
            return null;
        }
        AgentProperties.ApiProperties.ModelRouteProperties route =
            OpenAiModelRouting.routedRoute(api, requested.model());
        if (route == null) {
            return null;
        }

        String routeProvider = clean(route.getProvider());
        String routeApiKey = clean(route.getApiKey());
        String routeBaseUrl = clean(route.getBaseUrl());
        String routeAlias = requested.model();
        if (routeAlias == null) {
            routeAlias = "requested model";
        }

        if (routeProvider != null && !requestProvider.equals(routeProvider)) {
            return "Model route '" + routeAlias + "' is pinned to provider '" + routeProvider
                + "'. Remove 'provider' or use '" + routeProvider + "'.";
        }
        if (routeProvider == null && (routeApiKey != null || routeBaseUrl != null)) {
            return "Model route '" + routeAlias + "' pins route credentials/base_url. "
                + "Do not combine it with an explicit 'provider'.";
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
