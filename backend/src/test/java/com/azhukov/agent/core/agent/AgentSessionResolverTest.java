package com.azhukov.agent.core.agent;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
    private AgentSessionResolver resolver;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        sessionMapper = org.mapstruct.factory.Mappers.getMapper(SessionEntityMapper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        resolver = new AgentSessionResolver(sessionRepository, sessionMapper, transactionTemplate);
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
}