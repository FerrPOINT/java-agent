package com.azhukov.agent.tools.memory;

import com.azhukov.agent.api.dto.StreamEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming-context bridge for the blocking clarify tool. The streaming agent
 * loop registers a sender bound to the session BEFORE dispatching a tool
 * batch; {@code ClarifyTool} — which runs on a worker thread, not the stream
 * thread — looks the sender up by session id and emits the {@code clarify}
 * SSE event through it. ThreadLocal was unusable here: the tool executor
 * submits execution to its own thread pool.
 */
public final class ClarifyStreamBridge {

    private static final Map<UUID, Sender> SENDERS = new ConcurrentHashMap<>();

    /** Functional interface the streaming loop provides to emit a clarify prompt. */
    @FunctionalInterface
    public interface Sender {
        void sendClarifyPrompt(String payloadJson);
    }

    private ClarifyStreamBridge() {
    }

    public static void setSender(UUID sessionId, Sender sender) {
        if (sessionId != null && sender != null) {
            SENDERS.put(sessionId, sender);
        }
    }

    public static Sender sender(UUID sessionId) {
        return sessionId == null ? null : SENDERS.get(sessionId);
    }

    public static void clear(UUID sessionId) {
        if (sessionId != null) {
            SENDERS.remove(sessionId);
        }
    }

    /** The SSE event payload a sender emits ({@code error} field carries the JSON prompt, review-event style). */
    public static StreamEvent clarifyEvent(String payloadJson) {
        return new StreamEvent("clarify", null, null, payloadJson);
    }
}
