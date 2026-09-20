package com.azhukov.agent.core.tool;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity gate (tools/registry.py check_fn): provider-backed tools
 * (text_to_speech, image_generate) must be hidden from the model's tool
 * schema when their provider is not resolvable — registered-and-failing is
 * the defect this locks out (model sees the tool, calls it, gets a config
 * error instead of never seeing it).
 */
class ProviderToolGateRegistryTest {

    private static SpringToolRegistry registryWithBeans(AgentProperties properties, Object... handlers) {
        var context = Mockito.mock(org.springframework.context.ApplicationContext.class);
        var beans = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < handlers.length; i++) {
            beans.put("tool" + i, handlers[i]);
        }
        Mockito.when(context.getBeansWithAnnotation(com.azhukov.agent.tools.AgentTool.class)).thenReturn(beans);
        var gateway = new ManagedToolGate(properties);
        var registry = new SpringToolRegistry(context, properties, new ObjectMapper(), gateway);
        registry.registerBeans();
        return registry;
    }

    private static com.azhukov.agent.tools.tts.TtsTool ttsTool(java.util.List<com.azhukov.agent.service.tts.TtsProvider> providers) {
        return new com.azhukov.agent.tools.tts.TtsTool(providers, new AgentProperties());
    }

    @Test
    void ttsToolHiddenWhenNoProviderConfigured() {
        // agent.tts.enabled=false (default) → provider beans not loaded → empty list
        AgentProperties properties = new AgentProperties();
        var registry = registryWithBeans(properties, ttsTool(java.util.List.of()));

        assertThat(registry.getDefinitions())
            .extracting(ToolDefinition::name)
            .doesNotContain("text_to_speech");
    }

    @Test
    void ttsToolRegisteredWhenProviderAvailable() {
        AgentProperties properties = new AgentProperties();
        var provider = Mockito.mock(com.azhukov.agent.service.tts.TtsProvider.class);
        Mockito.when(provider.name()).thenReturn("edge");
        properties.getTts().setProvider("edge");
        var registry = registryWithBeans(properties, ttsTool(java.util.List.of(provider)));

        assertThat(registry.getDefinitions())
            .extracting(ToolDefinition::name)
            .contains("text_to_speech");
    }

    @Test
    void imageGenToolHiddenWhenNoProviderConfigured() {
        // agent.image-gen.enabled=false (default) → ObjectProvider empty
        AgentProperties properties = new AgentProperties();
        var providerProvider = emptyObjectProvider();
        var tool = new com.azhukov.agent.tools.imagegen.ImageGenTool(providerProvider, properties);
        var registry = registryWithBeans(properties, tool);

        assertThat(registry.getDefinitions())
            .extracting(ToolDefinition::name)
            .doesNotContain("image_generate");
    }

    @Test
    void imageGenToolRegisteredWhenProviderAvailable() {
        AgentProperties properties = new AgentProperties();
        properties.getImageGen().setProvider("pollinations");
        var providerProvider = objectProviderWith(new PollinationsStub());
        var tool = new com.azhukov.agent.tools.imagegen.ImageGenTool(providerProvider, properties);
        var registry = registryWithBeans(properties, tool);

        assertThat(registry.getDefinitions())
            .extracting(ToolDefinition::name)
            .contains("image_generate");
    }

    private static org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.service.imagegen.ImageGenProvider> emptyObjectProvider() {
        return objectProviderWith(null);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.service.imagegen.ImageGenProvider> objectProviderWith(
            com.azhukov.agent.service.imagegen.ImageGenProvider only) {
        var provider = Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        Mockito.when(provider.stream()).thenReturn(only == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(only));
        return provider;
    }

    private static final class PollinationsStub implements com.azhukov.agent.service.imagegen.ImageGenProvider {
        @Override public String name() { return "pollinations"; }
        @Override public byte[] generate(String prompt, String aspectRatio) { return new byte[0]; }
    }
}
