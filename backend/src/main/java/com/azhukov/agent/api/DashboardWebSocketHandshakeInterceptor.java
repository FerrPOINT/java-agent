package com.azhukov.agent.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DashboardWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private static final String REJECTION_HEADER = "X-Hermes-WebSocket-Reject";

    private final DashboardWebSocketGuard guard;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        HttpHeaders headers = request.getHeaders();
        String reason = guard.rejectionReason(
            headers.getFirst(HttpHeaders.HOST),
            headers.getFirst(HttpHeaders.ORIGIN));
        if (reason == null) {
            return true;
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().set(REJECTION_HEADER, reason);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // No post-handshake state to clean up.
    }
}
