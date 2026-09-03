package com.azhukov.agent.service.imagegen;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiImageGenProviderTest {

    @Test
    void generate_blankApiKeyFailsBeforeNetworkCall() {
        AgentProperties properties = new AgentProperties();
        properties.getImageGen().setEnabled(true);
        properties.getImageGen().setApiKey("   ");
        OpenAiImageGenProvider provider = new OpenAiImageGenProvider(properties);

        assertThatThrownBy(() -> provider.generate("a cat", "landscape"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("api-key");
    }
}
