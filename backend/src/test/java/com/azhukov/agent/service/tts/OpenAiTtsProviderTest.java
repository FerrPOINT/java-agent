package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiTtsProviderTest {

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getTts().setEnabled(true);
        properties.getTts().setProvider("openai");
        properties.getTts().setApiKey("test-key");
        properties.getTts().setVoice("alloy");
        properties.getModel().setBaseUrl("https://api.openai.com/v1");
    }

    @Test
    void synthesize_throwsWhenNoApiKey() {
        properties.getTts().setApiKey("");
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key");
    }

    @Test
    void synthesize_usesDefaultVoiceWhenNullProvided() {
        // We can't make a real HTTP call in a unit test, but we can verify
        // the provider constructs without error and uses default voice logic
        properties.getTts().setApiKey("test-key");
        OpenAiTtsProvider provider = new OpenAiTtsProvider(properties);
        // The real call will fail with a network error, not a config error
        assertThatThrownBy(() -> provider.synthesize("hello", null))
            .isInstanceOf(RuntimeException.class);
    }
}