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

    public static final String USER_ID_ATTRIBUTE = DashboardWebSocketHandshakeInterceptor.class.getName() + ".userId";
    public static final String USER_ROLE_ATTRIBUTE = DashboardWebSocketHandshakeInterceptor.class.getName() + ".userRole";
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
            String userId = com.azhukov.agent.core.security.UserContext.getUserId();
            String role = com.azhukov.agent.core.security.UserContext.getRole();
            if (userId != null) {
                attributes.put(USER_ID_ATTRIBUTE, userId);
            }
            if (role != null) {
                attributes.put(USER_ROLE_ATTRIBUTE, role);
            }
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
