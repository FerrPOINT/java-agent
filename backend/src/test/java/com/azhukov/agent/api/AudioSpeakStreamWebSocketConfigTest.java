package com.azhukov.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudioSpeakStreamWebSocketConfigTest {

    @Test
    void registersProfilePrefixedSpeakStreamAliasLikeHermesDashboard() {
        AudioSpeakStreamWebSocketHandler handler = mock(AudioSpeakStreamWebSocketHandler.class);
        DashboardWebSocketHandshakeInterceptor handshakeInterceptor = mock(DashboardWebSocketHandshakeInterceptor.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(eq(handler), any(String[].class))).thenReturn(registration);

        new AudioSpeakStreamWebSocketConfig(handler, handshakeInterceptor).registerWebSocketHandlers(registry);

        verify(registry).addHandler(
            handler,
            "/api/audio/speak-stream",
            "/p/{profile}/api/audio/speak-stream");
        verify(registration).addInterceptors(handshakeInterceptor);
    }
}
