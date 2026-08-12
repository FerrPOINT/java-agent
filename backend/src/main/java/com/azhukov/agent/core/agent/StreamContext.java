package com.azhukov.agent.core.agent;

/**
 * Per-stream context that replaces the old singleton volatile boolean
 * {@code clientDisconnected} field on {@code AgentStreamingService}.
 *
 * <p>Each SSE stream gets its own {@link StreamContext} instance, so concurrent
 * streams no longer interfere with each other's disconnect state.
 */
public class StreamContext {

    /**
     * Volatile flag shared between the streaming thread and SseEmitter lifecycle
     * callbacks (which fire on the servlet container thread).  When the client
     * disconnects (timeout, error, or completion), this is set to {@code true}
     * so that {@code send()} can skip further writes.
     */
    private volatile boolean clientDisconnected = false;

    /**
     * Returns {@code true} if the client has disconnected (timeout, error, or
     * completion).
     *
     * @return whether the client is disconnected
     */
    public boolean isClientDisconnected() {
        return clientDisconnected;
    }

    /**
     * Mark the client as disconnected.
     */
    public void markDisconnected() {
        clientDisconnected = true;
    }
}