package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.MessageDto;
import com.azhukov.agent.api.dto.MessageListDto;
import com.azhukov.agent.api.dto.SessionDeletedDto;
import com.azhukov.agent.api.dto.SessionDto;
import com.azhukov.agent.api.dto.SessionListDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for session CRUD operations. Wraps all repository access so the
 * {@link com.azhukov.agent.api.SessionCrudController} never touches repositories
 * or entities directly. Performs entity→DTO mapping and returns typed DTOs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionCrudService {

    private static final String DEFAULT_USER_ID = "user-1";
    private static final String DEFAULT_TITLE = "New chat";
    private static final String SOURCE = "api_server";

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AgentSessionResolver sessionResolver;
    private final SessionEntityMapper sessionEntityMapper;
    private final DomainDtoMapper domainDtoMapper;
    private final AgentProperties properties;

    // ── List sessions ──

    @Transactional(readOnly = true)
    public SessionListDto listSessions(int limit, int offset, String userId) {
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        int cappedOffset = Math.max(offset, 0);
        String effectiveUserId = userId != null ? userId : DEFAULT_USER_ID;

        List<SessionSummaryDto> sessions = sessionRepository
            .findAllByUserId(effectiveUserId, PageRequest.of(cappedOffset / cappedLimit, cappedLimit))
            .stream()
            .map(sessionEntityMapper::toDomain)
            .map(domainDtoMapper::toSessionSummaryDto)
            .toList();

        // M15: Use count query for reliable has_more instead of size==limit heuristic.
        long totalCount = sessionRepository.countByUserId(effectiveUserId);
        boolean hasMore = (cappedOffset + sessions.size()) < totalCount;

        return new SessionListDto("list", sessions, cappedLimit, cappedOffset, hasMore);
    }

    // ── Create session ──

    @Transactional
    public SessionDto createSession(String userId, String model, String title) {
        String effectiveUserId = userId != null ? userId : DEFAULT_USER_ID;
        String effectiveModel = (model != null && !model.isBlank()) ? model : properties.getModel().getModelName();
        String effectiveTitle = title != null ? title : DEFAULT_TITLE;

        Session session = sessionResolver.createSession(effectiveUserId, "openai-compatible", effectiveModel);

        // Set title if provided (resolver defaults to "New chat")
        if (title != null) {
            sessionRepository.findById(session.id()).ifPresent(e -> {
                e.setTitle(effectiveTitle);
                e.setUpdatedAt(Instant.now());
                sessionRepository.save(e);
            });
        }

        return toSessionDto(session.id(), effectiveUserId, effectiveTitle, effectiveModel);
    }

    // ── Get session ──

    @Transactional(readOnly = true)
    public Optional<SessionDto> getSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
            .map(e -> toSessionDto(e.getId(), e.getUserId(), e.getTitle(), e.getModelName()));
    }

    // ── Update session ──

    @Transactional
    public Optional<SessionDto> updateSession(UUID sessionId, String title) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return Optional.empty();
        }
        if (title != null) {
            entity.setTitle(title);
        }
        entity.setUpdatedAt(Instant.now());
        sessionRepository.save(entity);
        return Optional.of(toSessionDto(entity.getId(), entity.getUserId(), entity.getTitle(), entity.getModelName()));
    }

    // ── Delete session ──

    @Transactional
    public Optional<SessionDeletedDto> deleteSession(UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return Optional.empty();
        }
        // Delete messages first
        messageRepository.deleteAll(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
        sessionRepository.delete(entity);
        return Optional.of(new SessionDeletedDto("session.deleted", sessionId.toString(), true));
    }

    // ── Get session messages ──

    @Transactional(readOnly = true)
    public Optional<MessageListDto> getSessionMessages(UUID sessionId, int limit, int offset) {
        if (!sessionRepository.existsById(sessionId)) {
            return Optional.empty();
        }
        int cappedLimit = Math.min(Math.max(limit, 1), 500);
        // M16: Use Pageable repository method instead of loading ALL messages into memory.
        int cappedOffset = Math.max(offset, 0);
        int page = cappedOffset / cappedLimit;
        List<MessageEntity> messages = messageRepository
            .findBySessionIdOrderByCreatedAtAsc(sessionId, PageRequest.of(page, cappedLimit))
            .getContent();

        List<MessageDto> data = messages.stream()
            .map(SessionCrudService::toMessageDto)
            .toList();

        return Optional.of(new MessageListDto("list", sessionId.toString(), data, cappedLimit, cappedOffset));
    }

    // ── Existence check (for chat endpoints) ──

    @Transactional(readOnly = true)
    public boolean sessionExists(UUID sessionId) {
        return sessionRepository.existsById(sessionId);
    }

    // ── Entity → DTO mapping ──

    private static SessionDto toSessionDto(UUID id, String userId, String title, String model) {
        return new SessionDto(
            "session",
            id.toString(),
            userId,
            title != null ? title : DEFAULT_TITLE,
            model != null ? model : "",
            SOURCE
        );
    }

    private static MessageDto toMessageDto(MessageEntity msg) {
        return new MessageDto(
            msg.getId().toString(),
            msg.getSessionId().toString(),
            msg.getRole(),
            msg.getContent(),
            msg.getToolCallName(),
            msg.getToolCallId(),
            msg.getTurnIndex(),
            msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null
        );
    }
}