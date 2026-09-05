package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Session;

import java.util.HashMap;
import java.util.Map;

final class RuntimeModelOptionsResolver {

    private RuntimeModelOptionsResolver() {
    }

    static ModelRequestOptions resolve(AgentProperties properties, ChatRequest request, Session session) {
        return resolve(properties, request, session, null);
    }

    static ModelRequestOptions resolve(AgentProperties properties,
                                       ChatRequest request,
                                       Session session,
                                       RuntimeConfigService runtimeConfigService) {
        Boolean fastMode = request.fastMode();
        String reasoningEffort = request.reasoningEffort();
        Map<String, String> metadata = session != null && session.metadata() != null ? session.metadata() : Map.of();
        if (fastMode == null) {
            fastMode = Boolean.parseBoolean(metadata.getOrDefault("fastMode", "false"));
        }
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            reasoningEffort = metadata.get("reasoningEffort");
        }
        Integer maxTokens = request.maxCompletionTokens();
        if (maxTokens == null || maxTokens <= 0) {
            try {
                maxTokens = Integer.parseInt(metadata.getOrDefault("maxTokens", "0"));
            } catch (NumberFormatException e) {
                maxTokens = 0;
            }
        }

        RuntimeSelection selection = requestSelection(request);
        if (selection.modelName() == null) {
            RuntimeSelection sessionSelection = sessionSelection(properties, session, hasRuntimeSelection(runtimeConfigService));
            if (sessionSelection.modelName() != null) {
                selection = sessionSelection;
            } else {
                selection = runtimeSelection(runtimeConfigService);
            }
        }

