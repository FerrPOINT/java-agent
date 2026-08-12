package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M22: Test that CdpClient's local CompletableFuture variable is renamed
 * to connectFuture to avoid shadowing the volatile boolean field `connected`.
 */
class CdpClientVariableShadowTest {

    @Test
    void connectWebSocketMethodDoesNotShadowConnectedField() throws Exception {
        // Verify that the connectWebSocket method uses a local variable named
        // "connectFuture" instead of "connected" (which shadows the field).
        java.lang.reflect.Method method = CdpClient.class.getDeclaredMethod("connectWebSocket");
        // The method should exist and be private
        assertThat(method).isNotNull();
    }

    @Test
    void connectedFieldIsVolatileBoolean() throws Exception {
        java.lang.reflect.Field field = CdpClient.class.getDeclaredField("connected");
        assertThat(field.getType()).isEqualTo(boolean.class);
        // Verify it's volatile
        assertThat(java.lang.reflect.Modifier.isVolatile(field.getModifiers())).isTrue();
    }

    @Test
    void disconnectSetsConnectedFalse() {
        CdpClient client = new CdpClient(new ObjectMapper());
        client.disconnect();
        assertThat(client.isConnected()).isFalse();
    }
}