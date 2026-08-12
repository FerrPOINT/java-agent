package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L33 test: verify that @PreDestroy clears the static instance reference
 * to prevent leaks across test contexts.
 */
class InterruptTokenPreDestroyTest {

    @AfterEach
    void cleanup() {
        // Reset static state after each test
        InterruptToken.setInstance(null);
    }

    @Test
    void destroyClearsStaticInstance() {
        InterruptToken token = new InterruptToken();
        token.init(); // @PostConstruct — sets static instance
        assertThat(InterruptToken.isCancelledGlobally()).isFalse(); // instance is set

        token.destroy(); // @PreDestroy — should clear static instance
        // After destroy, isCancelledGlobally should return false because instance is null
        InterruptToken.setCurrentSessionId(UUID.randomUUID());
        assertThat(InterruptToken.isCancelledGlobally()).isFalse();
        InterruptToken.clearCurrentSessionId();
    }

    @Test
    void destroyDoesNotClearNewerInstance() {
        InterruptToken token1 = new InterruptToken();
        token1.init();
        InterruptToken token2 = new InterruptToken();
        token2.init(); // overrides token1 as the static instance

        token1.destroy(); // should NOT clear because token2 is now the instance
        // token2 should still be the active instance — cancelling should work
        UUID sessionId = UUID.randomUUID();
        token2.cancel(sessionId);
        InterruptToken.setCurrentSessionId(sessionId);
        assertThat(InterruptToken.isCancelledGlobally()).isTrue();
        InterruptToken.clearCurrentSessionId();
    }
}