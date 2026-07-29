package com.azhukov.agent.core.agent;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.prompt.PromptCacheTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

class TurnFinalizerTest {

    private PromptCacheTracker cacheTracker;
    private TurnFinalizer finalizer;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getPromptCaching().setEnabled(true);
        cacheTracker = new PromptCacheTracker(properties);
        finalizer = new TurnFinalizer(cacheTracker);
    }

    @Test
    void finalize_success_preservesCache() {
        UUID sessionId = UUID.randomUUID();
        cacheTracker.markCached(sessionId.toString(), "abc123");
        List<Message> messages = List.of(Message.user("hello"), Message.assistant("hi", 1));
        assertThatCode(() -> finalizer.finalize(sessionId, messages, true))
            .doesNotThrowAnyException();
        // Cache should still be valid after successful turn
        org.assertj.core.api.Assertions.assertThat(cacheTracker.isCacheValid(sessionId.toString(), "abc123"))
            .isTrue();
    }

    @Test
    void finalize_failure_evictsCache() {
        UUID sessionId = UUID.randomUUID();
        cacheTracker.markCached(sessionId.toString(), "abc123");
        List<Message> messages = List.of(Message.user("hello"), Message.assistant("error", 1));
        assertThatCode(() -> finalizer.finalize(sessionId, messages, false))
            .doesNotThrowAnyException();
        // Cache should be evicted after failed turn
        org.assertj.core.api.Assertions.assertThat(cacheTracker.isCacheValid(sessionId.toString(), "abc123"))
            .isFalse();
    }

    @Test
    void finalize_emptyMessages_doesNotThrow() {
        UUID sessionId = UUID.randomUUID();
        assertThatCode(() -> finalizer.finalize(sessionId, List.of(), true))
            .doesNotThrowAnyException();
    }

    @Test
    void finalize_nullMessages_doesNotThrow() {
        UUID sessionId = UUID.randomUUID();
        assertThatCode(() -> finalizer.finalize(sessionId, null, false))
            .doesNotThrowAnyException();
    }
}