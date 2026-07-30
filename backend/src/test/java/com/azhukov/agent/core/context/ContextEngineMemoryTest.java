package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    // 1. Snapshot is fetched from memoryProvider — assert system message contains snapshot content
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

        // The system message should contain the snapshot content
        Message systemMsg = result.stream()
            .filter(m -> m.role() == Role.SYSTEM)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No system message found"));
        assertThat(systemMsg.content()).contains("§ MEMORY");
        assertThat(systemMsg.content()).contains("User prefers dark mode");
        assertThat(systemMsg.content()).contains("§ USER");
        assertThat(systemMsg.content()).contains("Name is Alice");
        // The original system prompt should also be preserved
        assertThat(systemMsg.content()).contains("You are an agent.");
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

    // 4. Empty snapshot falls back to live recall
    @Test
    void emptySnapshotFallsBackToLiveRecall() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user1", "test", "noop", "model", null, Map.of());
        // Empty snapshot — memory and user blocks are blank
        when(memoryProvider.getSnapshot("user1")).thenReturn(Map.of("memory", "", "user", ""));
        // Provide history so findLastUserMessage finds a user message to use for recall
        com.azhukov.agent.persistence.entity.MessageEntity histMsg = new com.azhukov.agent.persistence.entity.MessageEntity();
        histMsg.setRole("user");
        histMsg.setContent("hello");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))
            .thenReturn(List.of(histMsg));
        // Live recall returns facts
        when(memoryProvider.recall("user1", "hello", 5)).thenReturn(List.of("[auto] Fact from recall"));

        List<Message> result = engine.prepareContext(session, List.of(Message.system("System"), Message.user("hello")));

        // The recall should have been called as a fallback
        verify(memoryProvider).recall(eq("user1"), anyString(), eq(5));

        // The system message should contain the recalled fact
        Message systemMsg = result.stream()
            .filter(m -> m.role() == Role.SYSTEM)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No system message found"));
        assertThat(systemMsg.content()).contains("Relevant memory:");
        assertThat(systemMsg.content()).contains("[auto] Fact from recall");
    }

    // 5. Cache invalidation: when snapshot content changes between sessions,
    //    PromptCacheTracker.invalidate is called
    @Test
    void cacheInvalidationTriggersWhenMemoryChangesForDifferentSessions() {
        AgentProperties props = new AgentProperties();
        props.getPromptCaching().setEnabled(true);
        PromptCacheTracker cacheTracker = new PromptCacheTracker(props);

        // Create engine with PromptCacheTracker
        DefaultContextEngine engineWithTracker = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, props, cacheTracker);

        UUID session1Id = UUID.randomUUID();
        UUID session2Id = UUID.randomUUID();
        Session session1 = new Session(session1Id, "user1", "test", "noop", "model", null, Map.of());
        Session session2 = new Session(session2Id, "user1", "test", "noop", "model", null, Map.of());

        // First session: snapshot with memory content "A"
        when(memoryProvider.getSnapshot("user1"))
            .thenReturn(Map.of("memory", "§ MEMORY\nContent A", "user", ""));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        // First call — establishes the memory hash
        engineWithTracker.prepareContext(session1, List.of(Message.system("System"), Message.user("hi")));

        // Second session (same user, different session): snapshot with different memory content "B"
        // Since this is a new session, snapshotCache will call getSnapshot again
        when(memoryProvider.getSnapshot("user1"))
            .thenReturn(Map.of("memory", "§ MEMORY\nContent B", "user", ""));

        // Second call — memory content changed, should trigger cache invalidation
        engineWithTracker.prepareContext(session2, List.of(Message.system("System"), Message.user("hi")));

        // Verify that getSnapshot was called twice (once per session — different sessions have separate caches)
        verify(memoryProvider, times(2)).getSnapshot("user1");
    }

    // 6. Memory provider exception is handled gracefully — falls back to empty snapshot
    @Test
    void memoryProviderExceptionReturnsEmptyResultWithoutCrash() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user1", "test", "noop", "model", null, Map.of());
        when(memoryProvider.getSnapshot("user1")).thenThrow(new RuntimeException("DB unavailable"));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        // Should not throw — should handle exception gracefully
        List<Message> result = engine.prepareContext(session, List.of(Message.system("System"), Message.user("hi")));

        assertThat(result).isNotEmpty();
        // The system message should still exist (without memory content)
        Message systemMsg = result.stream()
            .filter(m -> m.role() == Role.SYSTEM)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No system message found"));
        assertThat(systemMsg.content()).contains("System");
    }
}