package com.azhukov.agent.service;

import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.client.mcp.McpLifecycleManager;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.skill.SkillBundleService;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * REM-10: Verify that restart() clears ALL sessions, not just the first 50.
 */
class AgentRuntimeServiceRestartTest {

    private AgentRuntimeService agentRuntimeService;
    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        agentRuntimeService = new AgentRuntimeService(
            mock(AgentRuntime.class),
            sessionRepository,
            messageRepository,
            mock(SessionTitleService.class),
            mock(MemoryProvider.class),
            mock(MemoryRepository.class),
            mock(WriteApprovalGate.class),
            mock(ConversationCompressor.class),
            mock(UsageTracker.class),
            mock(TurnUsageCollector.class),
            mock(AgentProperties.class),
            Mappers.getMapper(SessionEntityMapper.class),
            Mappers.getMapper(MessageMapper.class),
            Mappers.getMapper(DomainDtoMapper.class),
            mock(SkillBundleService.class),
            mock(SkillManager.class),
            mock(McpLifecycleManager.class),
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new RuntimeConfigService(),
            transactionTemplate,
            new AgentSessionResolver(sessionRepository, Mappers.getMapper(SessionEntityMapper.class), transactionTemplate),
            new CliStateApplier(),
            new SessionCompressionHelper(messageRepository, Mappers.getMapper(MessageMapper.class), mock(ConversationCompressor.class))
        );
    }

    @Test
    void restart_clearsAllSessionsBeyond50() {
        // Create 75 sessions — more than the old 50-item page limit
        List<SessionEntity> sessions = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            SessionEntity entity = new SessionEntity();
            entity.setId(UUID.randomUUID());
            entity.setUserId("user-1");
            entity.setTitle("session-" + i);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            sessions.add(entity);
        }

        // findAllByUserId(String) should return ALL sessions (no pagination)
        when(sessionRepository.findAllByUserId("user-1")).thenReturn(sessions);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenReturn(List.of());

        agentRuntimeService.restart();

        // Verify all 75 sessions had their messages cleared
        verify(messageRepository, times(75)).deleteAll(any());
        // Verify the non-paginated method was used
        verify(sessionRepository).findAllByUserId("user-1");
        // Verify the paginated method was NOT used
        verify(sessionRepository, never()).findAllByUserId(eq("user-1"), any());
    }

    @Test
    void restart_withNoSessions_doesNothing() {
        when(sessionRepository.findAllByUserId("user-1")).thenReturn(List.of());

        agentRuntimeService.restart();

        verify(messageRepository, never()).deleteAll(any());
    }
}