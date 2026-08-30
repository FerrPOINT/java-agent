package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.MessageDto;
import com.azhukov.agent.api.dto.MessageListDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.agent.SessionDeletedEvent;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Encapsulates session query and mutation logic that was previously embedded
 * in {@link com.azhukov.agent.api.SessionCrudController}.
 * <p>
 * The controller delegates here instead of touching repositories and entities
 * directly, preserving the layered architecture (controller → service → repository).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionQueryService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AgentSessionResolver sessionResolver;
    private final DomainDtoMapper domainDtoMapper;
    private final AgentProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    // ── Session listing ──

    /**
     * List sessions for a user with pagination.
     *
     * @return a map with "data" (list of SessionSummaryDto), "limit", "offset", "has_more"
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listSessions(int limit, int offset, String userId) {
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        int cappedOffset = Math.max(offset, 0);
        String effectiveUserId = userId != null ? userId : AgentProperties.DEFAULT_USER_ID;

        Page<SessionEntity> page = sessionRepository.findAllByUserId(effectiveUserId,
            new OffsetPageRequest(cappedLimit, cappedOffset, Sort.by(Sort.Direction.DESC, "updatedAt")));
        List<SessionSummaryDto> sessions = page
            .stream()
            .map(domainDtoMapper::toSessionSummaryDto)
            .toList();

        long totalCount = page.getTotalElements();
        boolean hasMore = (cappedOffset + sessions.size()) < totalCount;

        return Map.of(
            "object", "list",
            "data", sessions,
            "limit", cappedLimit,
            "offset", cappedOffset,
            "has_more", hasMore
        );
    }

    private record OffsetPageRequest(int pageSize, long offset, Sort sort) implements Pageable {
        @Override
        public Sort getSort() {
            return sort;
        }

        @Override
        public int getPageSize() {
            return pageSize;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public int getPageNumber() {
            return (int) (offset / pageSize);
        }

        @Override
        public Pageable next() {
            return new OffsetPageRequest(pageSize, offset + pageSize, sort);
        }

        @Override
        public Pageable previousOrFirst() {
            return hasPrevious() ? new OffsetPageRequest(pageSize, Math.max(0, offset - pageSize), sort) : first();
        }

        @Override
        public Pageable first() {
            return new OffsetPageRequest(pageSize, 0, sort);
        }

        @Override
        public Pageable withPage(int pageNumber) {
            return new OffsetPageRequest(pageSize, Math.multiplyExact((long) pageNumber, pageSize), sort);
        }

        @Override
        public boolean hasPrevious() {
            return offset > 0;
        }
    }

    // ── Session creation ──

    /**
     * Create a new session.
     *
     * @return a response map with session fields and the created session ID
     */
    @Transactional
    public Map<String, Object> createSession(String userId, String model, String title) {
        String effectiveUserId = userId != null ? userId : AgentProperties.DEFAULT_USER_ID;
        String effectiveModel = model != null && !model.isBlank()
            ? model : properties.getModel().getModelName();
        String effectiveTitle = title != null ? title : "New chat";

        Session session = sessionResolver.createSession(effectiveUserId, "openai-compatible", effectiveModel);

        if (title != null) {
            sessionRepository.findById(session.id()).ifPresent(e -> {
                e.setTitle(effectiveTitle);
                e.setUpdatedAt(Instant.now());
                sessionRepository.save(e);
            });
        }

        return toSessionResponse(session.id(), effectiveUserId, effectiveTitle, effectiveModel);
    }

    // ── Get session by ID ──

    /**
     * Get a session by ID.
     *
     * @return Optional containing the response map, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getSession(UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(toSessionResponse(
            entity.getId(), entity.getUserId(), entity.getTitle(), entity.getModelName()));
    }

    // ── Update session ──

    /**
     * Update session metadata (title).
     *
     * @return Optional containing the response map, or empty if not found
     */
    @Transactional
    public Optional<Map<String, Object>> updateSession(UUID sessionId, String title) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return Optional.empty();
        }
        if (title != null) {
            entity.setTitle(title);
        }
        entity.setUpdatedAt(Instant.now());
        sessionRepository.save(entity);

        return Optional.of(toSessionResponse(
            entity.getId(), entity.getUserId(), entity.getTitle(), entity.getModelName()));
    }

    // ── Delete session ──

    /**
     * Delete a session and all its messages.
     *
     * @return true if deleted, false if not found
     */
    @Transactional
    public boolean deleteSession(UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return false;
        }
        messageRepository.deleteAll(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
        sessionRepository.delete(entity);
        eventPublisher.publishEvent(new SessionDeletedEvent(sessionId));
        return true;
    }

    // ── Session messages ──

    /**
     * List messages in a session with pagination.
     *
     * @return Optional containing the message list envelope, or empty if session not found
     */
    @Transactional(readOnly = true)
    public Optional<MessageListDto> getSessionMessages(UUID sessionId, int limit, int offset) {
        if (!sessionRepository.existsById(sessionId)) {
            return Optional.empty();
        }
        int cappedLimit = Math.min(Math.max(limit, 1), 500);
        int cappedOffset = Math.max(offset, 0);
        int page = cappedOffset / cappedLimit;

        List<MessageEntity> messages = messageRepository
            .findBySessionIdOrderByCreatedAtAsc(sessionId,
                PageRequest.of(page, cappedLimit))
            .getContent();

        List<MessageDto> data = messages.stream()
            .map(SessionQueryService::toMessageDto)
            .toList();

        return Optional.of(new MessageListDto(
            "list",
            sessionId.toString(),
            data,
            cappedLimit,
            cappedOffset
        ));
    }

    // ── Session existence check ──

    @Transactional(readOnly = true)
    public boolean sessionExists(UUID sessionId) {
        return sessionRepository.existsById(sessionId);
    }

    // ── Helpers ──

    private static Map<String, Object> toSessionResponse(UUID id, String userId, String title, String model) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "session");
        response.put("id", id.toString());
        response.put("user_id", userId);
        response.put("title", title != null ? title : "New chat");
        response.put("model", model != null ? model : "");
        response.put("source", "api_server");
        return response;
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