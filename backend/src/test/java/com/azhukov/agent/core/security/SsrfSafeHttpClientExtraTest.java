package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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
    @DisplayName("fetch() with valid URL calls safety check and does not throw SecurityException")
    void fetchWithValidUrlReturnsRedactedBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AgentProperties properties = new AgentProperties();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page";
            UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
            when(safety.checkUrl(url)).thenReturn(null);
            SecretRedactor redactor = mock(SecretRedactor.class);
            when(redactor.redact("ok")).thenReturn("ok");

            SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

            assertThat(client.fetch(url, 5)).isEqualTo("ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("fetch() blocks redirects to unsafe URLs")
    void fetchBlocksUnsafeRedirectTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            AgentProperties properties = new AgentProperties();
            int port = server.getAddress().getPort();
            String source = "http://127.0.0.1:" + port + "/redirect";
            String target = "http://127.0.0.1:" + port + "/private";
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", target);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
            when(safety.checkUrl(source)).thenReturn(null);
            when(safety.checkUrl(target)).thenReturn("URL blocked by safety policy");
            SecretRedactor redactor = mock(SecretRedactor.class);

            SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

            assertThatThrownBy(() -> client.fetch(source, 5))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Redirect blocked: URL blocked by safety policy");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("post() with valid URL returns redacted body")
    void postWithValidUrlReturnsRedactedBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            byte[] body = "posted".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AgentProperties properties = new AgentProperties();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
            UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
            when(safety.checkUrl(url)).thenReturn(null);
            SecretRedactor redactor = mock(SecretRedactor.class);
            when(redactor.redact("posted")).thenReturn("posted");

            SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

            assertThat(client.post(url, "body", 5)).isEqualTo("posted");
        } finally {
            server.stop(0);
        }
    }

}
