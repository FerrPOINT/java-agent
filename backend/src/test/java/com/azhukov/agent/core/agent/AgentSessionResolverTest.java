package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSessionResolverTest {

    private SessionRepository sessionRepository;
    private SessionEntityMapper sessionMapper;
    private TransactionTemplate transactionTemplate;
    private MessageRepository messageRepository;
    private SessionLineageService sessionLineageService;
    private AgentSessionResolver resolver;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        sessionMapper = org.mapstruct.factory.Mappers.getMapper(SessionEntityMapper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        messageRepository = mock(MessageRepository.class);
        sessionLineageService = mock(SessionLineageService.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        // Default: lineage service returns single-session list (no ancestors)
        when(sessionLineageService.findAncestorSessionIds(any())).thenAnswer(inv ->
            java.util.List.of((UUID) inv.getArgument(0)));
        when(sessionLineageService.hasParentSession(any())).thenReturn(false);
        when(sessionLineageService.loadMessagesWithAncestors(any())).thenReturn(java.util.Collections.emptyList());
        resolver = new AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate, messageRepository, sessionLineageService);
    }

    @Test
    void resolveOrCreate_withNullSessionId_createsNewSession() {
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        AgentSessionResolver.ResolvedSession result = resolver.resolveOrCreate(null, "user-1", "gpt-4");

        assertThat(result.isNew()).isTrue();
        assertThat(result.session().userId()).isEqualTo("user-1");
        assertThat(result.session().modelName()).isEqualTo("gpt-4");
    }

    @Test
    void resolveOrCreate_withExistingSessionId_loadsSession() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId("user-1");
        entity.setModelProvider("openai-compatible");
        entity.setModelName("test-model");
        entity.setTitle("Test");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));
        when(messageRepository.countBySessionId(sessionId)).thenReturn(1L);

        AgentSessionResolver.ResolvedSession result = resolver.resolveOrCreate(sessionId, "user-1", "gpt-4");

        assertThat(result.isNew()).isFalse();
        assertThat(result.session().id()).isEqualTo(sessionId);
        assertThat(result.session().modelName()).isEqualTo("test-model");
    }

    @Test
    void resolveOrCreate_withUnknownSessionId_createsNewSession() {
        UUID unknownId = UUID.randomUUID();
        when(sessionRepository.findById(unknownId)).thenReturn(Optional.empty());
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        AgentSessionResolver.ResolvedSession result = resolver.resolveOrCreate(unknownId, "user-1", "gpt-4");

        assertThat(result.isNew()).isTrue();
        assertThat(result.session().userId()).isEqualTo("user-1");
    }

    @Test
    void loadSession_hydratesCliStateIntoMetadata() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId("user-1");
        entity.setModelProvider("openai-compatible");
        entity.setModelName("model");
        entity.setTitle("Test");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setCliStateValue("goal", "fix bugs");
        entity.setCliStateValue("subgoals", "bug1\nbug2");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        Session session = resolver.loadSession(sessionId);

        assertThat(session.getMetadata("goal")).isEqualTo("fix bugs");
        assertThat(session.getMetadata("subgoals")).isEqualTo("bug1\nbug2");
    }

    @Test
    void loadSession_hydratesSubgoalIntoMetadata() {
        UUID sessionId = UUID.randomUUID();
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId("user-1");
        entity.setModelProvider("openai-compatible");
        entity.setModelName("model");
        entity.setTitle("Test");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setSubgoal("Complete the migration");
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(entity));

        Session session = resolver.loadSession(sessionId);

        assertThat(session.getMetadata("subgoal")).isEqualTo("Complete the migration");
    }

    @Test
    void loadSession_throwsWhenNotFound() {
        UUID missingId = UUID.randomUUID();
        when(sessionRepository.findById(missingId)).thenReturn(Optional.empty());

        try {
            resolver.loadSession(missingId);
            org.junit.jupiter.api.Assertions.fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Session not found");
        }
    }

    // ── resolveResumeSessionId tests ──

    @Test
    void resolveResumeSessionId_returnsSelf_whenSessionHasMessages() {
        UUID sessionId = UUID.randomUUID();
        when(messageRepository.countBySessionId(sessionId)).thenReturn(1L);

        UUID result = resolver.resolveResumeSessionId(sessionId);

        assertThat(result).isEqualTo(sessionId);
    }

    @Test
    void resolveResumeSessionId_returnsChild_whenParentHasNoMessages() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SessionEntity childEntity = new SessionEntity();
        childEntity.setId(childId);

        when(messageRepository.countBySessionId(parentId)).thenReturn(0L);
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(parentId))
            .thenReturn(List.of(childEntity));
        when(messageRepository.countBySessionId(childId)).thenReturn(1L);

        UUID result = resolver.resolveResumeSessionId(parentId);

        assertThat(result).isEqualTo(childId);
    }

    @Test
    void resolveResumeSessionId_returnsSelf_whenNoDescendantHasMessages() {
        UUID sessionId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        SessionEntity childEntity = new SessionEntity();
        childEntity.setId(childId);

        when(messageRepository.countBySessionId(sessionId)).thenReturn(0L);
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(sessionId))
            .thenReturn(List.of(childEntity));
        when(messageRepository.countBySessionId(childId)).thenReturn(0L);
        // child has no messages and no further children
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(childId))
            .thenReturn(List.of());

        UUID result = resolver.resolveResumeSessionId(sessionId);

        assertThat(result).isEqualTo(sessionId);
    }

    @Test
    void resolveResumeSessionId_returnsNull_forNullId() {
        UUID result = resolver.resolveResumeSessionId(null);

        assertThat(result).isNull();
    }

    // ── Lineage delegate methods ──

    @Test
    void findAncestorSessionIds_delegatesToLineageService() {
        UUID sessionId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(sessionLineageService.findAncestorSessionIds(sessionId))
            .thenReturn(List.of(parentId, sessionId));

        List<UUID> result = resolver.findAncestorSessionIds(sessionId);

        assertThat(result).containsExactly(parentId, sessionId);
    }

    @Test
    void hasParentSession_delegatesToLineageService() {
        UUID sessionId = UUID.randomUUID();
        when(sessionLineageService.hasParentSession(sessionId)).thenReturn(true);

        assertThat(resolver.hasParentSession(sessionId)).isTrue();
    }

    @Test
    void loadMessagesWithAncestors_delegatesToLineageService() {
        UUID sessionId = UUID.randomUUID();
        when(sessionLineageService.loadMessagesWithAncestors(sessionId))
            .thenReturn(List.of(Message.user("test")));

        List<com.azhukov.agent.core.model.Message> result = resolver.loadMessagesWithAncestors(sessionId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("test");
    }
}