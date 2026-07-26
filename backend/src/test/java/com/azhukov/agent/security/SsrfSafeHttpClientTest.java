package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SsrfSafeHttpClientTest {

    @Test
    void fetchBlocksUnsafeUrl() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl("http://evil")).thenReturn("blocked");
        SsrfSafeHttpClient c = new SsrfSafeHttpClient(safety, mock(SecretRedactor.class), new AgentProperties());
        assertThatThrownBy(() -> c.fetch("http://evil", 5)).isInstanceOf(SecurityException.class);
    }

    @Test
    void postBlocksUnsafeUrl() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl("http://evil")).thenReturn("blocked");
        SsrfSafeHttpClient c = new SsrfSafeHttpClient(safety, mock(SecretRedactor.class), new AgentProperties());
        assertThatThrownBy(() -> c.post("http://evil", "body", 5)).isInstanceOf(SecurityException.class);
    }
}
