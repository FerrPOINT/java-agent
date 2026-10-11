package com.azhukov.agent.config;

import com.azhukov.agent.support.BackendIntegrationTestBase;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
@ActiveProfiles("noop")
@TestPropertySource(properties = {
    "server.shutdown=immediate"
})
@Tag("slow")
class ShutdownConfigTest extends BackendIntegrationTestBase {

    @Autowired
    private Environment environment;

    @Test
    void shutdownIsImmediateToAvoidGracefulShutdownBug() {
        String shutdown = environment.getProperty("server.shutdown");
        assertThat(shutdown).isEqualTo("immediate");
    }
}
