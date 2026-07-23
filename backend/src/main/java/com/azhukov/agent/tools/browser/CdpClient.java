package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CdpClient {

    private static final Logger log = LoggerFactory.getLogger(CdpClient.class);
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger messageId = new AtomicInteger(1);
    private WebSocketClient webSocketClient;
    private String webSocketUrl;
    private volatile boolean connected = false;

    public CdpClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public synchronized void connect(String cdpBaseUrl) throws Exception {
        if (connected) return;
        String listUrl = cdpBaseUrl.endsWith("/") ? cdpBaseUrl + "json/list" : cdpBaseUrl + "/json/list";
        HttpRequest request = HttpRequest.newBuilder(URI.create(listUrl))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        String response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        JsonNode arr = objectMapper.readTree(response);
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("No CDP targets available at " + cdpBaseUrl);
        }
        this.webSocketUrl = arr.get(0).get("webSocketDebuggerUrl").asText();
        connectWebSocket();
        send("Page.enable", null).get(5, TimeUnit.SECONDS);
        send("Runtime.enable", null).get(5, TimeUnit.SECONDS);
        send("DOM.enable", null).get(5, TimeUnit.SECONDS);
        connected = true;
    }

    private void connectWebSocket() throws Exception {
        CompletableFuture<Void> connected = new CompletableFuture<>();
        this.webSocketClient = new WebSocketClient(new URI(webSocketUrl)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connected.complete(null);
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
                if (!connected.isDone()) connected.completeExceptionally(ex);
                log.error("CDP websocket error", ex);
            }
        };
        webSocketClient.connect();
        connected.get(10, TimeUnit.SECONDS);
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
            }
        } catch (Exception e) {
            log.error("Failed to handle CDP message", e);
        }
    }

    public boolean isConnected() {
        return connected;
    }
}
