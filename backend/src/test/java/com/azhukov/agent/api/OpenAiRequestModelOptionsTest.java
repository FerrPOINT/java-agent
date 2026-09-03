package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelRequestOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestModelOptionsTest {

    @Test
    void providerPrefixedRouteAliasUsesSplitModelAndPrefixProviderLikeHermes() {
        AgentProperties properties = new AgentProperties();
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/fast-model");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("fast-agent", route);

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            " openrouter::fast-agent ",
            null,
            Map.of(),
            null,
            true);

        assertThat(options.modelName()).isEqualTo("openrouter/fast-model");
        assertThat(options.provider()).isEqualTo("openrouter");
        assertThat(options.baseUrl()).isEqualTo("https://openrouter.example/v1");
        assertThat(options.apiKey()).isEqualTo("sk-route-secret");
    }

    @Test
    void explicitProviderConflictAgainstPrefixedRouteAliasIsRejectedLikeHermes() {
        AgentProperties properties = new AgentProperties();
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/fast-model");
        route.setProvider("openrouter");
        properties.getApi().getModelRoutes().put("fast-agent", route);

        String error = OpenAiRouteSelection.routeProviderConflict(
            properties.getApi(),
            "openrouter::fast-agent",
            "minimax");

        assertThat(error).isEqualTo(
            "Model route 'fast-agent' is pinned to provider 'openrouter'. "
                + "Remove 'provider' or use 'openrouter'.");
    }

    @Test
    void openAiCompatibleSurfacesDoNotSplitProviderPrefixedModelLikeHermes() {
        AgentProperties properties = new AgentProperties();
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/fast-model");
        route.setProvider("openrouter");
        properties.getApi().getModelRoutes().put("fast-agent", route);

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            "openrouter::fast-agent",
            null,
            Map.of(),
            null,
            false,
            false);

        assertThat(options.modelName()).isNull();
        assertThat(options.provider()).isNull();
        assertThat(options.baseUrl()).isNull();
        assertThat(options.apiKey()).isNull();
        assertThat(OpenAiRouteSelection.routeProviderConflict(
            properties.getApi(),
            "openrouter::fast-agent",
            null,
            false)).isNull();
    }

    @Test
    void openAiCompatibleDirectModelRequestsPassPrefixedModelRawLikeHermes() {
        AgentProperties properties = new AgentProperties();
        properties.getApi().setDirectModelRequests(true);

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            "openrouter::MiniMax-M3",
            null,
            Map.of(),
            null,
            false,
            false);

        assertThat(options.modelName()).isEqualTo("openrouter::MiniMax-M3");
        assertThat(options.provider()).isNull();
    }

    @Test
    void providerPrefixedBareModelIsTrimmedOnNativeSurfaces() {
        AgentProperties properties = new AgentProperties();

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            " minimax::MiniMax-M3 ",
            null,
            Map.of(),
            null,
            true);

        assertThat(options.modelName()).isEqualTo("MiniMax-M3");
        assertThat(options.provider()).isEqualTo("minimax");
    }

    @Test
    void blankApiModelNameMapsHermesAliasToConfiguredModelOnNativeSurfaces() {
        AgentProperties properties = new AgentProperties();
        properties.getApi().setModelName(" ");
        properties.getModel().setModelName("real-provider-model");

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            "hermes-agent",
            null,
            Map.of(),
            null,
            true);

        assertThat(options.modelName()).isEqualTo("real-provider-model");
        assertThat(options.provider()).isNull();
    }

    @Test
    void fractionalFastOptionUsesHermesBoolishNumberPolicy() {
        AgentProperties properties = new AgentProperties();

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            "gpt-test",
            Map.of("fast", 0.5d),
            null);

        assertThat(options.fastMode()).isTrue();
    }

    @Test
    void serviceTierIsPreservedSeparatelyFromFastModeLikeHermes() {
        AgentProperties properties = new AgentProperties();

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            "gpt-test",
            Map.of("service_tier", " priority "),
            null);

        assertThat(options.serviceTier()).isEqualTo("priority");
        assertThat(options.fastMode()).isNull();
    }

    @Test
    void fastOptionAlsoRequestsPriorityServiceTierLikeHermes() {
        AgentProperties properties = new AgentProperties();

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            "gpt-test",
            Map.of("fast", true),
            null);

        assertThat(options.fastMode()).isTrue();
        assertThat(options.serviceTier()).isEqualTo("priority");
    }

    @Test
    void explicitNullServiceTierSuppressesFastFallbackLikeHermes() {
        AgentProperties properties = new AgentProperties();
        Map<String, Object> modelOptions = new java.util.LinkedHashMap<>();
        modelOptions.put("service_tier", null);
        modelOptions.put("fast", true);

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            "gpt-test",
            modelOptions,
            null);

        assertThat(options.fastMode()).isTrue();
        assertThat(options.serviceTier()).isNull();
    }

    @Test
    void invalidServiceTierIsDiscardedLikeHermesRuntimeId() {
        AgentProperties properties = new AgentProperties();

        ModelRequestOptions newline = OpenAiRequestModelOptions.from(
            properties,
            "gpt-test",
            Map.of("service_tier", "prio\nrity"),
            null);
        ModelRequestOptions overlong = OpenAiRequestModelOptions.from(
            properties,
            "gpt-test",
            Map.of("service_tier", "x".repeat(33)),
            null);

        assertThat(newline.serviceTier()).isNull();
        assertThat(overlong.serviceTier()).isNull();
    }

    @Test
    void nonStringRequestedModelAndProviderAreIgnoredLikeHermes() {
        AgentProperties properties = new AgentProperties();

        ModelRequestOptions options = OpenAiRequestModelOptions.from(
            properties,
            Map.of("id", "gpt-5"),
            List.of("openrouter"),
            Map.of("reasoning_effort", "high"),
            null,
            true);

        assertThat(options.modelName()).isNull();
        assertThat(options.provider()).isNull();
        assertThat(options.reasoningEffort()).isEqualTo("high");
    }
}
