package com.azhukov.agent.core.agent;

import java.util.UUID;

/**
 * Callback interface for emitting interim "commentary" messages to the user.
 * <p>
 * Mirrors Hermes' {@code _emit_interim_assistant_message()} pattern:
 * when the LLM returns BOTH visible text AND tool calls, the text is
 * "commentary" — an interim assistant message shown to the user before
 * tool execution begins. This is distinct from the final response
 * (text only, no tool calls) and from silent tool execution (tool calls
 * only, no text).
 * <p>
 * The {@code alreadyStreamed} flag indicates whether the text was already
 * shown to the user via streaming. If true, the gateway should issue a
 * segment break (visual separator) rather than re-sending the text as a
 * new message. If false (non-streaming path), the text should be sent
 * as a new message.
 *
 * @see <a href="https://hermes-agent.nousresearch.com/docs">Hermes _emit_interim_assistant_message</a>
 */
public interface CommentaryCallback {

    /**
     * Called when the LLM produces visible text alongside tool calls.
     *
     * @param sessionId       the session UUID
     * @param text            the commentary text (visible assistant content)
     * @param alreadyStreamed whether the text was already shown via streaming;
     *                        if true, the receiver should issue a segment break,
     *                        not a duplicate message
     */
    void onCommentary(UUID sessionId, String text, boolean alreadyStreamed);
}