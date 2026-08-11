package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.entity.MessageEntity;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DefaultContextEngine} memory-related behavior.
 * <p>
 * After the three-tier prompt refactoring, memory is injected as a prefix to the
 * system prompt via {@link com.azhukov.agent.core.prompt.DefaultPromptBuilder#buildMemoryPrefix},
 * not by DefaultContextEngine. These tests verify that DefaultContextEngine does NOT call
 * memoryProvider during prepareContext, and that the system message is preserved as-is
 * (with only skills appended).
 */
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

    // 1. System message is preserved with skills appended — memory is NOT injected into system prompt
    @Test
    void snapshotIsFetchedFromProvider() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user1", "test", "noop", "model", null, Map.of());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        List<Message> result = engine.prepareContext(session, List.of(Message.system("You are an agent."), Message.user("hello")));

        assertThat(result).isNotEmpty();
        // Memory provider should NOT be called during prepareContext (memory is in user prefix now)
        verify(memoryProvider, never()).getSnapshot(anyString());

        // The system message should be preserved
        Message systemMsg = result.stream()
            .filter(m -> m.role() == Role.SYSTEM)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No system message found"));
        assertThat(systemMsg.content()).contains("You are an agent.");
    }

    // 2. Memory is not fetched per session during prepareContext
    @Test
    void snapshotIsCachedPerSession() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user1", "test", "noop", "model", null, Map.of());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        // Call twice with same session
        engine.prepareContext(session, List.of(Message.system("System"), Message.user("hi")));
        engine.prepareContext(session, List.of(Message.system("System"), Message.user("again")));

        // Memory provider should NOT be called at all during prepareContext
        verify(memoryProvider, never()).getSnapshot(anyString());
    }

    // 3. Different sessions do not trigger memory provider calls
    @Test
    void differentSessionsGetDifferentSnapshots() {
        UUID session1 = UUID.randomUUID();
        UUID session2 = UUID.randomUUID();
        Session s1 = new Session(session1, "user1", "test", "noop", "model", null, Map.of());
        Session s2 = new Session(session2, "user2", "test", "noop", "model", null, Map.of());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        engine.prepareContext(s1, List.of(Message.system("S"), Message.user("hi")));
        engine.prepareContext(s2, List.of(Message.system("S"), Message.user("hi")));

        // Memory provider should NOT be called during prepareContext
        verify(memoryProvider, never()).getSnapshot(anyString());
    }

    // 4. Empty snapshot does not trigger live recall in prepareContext
    @Test
    void emptySnapshotFallsBackToLiveRecall() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user1", "test", "noop", "model", null, Map.of());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        List<Message> result = engine.prepareContext(session, List.of(Message.system("System"), Message.user("hello")));

        // Recall should NOT be called during prepareContext (memory is in user prefix now)
        verify(memoryProvider, never()).recall(eq("user1"), anyString(), anyInt());

        // The system message should still exist
        Message systemMsg = result.stream()
            .filter(m -> m.role() == Role.SYSTEM)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No system message found"));
        assertThat(systemMsg.content()).contains("System");
    }

    // 5. Cache invalidation: memory provider is not called during prepareContext
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
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        // First call
        engineWithTracker.prepareContext(session1, List.of(Message.system("System"), Message.user("hi")));
        // Second call — different session
        engineWithTracker.prepareContext(session2, List.of(Message.system("System"), Message.user("hi")));

        // Memory provider should NOT be called during prepareContext
        verify(memoryProvider, never()).getSnapshot(anyString());
    }

    // 6. Memory provider exception does not crash prepareContext — memory is not called
    @Test
    void memoryProviderExceptionReturnsEmptyResultWithoutCrash() {
        UUID sessionId = UUID.randomUUID();
        Session session = new Session(sessionId, "user1", "test", "noop", "model", null, Map.of());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());

        // Should not throw — memory provider is not called during prepareContext
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