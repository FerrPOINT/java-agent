package com.azhukov.agent.core.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpMemoryProviderTest {

    @Test
    void recallReturnsEmptyList() {
        assertThat(new NoOpMemoryProvider().recall("u", "q", 5)).isEmpty();
    }

    @Test
    void storeDoesNothing() {
        new NoOpMemoryProvider().store("u", "c", "f");
    }
}
