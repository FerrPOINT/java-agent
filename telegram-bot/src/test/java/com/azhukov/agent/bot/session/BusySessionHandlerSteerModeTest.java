package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the busy-input mode dispatch and busy-ack debounce logic
 * in {@link BusySessionHandler}.
 */
class BusySessionHandlerSteerModeTest {

    private BotProperties properties;
    private BusySessionHandler handler;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        handler = new BusySessionHandler(properties);
    }

    @Test
    void getEffectiveBusyInputModeReturnsSteerWhenConfigured() {
        properties.setBusyInputMode("steer");
        assertThat(handler.getEffectiveBusyInputMode()).isEqualTo("steer");
    }

    @Test
    void getEffectiveBusyInputModeReturnsQueueWhenConfigured() {
        properties.setBusyInputMode("queue");
        assertThat(handler.getEffectiveBusyInputMode()).isEqualTo("queue");
    }

    @Test
    void getEffectiveBusyInputModeReturnsInterruptWhenConfigured() {
        properties.setBusyInputMode("interrupt");
        assertThat(handler.getEffectiveBusyInputMode()).isEqualTo("interrupt");
    }

    @Test
    void getEffectiveBusyInputModeFallsBackToLegacyBusyMode() {
        properties.setBusyInputMode(null);
        properties.setBusyMode("queue");
        assertThat(handler.getEffectiveBusyInputMode()).isEqualTo("queue");
    }

    @Test
    void getEffectiveBusyInputModeDefaultsToInterrupt() {
        properties.setBusyInputMode(null);
        properties.setBusyMode(null);
        assertThat(handler.getEffectiveBusyInputMode()).isEqualTo("interrupt");
    }

    @Test
    void shouldSendBusyAckReturnsTrueOnFirstCall() {
        long chatId = 12345L;
        assertThat(handler.shouldSendBusyAck(chatId)).isTrue();
    }

    @Test
    void shouldSendBusyAckReturnsFalseWithin30SecondWindow() {
        long chatId = 12345L;
        handler.shouldSendBusyAck(chatId); // first call stamps timestamp
        assertThat(handler.shouldSendBusyAck(chatId)).isFalse();
    }

    @Test
    void shouldShowOnboardingHintReturnsTrueOnlyOnce() {
        assertThat(handler.shouldShowOnboardingHint()).isTrue();
        assertThat(handler.shouldShowOnboardingHint()).isFalse();
    }

    @Test
    void markFreeClearsBusyAckTimestamp() {
        long chatId = 12345L;
        handler.shouldSendBusyAck(chatId); // stamp
        handler.markFree(chatId);
        // After markFree, the next ack should be allowed
        assertThat(handler.shouldSendBusyAck(chatId)).isTrue();
    }

    @Test
    void isBusyAckEnabledDefaultsToTrue() {
        assertThat(handler.isBusyAckEnabled()).isTrue();
    }

    @Test
    void isBusyAckEnabledReturnsFalseWhenDisabled() {
        properties.setBusyAckEnabled(false);
        assertThat(handler.isBusyAckEnabled()).isFalse();
    }
}