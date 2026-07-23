package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AgentRuntimeService {

    private final AgentRuntime agentRuntime;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    public AgentRuntimeService(AgentRuntime agentRuntime,
                               SessionRepository sessionRepository,
                               MessageRepository messageRepository) {
        this.agentRuntime = agentRuntime;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public ChatResponseDto runDelegate(ChatRequest request) {
        int depth = request.delegationDepth() != null ? request.delegationDepth() : 0;
        Session session = createSession("user-1", "openai-compatible", "")
            .withMetadata("delegation_depth", String.valueOf(depth));

        saveUserMessage(session.id(), request.message());
        TurnResult result = agentRuntime.runTurn(session, request.message());
        persistMessages(session.id(), result.messages());

        return new ChatResponseDto(
            session.id(),
            result.finalText(),
            null,
            result.completed()
        );
    }

    @Transactional
    public ChatResponseDto runTurn(ChatRequest request) {
        boolean isNew = request.sessionId() == null;
        Session session = isNew
            ? createSession("user-1", "openai-compatible", "")
            : loadSession(request.sessionId());

        saveUserMessage(session.id(), request.message());
        TurnResult result = agentRuntime.runTurn(session, request.message());
        persistMessages(session.id(), result.messages());

        return new ChatResponseDto(
            session.id(),
            result.finalText(),
            null,
            result.completed()
        );
    }

    private Session createSession(String userId, String provider, String modelName) {
        SessionEntity e = new SessionEntity();
        e.setUserId(userId);
        e.setModelProvider(provider);
        e.setModelName(modelName);
        e.setTitle("New chat");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        SessionEntity saved = sessionRepository.save(e);
        return new Session(saved.getId(), saved.getUserId(), saved.getTitle(),
            saved.getModelProvider(), saved.getModelName(), null, java.util.Map.of());
    }

    private Session loadSession(UUID id) {
        SessionEntity e = sessionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        return new Session(e.getId(), e.getUserId(), e.getTitle(), e.getModelProvider(), e.getModelName(), null, java.util.Map.of());
    }

    private void saveUserMessage(UUID sessionId, String content) {
        MessageEntity e = new MessageEntity();
        e.setSessionId(sessionId);
        e.setRole(Role.USER.name().toLowerCase());
        e.setContent(content);
        e.setTurnIndex(0);
        e.setCreatedAt(Instant.now());
        messageRepository.save(e);
    }

    private void persistMessages(UUID sessionId, List<Message> messages) {
        Instant now = Instant.now();
        for (Message m : messages) {
            MessageEntity e = new MessageEntity();
            e.setSessionId(sessionId);
            e.setRole(m.role().name().toLowerCase());
            e.setContent(m.content());
            e.setToolCallId(m.toolCallId());
            if (m.toolCall() != null) {
                e.setToolCallName(m.toolCall().name());
                e.setToolCallArguments(m.toolCall().arguments());
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                e.setToolCallName(m.toolCalls().get(0).name());
                e.setToolCallArguments(m.toolCalls().get(0).arguments());
            }
            e.setCreatedAt(now);
            Integer turnIndex = m.turnIndex();
            e.setTurnIndex(turnIndex != null ? turnIndex : 0);
            messageRepository.save(e);
        }
    }
}
