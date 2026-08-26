package com.azhukov.agent.client;

import com.azhukov.agent.core.client.ModelClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Tag("live")
@Tag("slow")
class LangChain4jModelClientLiveTest {

    @Autowired(required = false)
    private ModelClient modelClient;

    @Test
    void contextLoadsAndClientExists() {
        // With the test profile the client is a NoOpModelClient, so this live test
        // merely verifies wiring. A real LLM call requires the dev profile + API key.
        assertThat(modelClient).isNotNull();
    }
}
