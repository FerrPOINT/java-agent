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
    private SkillManager skillManager;
    private McpLifecycleManager mcpLifecycleManager;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        skillManager = mock(SkillManager.class);
        mcpLifecycleManager = mock(McpLifecycleManager.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        agentRuntimeService = new AgentRuntimeService(
            mock(AgentRuntime.class),
            org.mockito.Mockito.mock(com.azhukov.agent.persistence.repository.BackgroundJobRepository.class),
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
            skillManager,
            mcpLifecycleManager,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            org.mockito.Mockito.mock(RuntimeConfigService.class),
            transactionTemplate,
            new AgentSessionResolver(sessionRepository, Mappers.getMapper(SessionEntityMapper.class), transactionTemplate, messageRepository, mock(com.azhukov.agent.core.agent.SessionLineageService.class)),
            new CliStateApplier(),
            new SessionCompressionHelper(
                messageRepository,
                Mappers.getMapper(MessageMapper.class),
                mock(ConversationCompressor.class),
                sessionRepository),
            mock(com.azhukov.agent.core.context.ContextCompressor.class),
            mock(com.azhukov.agent.core.metadata.ModelMetadataService.class), null,
            null, null
        );
    }

    @Test
    void restart_preservesHistoryAndReloadsRuntime() {
        // Hermes parity (gateway/slash_commands.py _handle_restart_command):
        // restart drains and reloads runtime state; it NEVER wipes messages.
        // The old implementation deleted every message of the default user.
        agentRuntimeService.restart();

        verify(messageRepository, never()).deleteAll(any());
        verifyNoInteractions(messageRepository);
        // runtime reload path exercised: skills + mcp + model override
        verify(skillManager).reload();
        verify(mcpLifecycleManager).closeAll();
        verify(mcpLifecycleManager).connectConfiguredServers();
    }

    @Test
    void restart_withNoSessions_doesNothing() {
        when(sessionRepository.findAllByUserId("user-1")).thenReturn(List.of());

        agentRuntimeService.restart();

        verify(messageRepository, never()).deleteAll(any());
    }
}
