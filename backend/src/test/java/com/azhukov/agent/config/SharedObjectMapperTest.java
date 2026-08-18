package com.azhukov.agent.config;

import com.azhukov.agent.config.split.ModelClientConfig;
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
        ModelClientConfig config = new ModelClientConfig();
        assertThat(config.objectMapper()).isSameAs(SharedObjectMapper.get());
    }
}