package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardWebSocketHandshakeInterceptorTest {

    @Test
    void rejectsBadHostBeforeUpgrade() {
        DashboardWebSocketHandshakeInterceptor interceptor = interceptor("127.0.0.1");
        ServerHttpRequest request = request("evil.example", "http://evil.example");
        ServerHttpResponse response = response();

        boolean allowed = interceptor.beforeHandshake(request, response, mock(org.springframework.web.socket.WebSocketHandler.class), new HashMap<>());

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst("X-Hermes-WebSocket-Reject")).isEqualTo("host_mismatch");
    }

    @Test
    void acceptsLoopbackHostAndOrigin() {
        DashboardWebSocketHandshakeInterceptor interceptor = interceptor("127.0.0.1");
        ServerHttpRequest request = request("localhost:8090", "http://localhost:8090");
        ServerHttpResponse response = response();

        boolean allowed = interceptor.beforeHandshake(request, response, mock(org.springframework.web.socket.WebSocketHandler.class), new HashMap<>());

        assertThat(allowed).isTrue();
        verifyNoInteractions(response);
    }

    private static DashboardWebSocketHandshakeInterceptor interceptor(String boundHost) {
        return new DashboardWebSocketHandshakeInterceptor(
            new DashboardWebSocketGuard(new AgentProperties(), boundHost));
    }

    private static ServerHttpRequest request(String host, String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.HOST, host);
        if (origin != null) {
            headers.set(HttpHeaders.ORIGIN, origin);
        }
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }

    private static ServerHttpResponse response() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        return response;
    }
}
