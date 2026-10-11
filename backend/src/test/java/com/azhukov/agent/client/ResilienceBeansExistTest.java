package com.azhukov.agent.client;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.support.NoopSpringIntegrationTest;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("slow")
class ResilienceBeansExistTest extends NoopSpringIntegrationTest {

    @Autowired(required = false)
    private ModelClient modelClient;

    @Autowired(required = false)
    private RetryRegistry retryRegistry;

    @Autowired(required = false)
    private TimeLimiterRegistry timeLimiterRegistry;

    @Test
    void resilienceRegistriesAndNoopModelClientAreAvailable() {
        assertThat(modelClient).isNotNull();
        assertThat(retryRegistry).isNotNull();
        assertThat(timeLimiterRegistry).isNotNull();
    }
}
