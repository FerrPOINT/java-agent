package com.azhukov.agent.api;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AudioSpeakStreamWebSocketConfig implements WebSocketConfigurer {

    private final AudioSpeakStreamWebSocketHandler handler;
    private final DashboardWebSocketHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/audio/speak-stream", "/p/{profile}/api/audio/speak-stream")
            .addInterceptors(handshakeInterceptor);
    }
}
