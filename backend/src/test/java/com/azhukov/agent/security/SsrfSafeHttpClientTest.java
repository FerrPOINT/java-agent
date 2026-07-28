package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsrfSafeHttpClientTest {

    @Test
    void fetchBlocksUnsafeUrl() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn("blocked");
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        assertThatThrownBy(() -> client.fetch("http://localhost/admin", 5))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("blocked");
    }

    @Test
    void postBlocksUnsafeUrl() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn("blocked");
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        assertThatThrownBy(() -> client.post("http://example.com", "body", 5))
            .isInstanceOf(SecurityException.class);
    }
}
