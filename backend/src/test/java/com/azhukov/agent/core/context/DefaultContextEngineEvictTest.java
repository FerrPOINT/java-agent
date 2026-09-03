package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rev-50: rotatedSessionIds were never removed in evict() — every
 * session-rotation (compression) left an old→new id mapping in the map
 * forever. resetSession and session deletion both go through evict, so a
 * reset session would also keep redirecting resolveRotatedSession into the
 * pre-reset child.
 */
@ExtendWith(MockitoExtension.class)
class DefaultContextEngineEvictTest {

    @Mock
    private MemoryProvider memoryProvider;
    @Mock
    private SkillManager skillManager;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ContextCompressor contextCompressor;

    private DefaultContextEngine engine;

    @BeforeEach
    void setUp() {
        contextEngineConstructor();
    }

    private void contextEngineConstructor() {
        engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, new AgentProperties());
    }

    @Test
    void evictRemovesRotationMapping() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        java.lang.reflect.Field f = DefaultContextEngine.class.getDeclaredField("rotatedSessionIds");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<UUID, UUID> map = (ConcurrentHashMap<UUID, UUID>) f.get(engine);
        map.put(sessionId, childId);

        engine.evict(sessionId);

        assertThat(map).doesNotContainKey(sessionId);
    }

    @Test
    void evictNullSessionIsNoOp() {
        engine.evict(null);
        // no exception
    }
}
