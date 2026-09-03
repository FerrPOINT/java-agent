package com.azhukov.agent.persistence.service;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.mapper.ToolCallPersistenceCodec;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
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

    private static final int PREVIEW_MAX_CHARS = 200;
    private static final int PREVIEW_TRUNCATE_AT = 197;

    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final com.azhukov.agent.persistence.mapper.MessageMapper messageMapper;

    /**
     * P1-5: Persist only the user message before a turn starts.
     * Used when mid-turn persistence is active — the DefaultAgentRuntime
     * will persist assistant + tool messages during the turn via
     * MidTurnPersistenceCallback.
     */
    @Transactional
    public void persistUserMessage(Session session, String userInput) {
        saveMessage(session.id(), Role.USER.name().toLowerCase(), userInput, null, null, null, 0);
    }

    @Transactional
    public void persistTurn(Session session, String userInput, TurnResult turnResult) {
        // Save user message
        saveMessage(session.id(), Role.USER.name().toLowerCase(), userInput, null, null, null, 0);

        // Save assistant messages from the turn (only final text + tool interactions)
        if (turnResult != null && turnResult.messages() != null) {
            for (Message msg : turnResult.messages()) {
                if (msg.role() == Role.ASSISTANT) {
                    List<ToolCall> calls = msg.toolCalls() != null && !msg.toolCalls().isEmpty()
                        ? msg.toolCalls()
                        : msg.toolCall() == null ? List.of() : List.of(msg.toolCall());
                    if ((msg.content() != null && !msg.content().isBlank()) || !calls.isEmpty()) {
                        saveAssistantMessage(session.id(), msg.content(), calls,
                            msg.turnIndex() != null ? msg.turnIndex() : 0);
                    }
                } else if (msg.role() == Role.TOOL) {
                    // Backfill the tool name for the result row: strict providers
                    // and session_search surface tool_result rows with the tool
                    // name; the id alone is opaque. Resolution follows Hermes
                    // tool_call pairing (assistant call earlier in this turn).
                    String toolName = resolveToolName(turnResult.messages(), msg.toolCallId());
                    saveMessage(session.id(), Role.TOOL.name().toLowerCase(), msg.content(),
                        msg.toolCallId(), toolName, null,
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

    /** Persist one assistant row per model response, including the entire tool-call batch. */
private String resolveToolName(List<Message> messages, String toolCallId) {
        if (toolCallId == null) {
            return null;
        }
        for (Message m : messages) {
            if (m.role() != Role.ASSISTANT) {
                continue;
            }
            List<ToolCall> calls = m.toolCalls() != null ? m.toolCalls()
                : m.toolCall() != null ? List.of(m.toolCall()) : List.of();
            for (ToolCall tc : calls) {
                if (tc.idVariants().contains(toolCallId) || tc.pairingId().equals(toolCallId)) {
                    return tc.name();
                }
            }
        }
        return null;
    }

    private void saveAssistantMessage(UUID sessionId, String content, List<ToolCall> calls, int turnIndex) {
        if (!sessionRepository.existsById(sessionId)) {
            log.debug("saveAssistantMessage skipped: session {} no longer exists", sessionId);
            return;
        }
        MessageEntity entity = new MessageEntity();
        entity.setSessionId(sessionId);
        entity.setRole(Role.ASSISTANT.name().toLowerCase());
        entity.setContent(content != null ? content : "");
        entity.setTurnIndex(turnIndex);
        entity.setCreatedAt(Instant.now());
        entity.setActive(true);
        entity.setCompacted(false);
        if (calls != null && !calls.isEmpty()) {
            ToolCall first = calls.get(0);
            entity.setToolCallId(first.pairingId());
            entity.setToolCallName(first.name());
            entity.setToolCallArguments(first.arguments());
            entity.setToolResponseItemId(first.responseItemId());
            entity.setToolCallsJson(ToolCallPersistenceCodec.serialize(calls));
        }
        messageRepository.save(entity);
        updateSessionStats(sessionId, entity.getRole(), entity.getContent());
    }

    private void updateSessionStats(UUID sessionId, String role, String content) {
        try {
            long count = messageRepository.countBySessionId(sessionId);
            String preview = "";
            if (content != null && !content.isBlank()) {
                // Use first 200 chars of user messages as preview
                if (Role.USER.name().toLowerCase().equals(role)) {
                    preview = content.length() > PREVIEW_MAX_CHARS ? content.substring(0, PREVIEW_TRUNCATE_AT) + "..." : content;
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