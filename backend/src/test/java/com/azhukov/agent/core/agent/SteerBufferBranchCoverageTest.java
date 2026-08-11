package com.azhukov.agent.core.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch-coverage tests for SteerBuffer — covers null session IDs,
 * whitespace-only text, overwriting previous steer, and clear on empty.
 */
class SteerBufferBranchCoverageTest {

    @Test
    void steerWithWhitespaceOnlyTextReturnsFalse() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();
        assertThat(buffer.steer(sessionId, "   ")).isFalse();
        assertThat(buffer.hasPending(sessionId)).isFalse();
    }

    @Test
    void steerWithTabOnlyTextReturnsFalse() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();
        assertThat(buffer.steer(sessionId, "\t\t")).isFalse();
    }

    @Test
    void steerOverwritesPreviousPending() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();
        buffer.steer(sessionId, "first note");
        buffer.steer(sessionId, "second note");
        assertThat(buffer.consume(sessionId)).isEqualTo("second note");
    }

    @Test
    void clearOnEmptyIsNoOp() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();
        buffer.clear(sessionId);
        assertThat(buffer.hasPending(sessionId)).isFalse();
        assertThat(buffer.consume(sessionId)).isNull();
    }

    @Test
    void clearAfterConsumeIsNoOp() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();
        buffer.steer(sessionId, "test");
        buffer.consume(sessionId);
        buffer.clear(sessionId);
        assertThat(buffer.hasPending(sessionId)).isFalse();
    }

    @Test
    void consumeOnEmptyReturnsNull() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();
        assertThat(buffer.consume(sessionId)).isNull();
    }

    @Test
    void hasPendingOnEmptyReturnsFalse() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();
        assertThat(buffer.hasPending(sessionId)).isFalse();
    }

    @Test
    void steerWithNullSessionThrowsNpe() {
        SteerBuffer buffer = new SteerBuffer();
        // ConcurrentHashMap does not allow null keys
        org.junit.jupiter.api.Assertions.assertThrows(
            NullPointerException.class,
            () -> buffer.steer(null, "test note"));
    }

    @Test
    void steerNullTextReturnsFalseEvenWithValidSession() {
        SteerBuffer buffer = new SteerBuffer();
        UUID sessionId = UUID.randomUUID();
        assertThat(buffer.steer(sessionId, null)).isFalse();
        assertThat(buffer.hasPending(sessionId)).isFalse();
    }

    @Test
    void multipleSessionsIndependentSteer() {
        SteerBuffer buffer = new SteerBuffer();
        UUID session1 = UUID.randomUUID();
        UUID session2 = UUID.randomUUID();

        buffer.steer(session1, "note for session 1");
        buffer.steer(session2, "note for session 2");

        assertThat(buffer.consume(session1)).isEqualTo("note for session 1");
        assertThat(buffer.consume(session2)).isEqualTo("note for session 2");
        assertThat(buffer.hasPending(session1)).isFalse();
        assertThat(buffer.hasPending(session2)).isFalse();
    }
}