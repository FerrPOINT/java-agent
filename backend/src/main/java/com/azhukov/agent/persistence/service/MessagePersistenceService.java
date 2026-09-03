package com.azhukov.agent.persistence.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final SessionRepository sessionRepository;
    private final MessageMapper messageMapper;

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
            Map<String, String> toolNamesByCallId = ToolResultNameResolver.collect(turnResult.messages());
            for (Message msg : turnResult.messages()) {
                if (msg.role() == Role.ASSISTANT) {
                    if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()
                        || msg.content() != null && !msg.content().isBlank()) {
                        saveMessage(session.id(), msg, toolNamesByCallId);
                    }
                } else if (msg.role() == Role.TOOL) {
                    saveMessage(session.id(), msg, toolNamesByCallId);
                }
            }
        }
        log.debug("Persisted turn for session {}: user input + {} turn messages",
            session.id(), turnResult != null ? turnResult.messages().size() : 0);
    }

    private void saveMessage(UUID sessionId, String role, String content,
                             String toolCallId, String toolCallName,
                             String toolCallArguments, int turnIndex) {
        // Deleted-session guard: skip silently when the session row is gone
        // (deleted mid-turn race) — mirrors MidTurnPersistenceService.
        if (!sessionRepository.existsById(sessionId)) {
            log.debug("saveMessage skipped: session {} no longer exists", sessionId);
            return;
        }
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
        // Update session stats (message_count, last_active, preview) so
        // session_search browse mode shows meaningful data instead of zeros.
        updateSessionStats(sessionId, role, content);
    }

    private void saveMessage(UUID sessionId, Message message, Map<String, String> toolNamesByCallId) {
        if (!sessionRepository.existsById(sessionId)) {
            log.debug("saveMessage skipped: session {} no longer exists", sessionId);
            return;
        }
        MessageEntity entity = messageMapper.toEntity(message);
        ToolResultNameResolver.apply(entity, message, toolNamesByCallId);
        entity.setSessionId(sessionId);
        entity.setCreatedAt(Instant.now());
        entity.setActive(true);
        entity.setCompacted(false);
        messageRepository.save(entity);
        updateSessionStats(sessionId, entity.getRole(), entity.getContent());
    }

    private void updateSessionStats(UUID sessionId, String role, String content) {
        try {
            long count = messageRepository.countBySessionId(sessionId);
            String preview = "";
            if (content != null && !content.isBlank()) {
                // Use first 200 chars of user messages as preview
                if ("user".equals(role)) {
                    preview = content.length() > 200 ? content.substring(0, 197) + "..." : content;
                }
            }
            sessionRepository.updateLastActiveAndMessageCount(sessionId, Instant.now(), (int) count);
            if (!preview.isEmpty()) {
                sessionRepository.updatePreview(sessionId, preview);
            }
        } catch (Exception e) {
            log.debug("Failed to update session stats for {}: {}", sessionId, e.getMessage());
        }
    }

    // Fully-qualified UUID import for saveMessage signature
    // (java.util.UUID is already imported at top via Session.id())
}
