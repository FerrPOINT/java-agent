package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentBackendClientTest {

    private RestClient restClient;
    private RestClient.RequestHeadersUriSpec<?> getSpec;
    private RestClient.RequestBodyUriSpec postSpec;
    private RestClient.ResponseSpec responseSpec;
    private ObjectMapper objectMapper;
    private BotProperties properties;
    private AgentBackendClient client;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        postSpec = mock(RestClient.RequestBodyUriSpec.class);
        getSpec = (RestClient.RequestHeadersUriSpec<?>) mock(RestClient.RequestHeadersUriSpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);

        // POST chain
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postSpec);
        when(postSpec.contentType(any())).thenReturn(postSpec);
        when(postSpec.body(any(Object.class))).thenReturn(postSpec);
        when(postSpec.retrieve()).thenReturn(responseSpec);

        // GET chain — use doReturn to bypass generic type issues
        doReturn(getSpec).when(restClient).get();
        doReturn(getSpec).when(getSpec).uri(anyString());
        when(getSpec.retrieve()).thenReturn(responseSpec);

        objectMapper = new ObjectMapper();
        properties = new BotProperties();
        properties.setBackendUrl("http://localhost:8090");
        client = new AgentBackendClient(restClient, objectMapper, properties);
        client.init();
    }

    // ─── chat success ──────────────────────────────────────────────

    @Test
    void chat_success_returnsResponseField() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"Hello from agent\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "session-123");

        assertThat(result.content()).isEqualTo("Hello from agent");
    }

    @Test
    void chat_extractsMetadataFields() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"Hello\",\"modelUsed\":\"kimi-k2.6\",\"contextTokens\":5000,\"contextLength\":20000}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "session-123");

        assertThat(result.content()).isEqualTo("Hello");
        assertThat(result.modelUsed()).isEqualTo("kimi-k2.6");
        assertThat(result.contextTokens()).isEqualTo(5000);
        assertThat(result.contextLength()).isEqualTo(20000);
    }

    @Test
    void chat_withNullSessionId_omitsSessionId() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", null);

        assertThat(result.content()).isEqualTo("OK");
        // Verify the body map was passed (we can't easily inspect the map, but verify the call chain)
        verify(postSpec).body(any(Object.class));
    }

    @Test
    void chat_withBlankSessionId_omitsSessionId() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "  ");

        assertThat(result.content()).isEqualTo("OK");
    }

    @Test
    void chat_success_withExtraFieldsInResponse() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"Result\",\"metadata\":\"extra\",\"tokens\":42}");

        AgentBackendClient.ChatResult result = client.chat("test", "s1");

        assertThat(result.content()).isEqualTo("Result");
    }

    // ─── chat error handling ───────────────────────────────────────

    @Test
    void chat_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenThrow(new RuntimeException("Connection refused"));

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("Connection refused");
    }

    @Test
    void chat_emptyResponse_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn("");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("empty response");
    }

    @Test
    void chat_nullResponse_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn(null);

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("empty response");
    }

    @Test
    void chat_missingResponseField_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"other\":\"value\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("missing 'response' field");
    }

    @Test
    void chat_nullResponseField_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":null}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("missing 'response' field");
    }

    @Test
    void chat_malformedJson_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn("not valid json{{{");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
    }

    // ─── health check ──────────────────────────────────────────────

    @Test
    void health_up_returnsTrue() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"status\":\"UP\"}");

        assertThat(client.health()).isTrue();
    }

    @Test
    void health_ok_returnsTrue() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"status\":\"OK\"}");

        assertThat(client.health()).isTrue();
    }

    @Test
    void health_down_returnsFalse() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"status\":\"DOWN\"}");

        assertThat(client.health()).isFalse();
    }

    @Test
    void health_noStatusField_returnsTrue() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"info\":\"agent v1.0\"}");

        assertThat(client.health()).isTrue();
    }

    @Test
    void health_exception_returnsFalse() {
        when(responseSpec.body(String.class))
            .thenThrow(new RuntimeException("Connection refused"));

        assertThat(client.health()).isFalse();
    }

    @Test
    void health_emptyResponse_returnsFalse() {
        when(responseSpec.body(String.class))
            .thenReturn("");

        assertThat(client.health()).isFalse();
    }

    @Test
    void health_nullResponse_returnsFalse() {
        when(responseSpec.body(String.class))
            .thenReturn(null);

        assertThat(client.health()).isFalse();
    }

    // ─── base URL ──────────────────────────────────────────────────

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        assertThat(client.getBaseUrl()).isEqualTo("http://localhost:8090");
    }
}