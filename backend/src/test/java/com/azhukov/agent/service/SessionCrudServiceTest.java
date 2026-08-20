package com.azhukov.agent.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionCrudServiceTest {

    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private AgentSessionResolver sessionResolver;
    private AgentProperties properties;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private SessionCrudService sessionCrudService;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        sessionResolver = mock(AgentSessionResolver.class);
        properties = mock(AgentProperties.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        AgentProperties.ModelProperties modelProps = mock(AgentProperties.ModelProperties.class);
        when(modelProps.getModelName()).thenReturn("default-model");
        when(properties.getModel()).thenReturn(modelProps);

        sessionCrudService = new SessionCrudService(
            sessionRepository,
            messageRepository,
            sessionResolver,
            Mappers.getMapper(SessionEntityMapper.class),
            Mappers.getMapper(DomainDtoMapper.class),
            properties,
            eventPublisher
        );
    }

    // ── listSessions ──

    @Test
    void listSessionsReturnsPaginatedDtos() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        SessionEntity e1 = sessionEntity(id1, "u1", "S1", "openai-compatible", "gpt-4");
        SessionEntity e2 = sessionEntity(id2, "u1", "S2", "openai-compatible", "gpt-4");

        when(sessionRepository.findAllByUserId(eq("u1"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(e1, e2)));
        when(sessionRepository.countByUserId("u1")).thenReturn(5L);

        SessionListDto result = sessionCrudService.listSessions(10, 0, "u1");

        assertThat(result.object()).isEqualTo("list");
        assertThat(result.limit()).isEqualTo(10);
        assertThat(result.offset()).isZero();
        assertThat(result.hasMore()).isTrue();
        assertThat(result.data()).hasSize(2);
        assertThat(result.data()).extracting("id").containsExactly(id1, id2);
    }

    @Test
    void listSessionsDefaultsUserIdWhenNull() {
        when(sessionRepository.findAllByUserId(eq("user-1"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(sessionRepository.countByUserId("user-1")).thenReturn(0L);

        SessionListDto result = sessionCrudService.listSessions(50, 0, null);

        assertThat(result.data()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void listSessionsCapsLimitAndOffset() {
        when(sessionRepository.findAllByUserId(any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(sessionRepository.countByUserId(any())).thenReturn(0L);

        SessionListDto result = sessionCrudService.listSessions(10000, -5, "u1");

        assertThat(result.limit()).isEqualTo(200);
        assertThat(result.offset()).isZero();
    }

    // ── createSession ──

    @Test
    void createSessionDelegatesToResolverAndSetsTitle() {
        UUID sessionId = UUID.randomUUID();
        Session created = new Session(sessionId, "u1", "New chat", "openai-compatible", "gpt-4", null, java.util.Map.of());
        when(sessionResolver.createSession("u1", "openai-compatible", "gpt-4")).thenReturn(created);

        SessionEntity saved = sessionEntity(sessionId, "u1", "New chat", "openai-compatible", "gpt-4");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(saved));

        SessionDto result = sessionCrudService.createSession("u1", "gpt-4", "My Title");

        assertThat(result.object()).isEqualTo("session");
        assertThat(result.id()).isEqualTo(sessionId.toString());
        assertThat(result.userId()).isEqualTo("u1");
        assertThat(result.model()).isEqualTo("gpt-4");
        assertThat(result.source()).isEqualTo("api_server");
        // title provided → resolver's persisted entity should be updated
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("My Title");
    }

    @Test
    void createSessionUsesDefaultModelWhenBlank() {
        UUID sessionId = UUID.randomUUID();
        Session created = new Session(sessionId, "user-1", "New chat", "openai-compatible", "default-model", null, java.util.Map.of());
        when(sessionResolver.createSession("user-1", "openai-compatible", "default-model")).thenReturn(created);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        SessionDto result = sessionCrudService.createSession(null, "  ", null);

        assertThat(result.model()).isEqualTo("default-model");
        assertThat(result.userId()).isEqualTo("user-1");
        assertThat(result.title()).isEqualTo("New chat");
    }

    @Test
    void createSessionWithoutTitleSkipsRepositoryUpdate() {
        UUID sessionId = UUID.randomUUID();
        Session created = new Session(sessionId, "u1", "New chat", "openai-compatible", "gpt-4", null, java.util.Map.of());
        when(sessionResolver.createSession("u1", "openai-compatible", "gpt-4")).thenReturn(created);

        SessionDto result = sessionCrudService.createSession("u1", "gpt-4", null);

        assertThat(result.title()).isEqualTo("New chat");
        verify(sessionRepository, never()).save(any());
    }

    // ── getSession ──

    @Test
    void getSessionReturnsDtoWhenFound() {
        UUID id = UUID.randomUUID();
        SessionEntity entity = sessionEntity(id, "u1", "Title", "openai-compatible", "gpt-4");
        when(sessionRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<SessionDto> result = sessionCrudService.getSession(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id.toString());
        assertThat(result.get().title()).isEqualTo("Title");
    }

    @Test
    void getSessionReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(sessionCrudService.getSession(id)).isEmpty();
    }

    // ── updateSession ──

    @Test
    void updateSessionUpdatesTitleAndReturnsDto() {
        UUID id = UUID.randomUUID();
        SessionEntity entity = sessionEntity(id, "u1", "Old", "openai-compatible", "gpt-4");
        when(sessionRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<SessionDto> result = sessionCrudService.updateSession(id, "New");

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("New");
        verify(sessionRepository).save(entity);
        assertThat(entity.getTitle()).isEqualTo("New");
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateSessionReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(sessionCrudService.updateSession(id, "New")).isEmpty();
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void updateSessionWithNullTitleKeepsExisting() {
        UUID id = UUID.randomUUID();
        SessionEntity entity = sessionEntity(id, "u1", "Keep", "openai-compatible", "gpt-4");
        when(sessionRepository.findById(id)).thenReturn(Optional.of(entity));

        Optional<SessionDto> result = sessionCrudService.updateSession(id, null);

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Keep");
    }

    // ── deleteSession ──

    @Test
    void deleteSessionRemovesMessagesAndSession() {
        UUID id = UUID.randomUUID();
        SessionEntity entity = sessionEntity(id, "u1", "T", "openai-compatible", "gpt-4");
        when(sessionRepository.findById(id)).thenReturn(Optional.of(entity));
        List<MessageEntity> msgs = List.of(messageEntity(UUID.randomUUID(), id, "user", "hi"));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(id)).thenReturn(msgs);

        Optional<SessionDeletedDto> result = sessionCrudService.deleteSession(id);

        assertThat(result).isPresent();
        assertThat(result.get().object()).isEqualTo("session.deleted");
        assertThat(result.get().id()).isEqualTo(id.toString());
        assertThat(result.get().deleted()).isTrue();
        verify(messageRepository).deleteAll(msgs);
        verify(sessionRepository).delete(entity);
        // C3 regression: the service path must evict per-session runtime state too
        verify(eventPublisher).publishEvent(any(com.azhukov.agent.core.agent.SessionDeletedEvent.class));
    }

    @Test
    void deleteSessionReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(sessionRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(sessionCrudService.deleteSession(id)).isEmpty();
    }

    // ── getSessionMessages ──

    @Test
    void getSessionMessagesReturnsPaginatedDtos() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.existsById(sessionId)).thenReturn(true);
        MessageEntity m1 = messageEntity(UUID.randomUUID(), sessionId, "user", "hello");
        m1.setCreatedAt(Instant.now());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(eq(sessionId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(m1), PageRequest.of(0, 100), 1));

        Optional<MessageListDto> result = sessionCrudService.getSessionMessages(sessionId, 100, 0);

        assertThat(result).isPresent();
        MessageListDto dto = result.get();
        assertThat(dto.object()).isEqualTo("list");
        assertThat(dto.sessionId()).isEqualTo(sessionId.toString());
        assertThat(dto.limit()).isEqualTo(100);
        assertThat(dto.offset()).isZero();
        assertThat(dto.data()).hasSize(1);
        assertThat(dto.data().get(0).role()).isEqualTo("user");
        assertThat(dto.data().get(0).content()).isEqualTo("hello");
        assertThat(dto.data().get(0).timestamp()).isNotNull();
    }

    @Test
    void getSessionMessagesCapsLimit() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.existsById(sessionId)).thenReturn(true);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(eq(sessionId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 500), 0));

        Optional<MessageListDto> result = sessionCrudService.getSessionMessages(sessionId, 99999, 0);

        assertThat(result).isPresent();
        assertThat(result.get().limit()).isEqualTo(500);
    }

    @Test
    void getSessionMessagesReturnsEmptyWhenSessionNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.existsById(sessionId)).thenReturn(false);

        assertThat(sessionCrudService.getSessionMessages(sessionId, 100, 0)).isEmpty();
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void getSessionMessagesIncludesOptionalToolFields() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.existsById(sessionId)).thenReturn(true);
        MessageEntity m = messageEntity(UUID.randomUUID(), sessionId, "assistant", "result");
        m.setToolCallName("terminal");
        m.setToolCallId("call-1");
        m.setTurnIndex(3);
        m.setCreatedAt(Instant.now());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(eq(sessionId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(m), PageRequest.of(0, 100), 1));

        Optional<MessageListDto> result = sessionCrudService.getSessionMessages(sessionId, 100, 0);

        assertThat(result).isPresent();
        var dto = result.get().data().get(0);
        assertThat(dto.toolCallName()).isEqualTo("terminal");
        assertThat(dto.toolCallId()).isEqualTo("call-1");
        assertThat(dto.turnIndex()).isEqualTo(3);
    }

    // ── sessionExists ──

    @Test
    void sessionExistsDelegatesToRepository() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.existsById(sessionId)).thenReturn(true);

        assertThat(sessionCrudService.sessionExists(sessionId)).isTrue();
    }

    @Test
    void sessionExistsReturnsFalseWhenAbsent() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.existsById(sessionId)).thenReturn(false);

        assertThat(sessionCrudService.sessionExists(sessionId)).isFalse();
    }

    // ── helpers ──

    private static SessionEntity sessionEntity(UUID id, String userId, String title, String provider, String model) {
        SessionEntity e = new SessionEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setTitle(title);
        e.setModelProvider(provider);
        e.setModelName(model);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    private static MessageEntity messageEntity(UUID id, UUID sessionId, String role, String content) {
        MessageEntity m = new MessageEntity();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}