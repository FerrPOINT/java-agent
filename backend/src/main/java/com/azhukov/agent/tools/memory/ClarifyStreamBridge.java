package com.azhukov.agent.tools.memory;

import com.azhukov.agent.api.dto.StreamEvent;

/**
 * Streaming-context bridge for the blocking clarify tool. The streaming agent
 * loop registers a sender before dispatching a tool batch; {@code ClarifyTool}
 * reads it at call time to emit the {@code clarify} SSE event (the prompt the
 * adapter renders) without the tool layer knowing about SSE or emitters.
 * Null sender = non-interactive context (CLI/noop/tests): the tool falls back
 * to formatting-only mode.
 */
public final class ClarifyStreamBridge {

    @FunctionalInterface
    public interface Sender {
        void sendClarifyPrompt(String payloadJson);
    }

    private static final ThreadLocal<Sender> SENDER = new ThreadLocal<>();

    private ClarifyStreamBridge() {
    }

    /** Register the sender for the current agent-loop thread. */
    public static void setSender(Sender sender) {
        SENDER.set(sender);
    }

    /** Clear the sender (finally-block of the tool batch dispatch). */
    public static void clear() {
        SENDER.remove();
    }

    /** Currently registered sender or null (non-streaming context). */
    public static Sender sender() {
        return SENDER.get();
    }

    /** Build the {@code clarify} SSE event carrying the prompt payload. */
    public static StreamEvent clarifyEvent(String payloadJson) {
        return new StreamEvent("clarify", null, null, payloadJson,
            null, null, null, "clarify", null, null);
    }
}
