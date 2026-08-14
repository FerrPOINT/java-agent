package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;

import java.util.List;
import java.util.UUID;

/**
 * Callback interface for persisting messages mid-turn, after each tool batch.
 * <p>
 * Mirrors Hermes' {@code _persist_session} / {@code _flush_messages_to_session_db}
 * pattern: after every tool batch completes (assistant message with tool calls +
 * all tool results), the new messages are flushed to the database immediately.
 * If the JVM crashes mid-turn, all progress up to the last batch is preserved.
 * <p>
 * The implementation is responsible for tracking which messages have already
 * been persisted (e.g. via an index cursor) to avoid duplicate writes.
 */
public interface MidTurnPersistenceCallback {

    /**
     * Persist new messages that were added since the last flush.
     * <p>
     * Called after each tool batch: the assistant message (with tool calls) and
     * all tool result messages for that batch. Also called for the final
     * assistant message when the turn completes without tool calls.
     *
     * @param sessionId  the session UUID
     * @param messages   the full list of turn messages (system + user + assistant + tool)
     * @param fromIndex  index of the first new message to persist (exclusive: persist messages from this index onward)
     * @return true if persistence succeeded, false if it failed (caller should NOT advance cursor)
     */
    boolean persistNewMessages(UUID sessionId, List<Message> messages, int fromIndex);
}