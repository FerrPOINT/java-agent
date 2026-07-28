package com.azhukov.agent.config;

import com.azhukov.agent.core.client.ModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.profiles.active=noop",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false",
    "agent.mcp.enabled=false",
    "agent.chromium.auto-start=false",
    "agent.gateway.telegram.long-polling.enabled=false"
})
class AgentConfigNoopProfileTest {

    @Autowired
    private ModelClient modelClient;

    @Test
    void contextLoadsAndNoopClientIsPresent() {
        assertThat(modelClient).isNotNull();
    }
}
