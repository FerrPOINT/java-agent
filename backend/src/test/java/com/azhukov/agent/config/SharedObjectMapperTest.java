package com.azhukov.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REM-11: Verify that ObjectMapper is reused via a shared singleton
 * instead of creating new instances everywhere.
 */
class SharedObjectMapperTest {

    @Test
    void get_returnsSameInstance() {
        assertThat(SharedObjectMapper.get()).isSameAs(SharedObjectMapper.get());
    }

    @Test
    void get_returnsNonNullObjectMapper() {
        assertThat(SharedObjectMapper.get()).isNotNull();
    }

    @Test
    void objectMapper_bean_usesSharedSingleton() {
        AgentConfig config = new AgentConfig();
        assertThat(config.objectMapper()).isSameAs(SharedObjectMapper.get());
    }
}