package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SteerBufferTest {

    @Test
    void steerAndConsume() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();

        buffer.steer(sessionId, "focus on auth module");
        assertThat(buffer.hasPending(sessionId)).isTrue();

        String text = buffer.consume(sessionId);
        assertThat(text).isEqualTo("focus on auth module");
        assertThat(buffer.hasPending(sessionId)).isFalse();
    }

    @Test
    void consumeEmptyReturnsNull() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();

        assertThat(buffer.consume(sessionId)).isNull();
    }

    @Test
    void blankSteerRejected() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();

        assertThat(buffer.steer(sessionId, "")).isFalse();
        assertThat(buffer.steer(sessionId, null)).isFalse();
        assertThat(buffer.hasPending(sessionId)).isFalse();
    }

    @Test
    void clearRemovesPending() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();

        buffer.steer(sessionId, "test note");
        buffer.clear(sessionId);

        assertThat(buffer.hasPending(sessionId)).isFalse();
        assertThat(buffer.consume(sessionId)).isNull();
    }

    @Test
    void consumeIsOneShot() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();

        buffer.steer(sessionId, "first note");
        buffer.consume(sessionId);

        // Second consume returns null
        assertThat(buffer.consume(sessionId)).isNull();
    }
}