        return new ModelRequestOptions(
            selection.modelName(),
            reasoningEffort,
            fastMode,
            request.voiceMode(),
            request.personality(),
            request.subgoal(),
            maxTokens,
            selection.provider(),
            selection.baseUrl(),
            selection.apiKey(),
            clean(request.serviceTier()),
            request.yoloMode(),
            request.verboseMode());
    }

    static Session applyEffectiveRuntime(Session session,
                                         AgentProperties properties,
                                         ModelRequestOptions options) {
        if (session == null) {
            return null;
        }
        String model = clean(options != null ? options.modelName() : null);
        String provider = clean(options != null ? options.provider() : null);
        boolean storedAdvertisedAlias = isAdvertisedApiAlias(properties, session.modelName());
        if (model == null && storedAdvertisedAlias) {
            model = configuredModel(properties);
        }
        String effectiveModel = model != null ? model : storedAdvertisedAlias ? "" : session.modelName();
        String effectiveProvider = provider != null ? provider : session.modelProvider();
        Map<String, String> metadata = new HashMap<>(session.metadata() != null ? session.metadata() : Map.of());
        if (model != null) {
            metadata.put("modelOverride", model);
        } else {
            metadata.remove("modelOverride");
        }
        return new Session(
            session.id(),
            session.userId(),
            session.title(),
            effectiveProvider != null ? effectiveProvider : "",
            effectiveModel != null ? effectiveModel : "",
            session.systemPrompt(),
            Map.copyOf(metadata),
            session.subgoal());
    }

    static String modelUsed(AgentProperties properties,
                            RuntimeConfigService runtimeConfigService,
                            Session session,
                            String unknownModel) {
        String requestOverride = session != null ? clean(session.getMetadata("modelOverride")) : null;
        if (requestOverride != null) {
            return requestOverride;
        }
        String storedModel = storedSessionModel(properties, session, hasRuntimeSelection(runtimeConfigService));
        if (storedModel != null) {
            return storedModel;
        }
        String override = runtimeConfigService != null ? clean(runtimeConfigService.getModelOverride()) : null;
        if (override != null) {
            return override;
        }
        String configured = configuredModel(properties);
        if (configured != null) {
            return configured;
        }
        return unknownModel;
    }

    private static RuntimeSelection requestSelection(ChatRequest request) {
        return new RuntimeSelection(
            clean(request.model()),
            clean(request.provider()),
            clean(request.baseUrl()),
            clean(request.apiKey()));
    }

    private static RuntimeSelection sessionSelection(AgentProperties properties,
                                                    Session session,
                                                    boolean runtimeSelectionActive) {
        String storedModel = storedSessionModel(properties, session, runtimeSelectionActive);
        if (storedModel == null) {
            return RuntimeSelection.empty();
        }
        AgentProperties.ApiProperties.ModelRouteProperties route =
            routedRoute(apiProperties(properties), storedModel);
        if (route != null) {
            return new RuntimeSelection(
                clean(route.getModel()),
                clean(route.getProvider()),
                clean(route.getBaseUrl()),
                clean(route.getApiKey()));
        }
        return new RuntimeSelection(
            storedModel,
            clean(session != null ? session.modelProvider() : null),
            null,
            null);
    }

    private static RuntimeSelection runtimeSelection(RuntimeConfigService runtimeConfigService) {
        RuntimeConfigService.RuntimeModelSelection selection =
            runtimeConfigService != null ? runtimeConfigService.getModelSelection() : null;
        if (selection == null || clean(selection.model()) == null) {
            return RuntimeSelection.empty();
        }
        return new RuntimeSelection(
            clean(selection.model()),
            clean(selection.provider()),
            clean(selection.baseUrl()),
            clean(selection.apiKey()));
    }

    private static boolean hasRuntimeSelection(RuntimeConfigService runtimeConfigService) {
        RuntimeConfigService.RuntimeModelSelection selection =
            runtimeConfigService != null ? runtimeConfigService.getModelSelection() : null;
        return selection != null && clean(selection.model()) != null;
    }

    private static String storedSessionModel(AgentProperties properties,
                                             Session session,
                                             boolean runtimeSelectionActive) {
        String stored = clean(session != null ? session.modelName() : null);
        if (stored == null || isAdvertisedApiAlias(properties, stored)) {
            return null;
        }
        if (runtimeSelectionActive && stored.equals(configuredModel(properties))) {
            return null;
        }
        return stored;
    }

    private static AgentProperties.ApiProperties.ModelRouteProperties routedRoute(AgentProperties.ApiProperties api,
                                                                                  String requestedModel) {
        String requested = clean(requestedModel);
        if (api == null || requested == null || api.getModelRoutes() == null) {
            return null;
        }
        AgentProperties.ApiProperties.ModelRouteProperties route = api.getModelRoutes().get(requested);
        if (route == null || clean(route.getModel()) == null) {
            return null;
        }
        return route;
    }

    private static boolean isAdvertisedApiAlias(AgentProperties properties, String value) {
        String advertised = clean(apiProperties(properties) != null ? apiProperties(properties).getModelName() : null);
        if (advertised == null || !advertised.equals(value)) {
            return false;
        }
        String configured = configuredModel(properties);
        return configured == null || !advertised.equals(configured);
    }

    private static AgentProperties.ApiProperties apiProperties(AgentProperties properties) {
        return properties != null ? properties.getApi() : null;
    }

    private static String configuredModel(AgentProperties properties) {
        if (properties == null || properties.getModel() == null) {
            return null;
        }
        return clean(properties.getModel().getModelName());
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record RuntimeSelection(
        String modelName,
        String provider,
        String baseUrl,
        String apiKey
    ) {
        private static RuntimeSelection empty() {
            return new RuntimeSelection(null, null, null, null);
        }

        private RuntimeSelection withMissingTransportFrom(RuntimeSelection fallback) {
            return new RuntimeSelection(
                fallback.modelName(),
                provider != null ? provider : fallback.provider(),
                baseUrl != null ? baseUrl : fallback.baseUrl(),
                apiKey != null ? apiKey : fallback.apiKey());
        }
    }
}
