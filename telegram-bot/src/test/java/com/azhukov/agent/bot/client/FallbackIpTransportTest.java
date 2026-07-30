package com.azhukov.agent.bot.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FallbackIpTransportTest {

    @Test
    void skipsNonTelegramRequests() throws IOException {
        FallbackIpTransport transport = new FallbackIpTransport(List.of("149.154.167.220"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = mock(ClientHttpResponse.class);

        HttpRequest request = new TestRequest(URI.create("https://localhost:8090/api/test"), HttpMethod.POST);
        when(execution.execute(eq(request), any())).thenReturn(response);

        ClientHttpResponse result = transport.intercept(request, new byte[0], execution);
        assertThat(result).isSameAs(response);
        verify(execution).execute(eq(request), any());
    }

    @Test
    void usesPrimaryWhenNoFallbackIps() throws IOException {
        FallbackIpTransport transport = new FallbackIpTransport(List.of());
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = mock(ClientHttpResponse.class);

        HttpRequest request = new TestRequest(URI.create("https://api.telegram.org/bot123/sendMessage"), HttpMethod.POST);
        when(execution.execute(eq(request), any())).thenReturn(response);

        ClientHttpResponse result = transport.intercept(request, new byte[0], execution);
        assertThat(result).isSameAs(response);
    }

    @Test
    void fallsBackToIpOnConnectError() throws IOException {
        FallbackIpTransport transport = new FallbackIpTransport(List.of("149.154.167.220"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = mock(ClientHttpResponse.class);

        HttpRequest request = new TestRequest(URI.create("https://api.telegram.org/bot123/sendMessage"), HttpMethod.POST);

        // Primary fails with connect error
        when(execution.execute(any(), any()))
            .thenThrow(new java.net.ConnectException("Connection refused"))
            .thenReturn(response);

        ClientHttpResponse result = transport.intercept(request, new byte[0], execution);
        assertThat(result).isSameAs(response);
        verify(execution, times(2)).execute(any(), any());
    }

    @Test
    void stickyIpIsSetAfterSuccessfulFallback() throws IOException {
        FallbackIpTransport transport = new FallbackIpTransport(List.of("149.154.167.220"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = mock(ClientHttpResponse.class);

        HttpRequest request = new TestRequest(URI.create("https://api.telegram.org/bot123/sendMessage"), HttpMethod.POST);

        // Primary fails, fallback succeeds
        when(execution.execute(any(), any()))
            .thenThrow(new java.net.ConnectException("Connection refused"))
            .thenReturn(response);

        transport.intercept(request, new byte[0], execution);
        assertThat(transport.getStickyIp()).isEqualTo("149.154.167.220");
    }

    @Test
    void stickyIpResetOnFailure() throws IOException {
        FallbackIpTransport transport = new FallbackIpTransport(List.of("149.154.167.220"));
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);

        HttpRequest request = new TestRequest(URI.create("https://api.telegram.org/bot123/sendMessage"), HttpMethod.POST);

        // All attempts fail
        when(execution.execute(any(), any()))
            .thenThrow(new java.net.ConnectException("Connection refused"));

        try {
            transport.intercept(request, new byte[0], execution);
        } catch (IOException e) {
            // Expected
        }
        assertThat(transport.getStickyIp()).isNull();
    }

    @Test
    void isTelegramApiRequestChecksHost() {
        HttpRequest telegramRequest = new TestRequest(URI.create("https://api.telegram.org/bot/test"), HttpMethod.POST);
        HttpRequest otherRequest = new TestRequest(URI.create("https://localhost/test"), HttpMethod.GET);

        assertThat(FallbackIpTransport.isTelegramApiRequest(telegramRequest)).isTrue();
        assertThat(FallbackIpTransport.isTelegramApiRequest(otherRequest)).isFalse();
    }

    @Test
    void isRetryableConnectErrorDetectsConnectException() {
        assertThat(FallbackIpTransport.isRetryableConnectError(new java.net.ConnectException("refused"))).isTrue();
        assertThat(FallbackIpTransport.isRetryableConnectError(new java.net.SocketTimeoutException("timeout"))).isTrue();
        assertThat(FallbackIpTransport.isRetryableConnectError(new RuntimeException("other"))).isFalse();
    }

    @Test
    void rewriteForIpPreservesHostHeader() {
        HttpRequest original = new TestRequest(URI.create("https://api.telegram.org/bot123/sendMessage"), HttpMethod.POST);
        HttpRequest rewritten = FallbackIpTransport.rewriteForIp(original, "149.154.167.220");

        assertThat(rewritten.getURI().toString()).contains("149.154.167.220");
        assertThat(rewritten.getHeaders().getFirst("Host")).isEqualTo("api.telegram.org");
    }

    @Test
    void fallbackIpsValidatedOnConstruction() {
        // Private IPs should be filtered out
        FallbackIpTransport transport = new FallbackIpTransport(List.of("127.0.0.1", "149.154.167.220"));
        assertThat(transport.getFallbackIps()).containsExactly("149.154.167.220");
    }

    /** Simple test request implementation. */
    static class TestRequest implements HttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final HttpHeaders headers = new HttpHeaders();

        TestRequest(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        @Override
        public HttpMethod getMethod() { return method; }

        @Override
        public URI getURI() { return uri; }

        @Override
        public HttpHeaders getHeaders() { return headers; }

        @Override
        public java.util.Map<String, Object> getAttributes() { return java.util.Map.of(); }
    }
}