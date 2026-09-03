package com.azhukov.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventWebSocketConfigTest {

    @Test
    void registersProfilePrefixedEventsAliasWithDashboardHandshakeGuard() {
        EventWebSocketHandler handler = mock(EventWebSocketHandler.class);
        DashboardWebSocketHandshakeInterceptor handshakeInterceptor = mock(DashboardWebSocketHandshakeInterceptor.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(eq(handler), any(String[].class))).thenReturn(registration);

        new EventWebSocketConfig(handler, handshakeInterceptor).registerWebSocketHandlers(registry);

        verify(registry).addHandler(handler, "/api/events", "/p/{profile}/api/events");
        verify(registration).addInterceptors(handshakeInterceptor);
    }
}
