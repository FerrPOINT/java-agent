package com.azhukov.agent.tools.browser;

import org.java_websocket.client.WebSocketClient;

/** In-memory CDP transport shared by isolated and manual test lanes. */
final class CdpTestWebSocket extends WebSocketClient {
    final java.util.List<String> sent = new java.util.ArrayList<>();
    boolean closed = false;

    CdpTestWebSocket() {
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
