package com.azhukov.agent.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("noop")
@TestPropertySource(properties = {
    "server.shutdown=immediate"
})
@Tag("slow")
class ShutdownConfigTest {

    @Autowired
    private Environment environment;

    @Test
    void shutdownIsImmediateToAvoidGracefulShutdownBug() {
        String shutdown = environment.getProperty("server.shutdown");
        assertThat(shutdown).isEqualTo("immediate");
    }
}
