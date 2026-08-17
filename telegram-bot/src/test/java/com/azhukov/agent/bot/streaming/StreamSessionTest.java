package com.azhukov.agent.bot.streaming;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * c6: Unit tests for {@link StreamSession} — the per-chat streaming state holder
 * that replaced the 17 parallel {@code ConcurrentHashMap}s in {@link StreamEditor}.
 *
 * <p>Verifies the {@link StreamSession#resetForNewStream()}, {@link StreamSession#clear()},
 * and {@link StreamSession#ensureThinkScrubber()} lifecycle methods reset/initialize
 * the right fields, and that the atomic holders ({@link StreamSession#editInterval},
 * {@link StreamSession#currentMessageId}) behave correctly.
 */
class StreamSessionTest {

    @Test
    void defaults_areClean() {
        StreamSession s = new StreamSession();
        assertThat(s.editInterval.get()).isZero();
        assertThat(s.lastEditTime).isZero();
        assertThat(s.floodStrikes).isZero();
        assertThat(s.streamingDisabled).isFalse();
        assertThat(s.floodFallbackBuffer.length()).isZero();
        assertThat(s.lastSentText).isNull();
        assertThat(s.thinkScrubber).isNull();
        assertThat(s.streamStartTime).isZero();
        assertThat(s.lastTokenTime).isZero();
        assertThat((Object) s.heartbeatFuture).isNull();
        assertThat(s.charsSinceLastEdit).isZero();
        assertThat(s.currentToolName).isNull();
        assertThat(s.currentMessageId.get()).isEqualTo(-1L);
        assertThat(s.useDraftStreaming).isFalse();
        assertThat(s.draftId).isZero();
        assertThat(s.draftFailures).isZero();
        assertThat(s.chatType).isEqualTo("dm");
    }

    @Test
    void resetForNewStream_clearsMutableState_butKeepsChatType() {
        StreamSession s = new StreamSession();
        // Populate all fields with "dirty" values
        s.editInterval.set(5000L);
        s.lastEditTime = 1234L;
        s.floodStrikes = 3;
        s.streamingDisabled = true;
        s.floodFallbackBuffer.append("buffered");
        s.lastSentText = "sent";
        s.thinkScrubber = new StreamEditor.ThinkScrubber();
        s.streamStartTime = 999L;
        s.lastTokenTime = 888L;
        s.charsSinceLastEdit = 50;
        s.currentToolName = "WebSearch";
        s.currentMessageId.set(42L);
        s.useDraftStreaming = true;
        s.draftId = 7;
        s.draftFailures = 2;
        s.chatType = "group";

        s.resetForNewStream();

        assertThat(s.editInterval.get()).isZero();
        assertThat(s.lastEditTime).isZero();
        assertThat(s.floodStrikes).isZero();
        assertThat(s.streamingDisabled).isFalse();
        assertThat(s.floodFallbackBuffer.length()).isZero();
        assertThat(s.lastSentText).isNull();
        assertThat(s.thinkScrubber).isNull();
        assertThat(s.currentToolName).isNull();
        assertThat(s.currentMessageId.get()).isEqualTo(-1L);
        assertThat(s.useDraftStreaming).isFalse();
        assertThat(s.draftId).isZero();
        assertThat(s.draftFailures).isZero();
        // chatType is intentionally NOT reset by resetForNewStream
        assertThat(s.chatType).isEqualTo("group");
    }

    @Test
    void resetForNewStream_doesNotTouchHeartbeatFuture() {
        // The caller cancels the heartbeat explicitly; reset must not null it out
        // (otherwise a scheduled task could be left running).
        StreamSession s = new StreamSession();
        ScheduledFuture<?> sentinel = mockFuture();
        s.heartbeatFuture = sentinel;
        s.streamStartTime = 1L;

        s.resetForNewStream();

        assertThat((Object) s.heartbeatFuture).isSameAs(sentinel);
        // streamStartTime/lastTokenTime are NOT reset by resetForNewStream either
        assertThat(s.streamStartTime).isEqualTo(1L);
    }

    @Test
    void clear_resetsEverything_includingChatTypeAndTimes() {
        StreamSession s = new StreamSession();
        s.streamStartTime = 555L;
        s.lastTokenTime = 666L;
        s.chatType = "supergroup";

        s.clear();

        assertThat(s.streamStartTime).isZero();
        assertThat(s.lastTokenTime).isZero();
        assertThat(s.chatType).isEqualTo("dm");
    }

    @Test
    void ensureThinkScrubber_createsOnce_andReturnsSameInstance() {
        StreamSession s = new StreamSession();
        assertThat(s.thinkScrubber).isNull();

        StreamEditor.ThinkScrubber first = s.ensureThinkScrubber();
        assertThat(first).isNotNull();
        assertThat(s.thinkScrubber).isSameAs(first);

        // Second call returns the same instance (no replacement)
        StreamEditor.ThinkScrubber second = s.ensureThinkScrubber();
        assertThat(second).isSameAs(first);
    }

    @Test
    void editInterval_isAtomicLong_andMutable() {
        StreamSession s = new StreamSession();
        assertThat(s.editInterval).isInstanceOf(AtomicLong.class);
        s.editInterval.set(2500L);
        assertThat(s.editInterval.get()).isEqualTo(2500L);
    }

    @Test
    void currentMessageId_isAtomicLong_andMutable() {
        StreamSession s = new StreamSession();
        assertThat(s.currentMessageId.get()).isEqualTo(-1L);
        s.currentMessageId.set(99L);
        assertThat(s.currentMessageId.get()).isEqualTo(99L);
    }

    @Test
    void floodFallbackBuffer_isMutableStringBuilder() {
        StreamSession s = new StreamSession();
        s.floodFallbackBuffer.append("part1 ").append("part2");
        assertThat(s.floodFallbackBuffer.toString()).isEqualTo("part1 part2");
        s.floodFallbackBuffer.setLength(0);
        assertThat(s.floodFallbackBuffer.length()).isZero();
    }

    @Test
    void resetForNewStream_canBeCalledMultipleTimes_safely() {
        StreamSession s = new StreamSession();
        s.floodStrikes = 5;
        s.resetForNewStream();
        assertThat(s.floodStrikes).isZero();
        s.floodStrikes = 2;
        s.resetForNewStream();
        assertThat(s.floodStrikes).isZero();
    }

    /** Minimal no-op ScheduledFuture for sentinel testing. */
    @SuppressWarnings("unchecked")
    private static ScheduledFuture<?> mockFuture() {
        return new ScheduledFuture<>() {
            @Override public long getDelay(java.util.concurrent.TimeUnit unit) { return 0; }
            @Override public int compareTo(java.util.concurrent.Delayed o) { return 0; }
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return true; }
            @Override public Object get() { return null; }
            @Override public Object get(long timeout, java.util.concurrent.TimeUnit unit) { return null; }
        };
    }
}