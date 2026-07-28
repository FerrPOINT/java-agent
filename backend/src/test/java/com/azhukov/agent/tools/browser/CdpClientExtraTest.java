package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CdpClientExtraTest {

    @Test
    void alreadyConnectedIsNoOp() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        setConnected(client, true);
        client.connect("http://localhost:9222");
        // should not attempt network call; simply return
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void handleMessageCompletesPendingResult() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        setPending(client, 1, future);

        invokeHandleMessage(client, "{\"id\":1,\"result\":{\"ok\":true}}");

        JsonNode result = future.get(1, TimeUnit.SECONDS);
        assertThat(result.get("ok").asBoolean()).isTrue();
    }

    @Test
    void handleMessageCompletesPendingError() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        setPending(client, 2, future);

        invokeHandleMessage(client, "{\"id\":2,\"error\":{\"message\":\"fail\"}}");

        assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
            .hasMessageContaining("fail");
    }

    @Test
    void handleMessageDispatchesEvent() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        client.onEvent("Page.loadEventFired", captured::set);

        invokeHandleMessage(client, "{\"method\":\"Page.loadEventFired\",\"params\":{\"timestamp\":1}}");

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().get("timestamp").asInt()).isEqualTo(1);
    }

    @Test
    void handleMessageIgnoresUnknownEvent() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        // Should not throw
        invokeHandleMessage(client, "{\"method\":\"Unknown.event\"}");
    }

    @Test
    void waitForEventCompletes() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        CompletableFuture<JsonNode> future = client.waitForEvent("Network.response", 2);
        invokeHandleMessage(client, "{\"method\":\"Network.response\",\"params\":{}}");

        JsonNode result = future.get(1, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
    }

    @Test
    void sendCreatesMessageWithParams() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        FakeWebSocket ws = new FakeWebSocket();
        setWebSocketClient(client, ws);

        ObjectNode params = new ObjectMapper().createObjectNode().put("url", "http://example.com");
        CompletableFuture<JsonNode> future = client.send("Page.navigate", params);

        assertThat(ws.sent).hasSize(1);
        JsonNode sent = new ObjectMapper().readTree(ws.sent.get(0));
        assertThat(sent.get("method").asText()).isEqualTo("Page.navigate");
        assertThat(sent.get("params").get("url").asText()).isEqualTo("http://example.com");
        assertThat(sent.get("id").asInt()).isPositive();

        setPending(client, sent.get("id").asInt(), future);
        invokeHandleMessage(client, "{\"id\":" + sent.get("id").asInt() + ",\"result\":{}}");
        assertThat(future.get(1, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void disconnectClosesClientAndResetsState() throws Exception {
        CdpClient client = new CdpClient(new ObjectMapper());
        FakeWebSocket ws = new FakeWebSocket();
        setWebSocketClient(client, ws);
        setConnected(client, true);

        client.disconnect();

        assertThat(client.isConnected()).isFalse();
        assertThat(ws.closed).isTrue();
    }

    private void setConnected(CdpClient client, boolean value) throws Exception {
        Field field = CdpClient.class.getDeclaredField("connected");
        field.setAccessible(true);
        field.setBoolean(client, value);
    }

    @SuppressWarnings("unchecked")
    private void setPending(CdpClient client, int id, CompletableFuture<JsonNode> future) throws Exception {
        Field field = CdpClient.class.getDeclaredField("pending");
        field.setAccessible(true);
        java.util.Map<Integer, CompletableFuture<JsonNode>> map = (java.util.Map<Integer, CompletableFuture<JsonNode>>) field.get(client);
        map.put(id, future);
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
        public void onOpen(org.java_websocket.handshake.ServerHandshake handshake) { }

        @Override
        public void onMessage(String message) { }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closed = true;
        }

        @Override
        public void onError(Exception ex) { }

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
