package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextEngineMemoryTest {

    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private SkillManager skillManager;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ContextCompressor contextCompressor;

    private AgentProperties properties;
    private DefaultContextEngine engine;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        lenient().when(skillManager.listSkillNames()).thenReturn(List.of());
        engine = new DefaultContextEngine(memoryProvider, skillManager, messageRepository, contextCompressor, properties);
    }

    // 1. Snapshot is fetched from memoryProvider
    @Test
    void snapshotIsFetchedFromProvider() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user1", "test", "noop", "model", null, Map.of());
        when(memoryProvider.getSnapshot("user1"))
            .thenReturn(Map.of("memory", "§ MEMORY\n[auto] User prefers dark mode", "user", "§ USER\n[auto] Name is Alice"));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        List<Message> result = engine.prepareContext(session, List.of(Message.system("You are an agent."), Message.user("hello")));

        assertThat(result).isNotEmpty();
        verify(memoryProvider).getSnapshot("user1");
    }

    // 2. Snapshot is cached per session (only fetched once)
    @Test
    void snapshotIsCachedPerSession() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user1", "test", "noop", "model", null, Map.of());
        when(memoryProvider.getSnapshot("user1"))
            .thenReturn(Map.of("memory", "§ MEMORY\nFact 1", "user", ""));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        // Call twice with same session
        engine.prepareContext(session, List.of(Message.system("System"), Message.user("hi")));
        engine.prepareContext(session, List.of(Message.system("System"), Message.user("again")));

        // Should only call getSnapshot once (cached)
        verify(memoryProvider, times(1)).getSnapshot("user1");
    }

    // 3. Different sessions get different snapshots
    @Test
    void differentSessionsGetDifferentSnapshots() {
        UUID session1 = UUID.randomUUID();
        UUID session2 = UUID.randomUUID();
        Session s1 = new Session(session1, "user1", "test", "noop", "model", null, Map.of());
        Session s2 = new Session(session2, "user2", "test", "noop", "model", null, Map.of());
        when(memoryProvider.getSnapshot("user1")).thenReturn(Map.of("memory", "§ MEMORY\nFact1", "user", ""));
        when(memoryProvider.getSnapshot("user2")).thenReturn(Map.of("memory", "§ MEMORY\nFact2", "user", ""));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        engine.prepareContext(s1, List.of(Message.system("S"), Message.user("hi")));
        engine.prepareContext(s2, List.of(Message.system("S"), Message.user("hi")));

        verify(memoryProvider).getSnapshot("user1");
        verify(memoryProvider).getSnapshot("user2");
    }
}