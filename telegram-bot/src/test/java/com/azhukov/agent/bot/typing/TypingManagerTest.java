package com.azhukov.agent.bot.typing;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TypingManagerTest {

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
    void startTyping_sendsImmediatelyAndSchedules() throws InterruptedException {
        // Use latch to wait for at least 2 sendTyping calls (immediate + one periodic)
        CountDownLatch typingLatch = new CountDownLatch(2);
        when(client.sendTyping(anyLong(), any())).thenAnswer(inv -> {
            typingLatch.countDown();
            return true;
        });
        manager.startTyping(123L);
        assertThat(typingLatch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(client, atLeast(2)).sendTyping(eq(123L), any());
        manager.stopTyping(123L);
    }

    @Test
    void stopTyping_cancelsRefresh() throws InterruptedException {
        // Use latch: count calls before and after stop
        CountDownLatch firstCalls = new CountDownLatch(2);
        when(client.sendTyping(anyLong(), any())).thenAnswer(inv -> {
            firstCalls.countDown();
            return true;
        });
        manager.startTyping(123L);
        // Wait for at least 2 calls (immediate + one periodic)
        assertThat(firstCalls.await(2, TimeUnit.SECONDS)).isTrue();
        manager.stopTyping(123L);
        int countAfterStop = org.mockito.Mockito.mockingDetails(client).getInvocations().size();
        // Actual timing: verify no periodic task fires after cancellation
        Thread.sleep(100); // timing-assertion
        int countAfterWait = org.mockito.Mockito.mockingDetails(client).getInvocations().size();
        assertThat(countAfterWait).isEqualTo(countAfterStop);
    }

    @Test
    void isTyping_trueAfterStart() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        assertThat(manager.isTyping(123L)).isTrue();
        manager.stopTyping(123L);
    }

    @Test
    void isTyping_falseAfterStop() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        manager.stopTyping(123L);
        assertThat(manager.isTyping(123L)).isFalse();
    }

    @Test
    void flushTyping_sendsOneFinalActionBeforeStopping() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.startTyping(123L);
        clearInvocations(client);

        manager.flushTyping(123L);
        verify(client).sendTyping(eq(123L), any());

        manager.stopTyping(123L);
    }

    @Test
    void flushTyping_whenNotActive_doesNothing() {
        when(client.sendTyping(anyLong(), any())).thenReturn(true);
        manager.flushTyping(123L);
        verifyNoInteractions(client);
    }
}
