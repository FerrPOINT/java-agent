package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Additional CdpClient tests that don't overlap with CdpClientTest or CdpClientExtraTest.
 */
class CdpClientExtra2Test {

    @Test
    @DisplayName("constructor with URI sets webSocketUrl")
    void constructorWithUriSetsWebSocketUrl() throws Exception {
        URI uri = URI.create("ws://localhost:9222/devtools/page/abc");
        CdpClient client = new CdpClient(uri, new ObjectMapper());

        Field wsUrlField = CdpClient.class.getDeclaredField("webSocketUrl");
        wsUrlField.setAccessible(true);
        String webSocketUrl = (String) wsUrlField.get(client);

        assertThat(webSocketUrl).isEqualTo("ws://localhost:9222/devtools/page/abc");
    }

    @Test
    @DisplayName("constructor with ObjectMapper only (Spring autowired) starts disconnected")
    void constructorWithObjectMapperOnlyStartsDisconnected() {
        CdpClient client = new CdpClient(new ObjectMapper());

        assertThat(client.isConnected()).isFalse();
    }

    @Test
    @DisplayName("isConnected() returns false initially")
    void isConnectedReturnsFalseInitially() {
        CdpClient client = new CdpClient(new ObjectMapper());
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    @DisplayName("disconnect() sets connected to false")
    void disconnectSetsConnectedToFalse() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        setConnected(client, true);
        client.disconnect();
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    @DisplayName("send() throws IllegalStateException when webSocketClient is null (not connected)")
    void sendThrowsWhenNotConnected() {
        CdpClient client = new CdpClient(new ObjectMapper());
        ObjectNode params = new ObjectMapper().createObjectNode().put("url", "http://example.com");

        // After fix: send() returns a completed-exceptionally future instead of NPE
        CompletableFuture<JsonNode> future = client.send("Page.navigate", params);
        assertThat(future.isCompletedExceptionally()).isTrue();
    }

    @Test
    @DisplayName("registerEventListener via onEvent fires for matching method")
    void registerEventListenerFiresForMatchingMethod() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        client.onEvent("Network.requestWillBeSent", captured::set);

        invokeHandleMessage(client, "{\"method\":\"Network.requestWillBeSent\",\"params\":{\"requestId\":\"1\"}}");

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().get("requestId").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("unregisterEventListener by removing all listeners")
    void unregisterEventListenerByReplacingWithNull() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        client.onEvent("Page.frameNavigated", captured::set);

        // Unregister by removing all listeners for this method
        client.removeListeners("Page.frameNavigated");

        invokeHandleMessage(client, "{\"method\":\"Page.frameNavigated\",\"params\":{\"frameId\":\"abc\"}}");

        // captured should still be null since we removed all listeners
        assertThat(captured.get()).isNull();
    }

    @Test
    @DisplayName("handleMessage with invalid JSON does not throw")
    void handleMessageWithInvalidJsonDoesNotThrow() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        invokeHandleMessage(client, "not valid json");
        // Should not throw, just log
    }

    @Test
    @DisplayName("send() with null params sends message without params field")
    void sendWithNullParamsSendsMessageWithoutParams() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        FakeWebSocket ws = new FakeWebSocket();
        setWebSocketClient(client, ws);

        CompletableFuture<JsonNode> future = client.send("Page.enable", null);

        assertThat(ws.sent).hasSize(1);
        JsonNode sent = new ObjectMapper().readTree(ws.sent.get(0));
        assertThat(sent.get("method").asText()).isEqualTo("Page.enable");
        assertThat(sent.has("params")).isFalse();
        assertThat(sent.get("id").asInt()).isPositive();
    }

    // --- Helper methods ---

    private void setConnected(CdpClient client, boolean value) throws Exception {
        Field field = CdpClient.class.getDeclaredField("connected");
        field.setAccessible(true);
        field.setBoolean(client, value);
    }

    private void setWebSocketClient(CdpClient client, WebSocketClient ws) throws Exception {
        Field field = CdpClient.class.getDeclaredField("webSocketClient");
        field.setAccessible(true);
        field.set(client, ws);
    }

    private void invokeHandleMessage(CdpClient client, String message) throws Exception {
        Method method = CdpClient.class.getDeclaredMethod("handleMessage", String.class);
        method.setAccessible(true);
        method.invoke(client, message);
    }

    static class FakeWebSocket extends WebSocketClient {
        final java.util.List<String> sent = new java.util.ArrayList<>();
        boolean closed = false;

        FakeWebSocket() {
            super(java.net.URI.create("ws://localhost:1"));
        }

        @Override
        public void onOpen(org.java_websocket.handshake.ServerHandshake handshake) {}

        @Override
        public void onMessage(String message) {}

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closed = true;
        }

        @Override
        public void onError(Exception ex) {}

        @Override
        public void send(String text) {
            sent.add(text);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}