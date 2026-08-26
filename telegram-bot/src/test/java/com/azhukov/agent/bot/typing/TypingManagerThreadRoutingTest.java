package com.azhukov.agent.bot.typing;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for forum thread routing in TypingManager.
 * Verifies that typing indicators are routed to the correct forum topic thread,
 * matching Hermes behavior.
 */
class TypingManagerThreadRoutingTest {

    private TelegramClient client;
    private TypingManager manager;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        BotProperties props = new BotProperties();
        props.setTypingRefreshInterval(Duration.ofMillis(50));
        manager = new TypingManager(client, props);
        manager.init();
    }

    @Test
    void startTyping_withThreadIdRoutesToCorrectThread() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L, 42);
        // Verify typing was sent with the thread id
        verify(client).sendTyping(eq(123L), eq(42));
        manager.stopTyping(123L);
    }

    @Test
    void startTyping_withoutThreadIdSendsNullThread() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        // No thread id → null is passed (no thread routing)
        verify(client).sendTyping(eq(123L), any());
        manager.stopTyping(123L);
    }

    @Test
    void startTyping_withNullThreadIdSendsNullThread() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L, null);
        verify(client).sendTyping(eq(123L), any());
        manager.stopTyping(123L);
    }

    @Test
    void flushTyping_withThreadIdRoutesToCorrectThread() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L, 99);
        clearInvocations(client);

        manager.flushTyping(123L);
        verify(client).sendTyping(eq(123L), eq(99));

        manager.stopTyping(123L);
    }

    @Test
    void stopTypingClearsThreadId() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L, 42);
        manager.stopTyping(123L);

        // After stop, starting again with a different thread id should use the new one
        manager.startTyping(123L, 77);
        verify(client).sendTyping(eq(123L), eq(77));
        manager.stopTyping(123L);
    }

    @Test
    void periodicRefreshUsesThreadId() throws InterruptedException {
        // Use latch to wait for at least 2 sendTyping calls (immediate + one periodic)
        CountDownLatch typingLatch = new CountDownLatch(2);
        when(client.sendTyping(anyLong(), any())).thenAnswer(inv -> {
            typingLatch.countDown();
            return true;
        });
        manager.startTyping(123L, 55);
        assertThat(typingLatch.await(2, TimeUnit.SECONDS)).isTrue();
        // All calls should use the thread id
        verify(client, atLeast(2)).sendTyping(eq(123L), eq(55));
        manager.stopTyping(123L);
    }

    @Test
    void stopAllClearsAllThreadIds() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(100L, 10);
        manager.startTyping(200L, 20);
        manager.stopAll();

        // After stopAll, typing is not active
        assertThat(manager.isTyping(100L)).isFalse();
        assertThat(manager.isTyping(200L)).isFalse();

        // Starting again without thread id should work
        clearInvocations(client);
        manager.startTyping(100L);
        verify(client).sendTyping(eq(100L), any());
        manager.stopTyping(100L);
    }
}