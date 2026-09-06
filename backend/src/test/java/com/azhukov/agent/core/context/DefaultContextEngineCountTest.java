package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.core.ports.MessageStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for DefaultContextEngine.countPriorUserMessages (Finding 5.2).
 */
@ExtendWith(MockitoExtension.class)
class DefaultContextEngineCountTest {

    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private SkillManager skillManager;
    @Mock
    private com.azhukov.agent.core.ports.MessageStorePort messageRepository;
    @Mock
    private ContextCompressor contextCompressor;

    private DefaultContextEngine contextEngine;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        contextEngine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
        sessionId = UUID.randomUUID();
    }

    private MessageEntity entity(String role, String content) {
        MessageEntity e = new MessageEntity();
        e.setSessionId(sessionId);
        e.setRole(role);
        e.setContent(content);
        e.setTurnIndex(1);
        return e;
    }

    @Test
    void countPriorUserMessagesReturnsCorrectCount() {
        // 3 user + 2 assistant = 5 total, should return 3
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenReturn(List.of(
                entity("user", "msg1"),
                entity("assistant", "reply1"),
                entity("user", "msg2"),
                entity("assistant", "reply2"),
                entity("user", "msg3")
            ));

        long count = contextEngine.countPriorUserMessages(sessionId);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void countPriorUserMessagesReturnsZeroForEmptySession() {
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenReturn(List.of());

        long count = contextEngine.countPriorUserMessages(sessionId);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void countPriorUserMessagesReturnsZeroOnException() {
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any(UUID.class)))
            .thenThrow(new RuntimeException("DB connection lost"));

        long count = contextEngine.countPriorUserMessages(sessionId);
        assertThat(count).isEqualTo(0);
    }
}