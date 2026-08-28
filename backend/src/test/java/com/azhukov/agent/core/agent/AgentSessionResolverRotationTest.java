package com.azhukov.agent.core.agent;

import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression (live 2026-08-27): after a mid-turn compression rotation the bot
 * kept writing into the archived PARENT session while the compacted child was
 * ignored, duplicating the transcript. Root cause: resolveResumeSessionId
 * counted ALL message rows (countBySessionId) — rotation deactivates ancestor
 * rows (active=false) but the rows remain, so the parent still "had messages"
 * and the chain was never followed to the live child.
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionResolverRotationTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private AgentSessionResolver resolver;

    private final UUID parentId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new AgentSessionResolver(sessionRepository,
            Mappers.getMapper(SessionEntityMapper.class), transactionTemplate,
            messageRepository, org.mockito.Mockito.mock(SessionLineageService.class));
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    @Test
    void followsRotationChainWhenParentRowsAreArchived() {
        // Parent rows deactivated by rotation → countBySessionIdAndActiveTrue == 0
        when(messageRepository.countBySessionIdAndActiveTrue(parentId)).thenReturn(0L);
        SessionEntity child = new SessionEntity();
        child.setId(childId);
        child.setUserId("user-1");
        child.setModelProvider("openai-compatible");
        child.setModelName("app-test");
        child.setTitle("New chat (compressed)");
        child.setParentSessionId(parentId);
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(parentId))
            .thenReturn(List.of(child));
        when(sessionRepository.findById(childId)).thenReturn(java.util.Optional.of(child));
        when(messageRepository.countBySessionIdAndActiveTrue(childId)).thenReturn(5L);

        AgentSessionResolver.ResolvedSession resolved = resolver.resolveOrCreate(parentId, "user-1", "app-test");

        assertThat(resolved.isNew()).isFalse();
        assertThat(resolved.session().id()).isEqualTo(childId);
    }

    @Test
    void staysOnSessionWithActiveRows() {
        when(messageRepository.countBySessionIdAndActiveTrue(parentId)).thenReturn(7L);

        SessionEntity parent = new SessionEntity();
        parent.setId(parentId);
        parent.setUserId("user-1");
        parent.setModelProvider("openai-compatible");
        parent.setModelName("app-test");
        parent.setTitle("chat");
        when(sessionRepository.findById(parentId)).thenReturn(java.util.Optional.of(parent));

        AgentSessionResolver.ResolvedSession resolved = resolver.resolveOrCreate(parentId, "user-1", "app-test");

        assertThat(resolved.isNew()).isFalse();
        assertThat(resolved.session().id()).isEqualTo(parentId);
    }

    @Test
    void createsNewSessionWhenChainEndsEmpty() {
        when(messageRepository.countBySessionIdAndActiveTrue(parentId)).thenReturn(0L);
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(parentId))
            .thenReturn(List.of());
        SessionEntity fresh = new SessionEntity();
        fresh.setId(UUID.randomUUID());
        fresh.setUserId("user-1");
        fresh.setModelProvider("openai-compatible");
        fresh.setModelName("app-test");
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(fresh);

        AgentSessionResolver.ResolvedSession resolved = resolver.resolveOrCreate(parentId, "user-1", "app-test");

        assertThat(resolved.isNew()).isTrue();
    }

    @Test
    void createsNewSessionWithInboundTransportSource() {
        UUID missing = UUID.randomUUID();
        when(messageRepository.countBySessionIdAndActiveTrue(missing)).thenReturn(0L);
        when(sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(missing)).thenReturn(List.of());
        SessionEntity fresh = new SessionEntity();
        fresh.setId(UUID.randomUUID());
        fresh.setUserId("telegram-user");
        fresh.setModelProvider("openai-compatible");
        fresh.setModelName("app-test");
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> {
            SessionEntity saved = invocation.getArgument(0);
            fresh.setSource(saved.getSource());
            return fresh;
        });

        AgentSessionResolver.ResolvedSession resolved = resolver.resolveOrCreate(
            missing, "telegram-user", "app-test", "telegram");

        assertThat(resolved.isNew()).isTrue();
        assertThat(fresh.getSource()).isEqualTo("telegram");
    }
}
