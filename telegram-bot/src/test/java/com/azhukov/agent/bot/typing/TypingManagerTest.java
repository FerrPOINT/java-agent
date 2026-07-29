package com.azhukov.agent.bot.typing;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
    }

    @Test
    void startTyping_sendsImmediatelyAndSchedules() throws InterruptedException {
        when(client.sendTyping(anyLong())).thenReturn(true);
        manager.startTyping(123L);
        Thread.sleep(80);
        verify(client, atLeast(2)).sendTyping(123L);
        manager.stopTyping(123L);
    }

    @Test
    void stopTyping_cancelsRefresh() throws InterruptedException {
        when(client.sendTyping(anyLong())).thenReturn(true);
        manager.startTyping(123L);
        Thread.sleep(80);
        manager.stopTyping(123L);
        int countAfterStop = org.mockito.Mockito.mockingDetails(client).getInvocations().size();
        Thread.sleep(80);
        int countAfterWait = org.mockito.Mockito.mockingDetails(client).getInvocations().size();
        assertThat(countAfterWait).isEqualTo(countAfterStop);
    }

    @Test
    void isTyping_trueAfterStart() {
        when(client.sendTyping(anyLong())).thenReturn(true);
        manager.startTyping(123L);
        assertThat(manager.isTyping(123L)).isTrue();
        manager.stopTyping(123L);
    }

    @Test
    void isTyping_falseAfterStop() {
        when(client.sendTyping(anyLong())).thenReturn(true);
        manager.startTyping(123L);
        manager.stopTyping(123L);
        assertThat(manager.isTyping(123L)).isFalse();
    }

    @Test
    void flushTyping_sendsOneFinalActionBeforeStopping() {
        when(client.sendTyping(anyLong())).thenReturn(true);
        manager.startTyping(123L);
        clearInvocations(client);

        manager.flushTyping(123L);
        verify(client).sendTyping(123L);

        manager.stopTyping(123L);
    }

    @Test
    void flushTyping_whenNotActive_doesNothing() {
        when(client.sendTyping(anyLong())).thenReturn(true);
        manager.flushTyping(123L);
        verifyNoInteractions(client);
    }
}
