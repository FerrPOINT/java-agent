package com.azhukov.agent.api;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the console output-streaming WebSocket endpoint behind the same
 * dashboard handshake guard as the events socket (docs/34 gap 2).
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ConsoleWebSocketConfig implements WebSocketConfigurer {

    private final ConsoleWebSocketHandler handler;
    private final DashboardWebSocketHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/console/ws", "/p/{profile}/api/console/ws")
            .addInterceptors(handshakeInterceptor);
    }
}
