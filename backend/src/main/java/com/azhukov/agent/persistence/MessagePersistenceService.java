package com.azhukov.agent.persistence;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persists user input and assistant response messages to the database
 * so that {@link com.azhukov.agent.core.context.ContextEngine} can load
 * conversation history on subsequent turns.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessagePersistenceService {

    private final MessageRepository messageRepository;

    /**
     * P1-5: Persist only the user message before a turn starts.
     * Used when mid-turn persistence is active — the DefaultAgentRuntime
     * will persist assistant + tool messages during the turn via
     * MidTurnPersistenceCallback.
     */
    @Transactional
    public void persistUserMessage(Session session, String userInput) {
        saveMessage(session.id(), "user", userInput, null, null, null, 0);
    }

    @Transactional
    public void persistTurn(Session session, String userInput, TurnResult turnResult) {
        // Save user message
        saveMessage(session.id(), "user", userInput, null, null, null, 0);

        // Save assistant messages from the turn (only final text + tool interactions)
        if (turnResult != null && turnResult.messages() != null) {
            for (Message msg : turnResult.messages()) {
                if (msg.role() == Role.ASSISTANT) {
                    // Save assistant text (skip null-content tool-call-only messages)
                    if (msg.content() != null && !msg.content().isBlank()) {
                        saveMessage(session.id(), "assistant", msg.content(),
                            null, null, null, msg.turnIndex() != null ? msg.turnIndex() : 0);
                    }
                    // Save tool calls if present
                    if (msg.toolCalls() != null) {
                        for (var tc : msg.toolCalls()) {
                            saveMessage(session.id(), "assistant", msg.content() != null ? msg.content() : "",
                                tc.id(), tc.name(), tc.arguments(),
                                msg.turnIndex() != null ? msg.turnIndex() : 0);
                        }
                    }
                } else if (msg.role() == Role.TOOL) {
                    saveMessage(session.id(), "tool", msg.content(),
                        msg.toolCallId(), null, null,
                        msg.turnIndex() != null ? msg.turnIndex() : 0);
                }
            }
        }
        log.debug("Persisted turn for session {}: user input + {} turn messages",
            session.id(), turnResult != null ? turnResult.messages().size() : 0);
    }

    private void saveMessage(UUID sessionId, String role, String content,
                             String toolCallId, String toolCallName,
                             String toolCallArguments, int turnIndex) {
        MessageEntity entity = new MessageEntity();
        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setToolCallId(toolCallId);
        entity.setToolCallName(toolCallName);
        entity.setToolCallArguments(toolCallArguments);
        entity.setTurnIndex(turnIndex);
        entity.setCreatedAt(Instant.now());
        entity.setActive(true);
        entity.setCompacted(false);
        messageRepository.save(entity);
    }

    // Fully-qualified UUID import for saveMessage signature
    // (java.util.UUID is already imported at top via Session.id())
}