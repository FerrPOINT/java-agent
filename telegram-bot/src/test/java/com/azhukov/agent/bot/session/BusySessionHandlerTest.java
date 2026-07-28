package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusySessionHandlerTest {

    private BotProperties properties;
    private BusySessionHandler handler;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        handler = new BusySessionHandler(properties);
    }

    // ─── busy / free ───────────────────────────────────────────────

    @Test
    void isBusy_falseByDefault() {
        assertThat(handler.isBusy(123L)).isFalse();
    }

    @Test
    void markBusy_setsBusyTrue() {
        handler.markBusy(123L);
        assertThat(handler.isBusy(123L)).isTrue();
    }

    @Test
    void markFree_setsBusyFalse() {
        handler.markBusy(123L);
        handler.markFree(123L);
        assertThat(handler.isBusy(123L)).isFalse();
    }

    @Test
    void markBusy_onDifferentChats_areIndependent() {
        handler.markBusy(100L);
        handler.markBusy(200L);
        assertThat(handler.isBusy(100L)).isTrue();
        assertThat(handler.isBusy(200L)).isTrue();
        handler.markFree(100L);
        assertThat(handler.isBusy(100L)).isFalse();
        assertThat(handler.isBusy(200L)).isTrue();
    }

    // ─── queue add / drain ─────────────────────────────────────────

    @Test
    void queueMessage_addsToQueue() {
        UpdateEvent event = textEvent(1L, "hello");
        handler.queueMessage(123L, event);
        assertThat(handler.hasQueued(123L)).isTrue();
    }

    @Test
    void drainQueue_returnsAndClearsQueuedMessages() {
        UpdateEvent e1 = textEvent(1L, "hello");
        UpdateEvent e2 = textEvent(2L, "world");
        handler.queueMessage(123L, e1);
        handler.queueMessage(123L, e2);

        var drained = handler.drainQueue(123L);

        assertThat(drained).hasSize(2).containsExactly(e1, e2);
        assertThat(handler.hasQueued(123L)).isFalse();
    }

    @Test
    void drainQueue_emptyReturnsEmptyList() {
        assertThat(handler.drainQueue(999L)).isEmpty();
    }

    @Test
    void drainQueue_afterSecondDrain_returnsEmpty() {
        handler.queueMessage(123L, textEvent(1L, "a"));
        handler.drainQueue(123L);
        assertThat(handler.drainQueue(123L)).isEmpty();
    }

    @Test
    void queueMessage_nullEvent_isIgnored() {
        handler.queueMessage(123L, null);
        assertThat(handler.hasQueued(123L)).isFalse();
    }

    @Test
    void queueMessage_onDifferentChats_areIndependent() {
        handler.queueMessage(100L, textEvent(1L, "a"));
        handler.queueMessage(200L, textEvent(2L, "b"));

        assertThat(handler.drainQueue(100L)).hasSize(1);
        assertThat(handler.drainQueue(200L)).hasSize(1);
    }

    // ─── interrupt mode ────────────────────────────────────────────

    @Test
    void interrupt_setsInterruptedTrue() {
        handler.interrupt(123L);
        assertThat(handler.isInterrupted(123L)).isTrue();
    }

    @Test
    void isInterrupted_falseByDefault() {
        assertThat(handler.isInterrupted(123L)).isFalse();
    }

    @Test
    void markBusy_resetsInterruptFlag() {
        handler.interrupt(123L);
        handler.markBusy(123L);
        assertThat(handler.isInterrupted(123L)).isFalse();
    }

    @Test
    void markFree_resetsInterruptFlag() {
        handler.markBusy(123L);
        handler.interrupt(123L);
        handler.markFree(123L);
        assertThat(handler.isInterrupted(123L)).isFalse();
    }

    @Test
    void interrupt_onDifferentChats_areIndependent() {
        handler.interrupt(100L);
        assertThat(handler.isInterrupted(100L)).isTrue();
        assertThat(handler.isInterrupted(200L)).isFalse();
    }

    // ─── busy mode config ──────────────────────────────────────────

    @Test
    void getBusyMode_returnsConfiguredMode() {
        properties.setBusyMode("interrupt");
        assertThat(handler.getBusyMode()).isEqualTo("interrupt");
    }

    @Test
    void getBusyMode_defaultIsQueue() {
        assertThat(handler.getBusyMode()).isEqualTo("queue");
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private UpdateEvent textEvent(long updateId, String text) {
        return new UpdateEvent(updateId, UpdateEvent.Type.TEXT, 123L, 456L,
            "jdoe", text, null, null, null,
            null, null, null, false, null, null);
    }
}