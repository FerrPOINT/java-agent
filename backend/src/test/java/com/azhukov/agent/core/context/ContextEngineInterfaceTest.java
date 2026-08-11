package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.TokenUsage;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.persistence.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-16: Tests for ContextEngine interface default methods
 * and DefaultContextEngine.getStatus()/updateModel().
 */
@ExtendWith(MockitoExtension.class)
class ContextEngineInterfaceTest {

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
        engine = new DefaultContextEngine(
            memoryProvider, skillManager, messageRepository, contextCompressor, properties);
    }

    @Test
    void defaultGetStatusReturnsEmptyMap() {
        // An anonymous ContextEngine using only defaults should return an empty map
        ContextEngine defaultEngine = new ContextEngine() {
            @Override
            public java.util.List<com.azhukov.agent.core.model.Message> prepareContext(
                    com.azhukov.agent.core.model.Session session,
                    java.util.List<com.azhukov.agent.core.model.Message> messages) {
                return messages;
            }
        };
        assertThat(defaultEngine.getStatus()).isEmpty();
    }

    @Test
    void defaultUpdateModelIsNoOp() {
        // Should not throw
        ContextEngine defaultEngine = new ContextEngine() {
            @Override
            public java.util.List<com.azhukov.agent.core.model.Message> prepareContext(
                    com.azhukov.agent.core.model.Session session,
                    java.util.List<com.azhukov.agent.core.model.Message> messages) {
                return messages;
            }
        };
        defaultEngine.updateModel("gpt-4");
        // No exception means pass
    }

    @Test
    void defaultShouldCompressPreflightReturnsFalse() {
        ContextEngine defaultEngine = new ContextEngine() {
            @Override
            public java.util.List<com.azhukov.agent.core.model.Message> prepareContext(
                    com.azhukov.agent.core.model.Session session,
                    java.util.List<com.azhukov.agent.core.model.Message> messages) {
                return messages;
            }
        };
        assertThat(defaultEngine.shouldCompressPreflight(java.util.List.of())).isFalse();
    }

    @Test
    void defaultUpdateFromResponseIsNoOp() {
        ContextEngine defaultEngine = new ContextEngine() {
            @Override
            public java.util.List<com.azhukov.agent.core.model.Message> prepareContext(
                    com.azhukov.agent.core.model.Session session,
                    java.util.List<com.azhukov.agent.core.model.Message> messages) {
                return messages;
            }
        };
        defaultEngine.updateFromResponse(TokenUsage.of(100, 50));
        // No exception means pass
    }

    @Test
    void defaultContextEngineGetStatusReturnsMapWithExpectedKeys() {
        Map<String, Object> status = engine.getStatus();
        assertThat(status).isNotEmpty();
        assertThat(status).containsKey("lastPromptTokens");
        assertThat(status).containsKey("lastCompletionTokens");
        assertThat(status).containsKey("compressionCount");
        assertThat(status).containsKey("contextLength");
    }

    @Test
    void defaultContextEngineGetStatusReflectsUpdateFromResponse() {
        engine.updateFromResponse(TokenUsage.of(500, 200));

        Map<String, Object> status = engine.getStatus();
        assertThat(status.get("lastPromptTokens")).isEqualTo(500);
        assertThat(status.get("lastCompletionTokens")).isEqualTo(200);
        assertThat(status.get("lastTotalTokens")).isEqualTo(700);
    }

    @Test
    void defaultContextEngineUpdateModelWithNullIsNoOp() {
        // Should not throw when model is null
        engine.updateModel(null);
        engine.updateModel("");
    }
}