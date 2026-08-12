package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
@Slf4j
public class CdpClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    // Use list of listeners per method so concurrent waitForEvent calls don't overwrite each other
    private final Map<String, List<Consumer<JsonNode>>> eventListeners = new ConcurrentHashMap<>();
    private final AtomicInteger messageId = new AtomicInteger(1);
    private WebSocketClient webSocketClient;
    private String webSocketUrl;
    private volatile boolean connected = false;

    public CdpClient(URI webSocketUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();
        this.webSocketUrl = webSocketUrl.toString();
    }

    @Autowired
    public CdpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();
    }

    public synchronized void connect(String cdpBaseUrl) throws Exception {
        if (connected) return;
        String listUrl = cdpBaseUrl.endsWith("/") ? cdpBaseUrl + "json/list" : cdpBaseUrl + "/json/list";
        HttpRequest request = HttpRequest.newBuilder(URI.create(listUrl))
            .timeout(Duration.ofSeconds(120))
            .GET()
            .build();
        String response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        JsonNode arr = objectMapper.readTree(response);
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("No CDP targets available at " + cdpBaseUrl);
        }
        this.webSocketUrl = arr.get(0).get("webSocketDebuggerUrl").asText();
        connectWebSocket();
        send("Page.enable", null).get(60, TimeUnit.SECONDS);
        send("Runtime.enable", null).get(60, TimeUnit.SECONDS);
        send("DOM.enable", null).get(60, TimeUnit.SECONDS);
        connected = true;
    }

    private void connectWebSocket() throws Exception {
        CompletableFuture<Void> connectFuture = new CompletableFuture<>();
        this.webSocketClient = new WebSocketClient(new URI(webSocketUrl)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connectFuture.complete(null);
            }
            @Override
            public void onMessage(String message) {
                handleMessage(message);
            }
            @Override
            public void onClose(int code, String reason, boolean remote) {
                CdpClient.this.connected = false;
            }
            @Override
            public void onError(Exception ex) {
                if (!connectFuture.isDone()) connectFuture.completeExceptionally(ex);
                log.error("CDP websocket error", ex);
            }
        };
        webSocketClient.connect();
        connectFuture.get(120, TimeUnit.SECONDS);
    }

    public synchronized void disconnect() {
        connected = false;
        if (webSocketClient != null) {
            try {
                webSocketClient.close();
            } catch (Exception e) {
                log.debug("Error closing CDP websocket: {}", e.getMessage());
            }
            webSocketClient = null;
        }
    }

    public CompletableFuture<JsonNode> send(String method, ObjectNode params) {
        int id = messageId.getAndIncrement();
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) {
            msg.set("params", params);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        // Null check: webSocketClient may be null if not connected or after disconnect
        if (webSocketClient == null) {
            pending.remove(id);
            future.completeExceptionally(new IllegalStateException("CDP WebSocket is not connected"));
            return future;
        }
        webSocketClient.send(msg.toString());
        return future;
    }

    private void handleMessage(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            if (node.has("id")) {
                int id = node.get("id").asInt();
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) {
                    if (node.has("error")) {
                        future.completeExceptionally(new RuntimeException(node.get("error").toString()));
                    } else {
                        future.complete(node.get("result"));
                    }
                }
            } else if (node.has("method")) {
                String method = node.get("method").asText();
                List<Consumer<JsonNode>> listeners = eventListeners.get(method);
                if (listeners != null) {
                    JsonNode params = node.path("params");
                    for (Consumer<JsonNode> listener : listeners) {
                        listener.accept(params);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to handle CDP message", e);
        }
    }

    public void onEvent(String method, Consumer<JsonNode> listener) {
        eventListeners.computeIfAbsent(method, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public CompletableFuture<JsonNode> waitForEvent(String method, long timeoutSeconds) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        Consumer<JsonNode> listener = future::complete;
        onEvent(method, listener);
        return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .whenComplete((result, ex) -> {
                // Remove the listener after the future completes (success, timeout, or error)
                List<Consumer<JsonNode>> listeners = eventListeners.get(method);
                if (listeners != null) {
                    listeners.remove(listener);
                }
            });
    }

    /**
     * Removes all listeners for the given method.
     * Useful for cleanup after waitForEvent.
     */
    public void removeListeners(String method) {
        eventListeners.remove(method);
    }

    public boolean isConnected() {
        return connected;
    }
}