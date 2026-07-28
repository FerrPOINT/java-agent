package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsrfSafeHttpClientExtraTest {

    @Test
    @DisplayName("constructor creates client with custom User-Agent from properties")
    void constructorCreatesClientWithCustomUserAgent() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();
        properties.getCore().setHttpUserAgent("MyCustomAgent/2.0");

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        assertThat(client).isNotNull();
        // Verify the restClient field is initialized
        RestClient restClient = (RestClient) ReflectionTestUtils.getField(client, "restClient");
        assertThat(restClient).isNotNull();
    }

    @Test
    @DisplayName("fetch() throws SecurityException when URL is blocked")
    void fetchThrowsSecurityExceptionWhenUrlBlocked() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn("URL is blocked");
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        assertThatThrownBy(() -> client.fetch("http://evil.com/admin", 5))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("URL is blocked");
    }

    @Test
    @DisplayName("post() throws SecurityException when URL is blocked")
    void postThrowsSecurityExceptionWhenUrlBlocked() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn("Host is blocked");
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        assertThatThrownBy(() -> client.post("http://evil.com/api", "body", 5))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("Host is blocked");
    }

    @Test
    @DisplayName("fetch() returns empty string when response body is null")
    void fetchReturnsEmptyStringWhenResponseBodyIsNull() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn(null);
        SecretRedactor redactor = mock(SecretRedactor.class);
        when(redactor.redact("")).thenReturn("");
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        // We need to mock the internally-created RestClient. Since we can't inject it,
        // we'll use a spy to replace the restClient field with a mock.
        RestClient mockRestClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> mockUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> mockRequestSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec mockResponseSpec = mock(RestClient.ResponseSpec.class);

        when(mockRestClient.get()).thenAnswer(inv -> mockUriSpec);
        when(mockUriSpec.uri(anyString())).thenAnswer(inv -> mockRequestSpec);
        when(mockRequestSpec.header(anyString(), anyString())).thenAnswer(inv -> mockRequestSpec);
        when(mockRequestSpec.retrieve()).thenAnswer(inv -> mockResponseSpec);
        when(mockResponseSpec.body(String.class)).thenReturn(null);

        ReflectionTestUtils.setField(client, "restClient", mockRestClient);

        String result = client.fetch("https://example.com/page", 5);
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("post() returns empty string when response body is null")
    void postReturnsEmptyStringWhenResponseBodyIsNull() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn(null);
        SecretRedactor redactor = mock(SecretRedactor.class);
        when(redactor.redact("")).thenReturn("");
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        RestClient mockRestClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec mockBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec mockBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec mockResponseSpec = mock(RestClient.ResponseSpec.class);

        when(mockRestClient.post()).thenAnswer(inv -> mockBodyUriSpec);
        when(mockBodyUriSpec.uri(anyString())).thenAnswer(inv -> mockBodySpec);
        when(mockBodySpec.header(anyString(), anyString())).thenAnswer(inv -> mockBodySpec);
        when(mockBodySpec.body(anyString())).thenAnswer(inv -> mockBodySpec);
        when(mockBodySpec.retrieve()).thenAnswer(inv -> mockResponseSpec);
        when(mockResponseSpec.body(String.class)).thenReturn(null);

        ReflectionTestUtils.setField(client, "restClient", mockRestClient);

        String result = client.post("https://example.com/api", "request body", 5);
        assertThat(result).isEqualTo("");
    }

    @Test
    @DisplayName("fetch() returns redacted content for valid URL")
    void fetchReturnsRedactedContentForValidUrl() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn(null);
        SecretRedactor redactor = mock(SecretRedactor.class);
        when(redactor.redact("sensitive data")).thenReturn("redacted content");
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        RestClient mockRestClient = mock(RestClient.class);
        RestClient.RequestHeadersUriSpec<?> mockUriSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec<?> mockRequestSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec mockResponseSpec = mock(RestClient.ResponseSpec.class);

        when(mockRestClient.get()).thenAnswer(inv -> mockUriSpec);
        when(mockUriSpec.uri(anyString())).thenAnswer(inv -> mockRequestSpec);
        when(mockRequestSpec.header(anyString(), anyString())).thenAnswer(inv -> mockRequestSpec);
        when(mockRequestSpec.retrieve()).thenAnswer(inv -> mockResponseSpec);
        when(mockResponseSpec.body(String.class)).thenReturn("sensitive data");

        ReflectionTestUtils.setField(client, "restClient", mockRestClient);

        String result = client.fetch("https://example.com/page", 5);
        assertThat(result).isEqualTo("redacted content");
    }

    @Test
    @DisplayName("post() returns redacted content for valid URL")
    void postReturnsRedactedContentForValidUrl() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn(null);
        SecretRedactor redactor = mock(SecretRedactor.class);
        when(redactor.redact("response secret")).thenReturn("[REDACTED]");
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        RestClient mockRestClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec mockBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec mockBodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec mockResponseSpec = mock(RestClient.ResponseSpec.class);

        when(mockRestClient.post()).thenAnswer(inv -> mockBodyUriSpec);
        when(mockBodyUriSpec.uri(anyString())).thenAnswer(inv -> mockBodySpec);
        when(mockBodySpec.header(anyString(), anyString())).thenAnswer(inv -> mockBodySpec);
        when(mockBodySpec.body(anyString())).thenAnswer(inv -> mockBodySpec);
        when(mockBodySpec.retrieve()).thenAnswer(inv -> mockResponseSpec);
        when(mockResponseSpec.body(String.class)).thenReturn("response secret");

        ReflectionTestUtils.setField(client, "restClient", mockRestClient);

        String result = client.post("https://example.com/api", "request body", 5);
        assertThat(result).isEqualTo("[REDACTED]");
    }
}