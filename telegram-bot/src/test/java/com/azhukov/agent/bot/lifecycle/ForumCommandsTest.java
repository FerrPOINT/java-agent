package com.azhukov.agent.bot.lifecycle;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ForumCommandsTest {

    private TelegramClient telegramClient;
    private BotProperties properties;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        properties = new BotProperties();
        properties.setToken("test-token");
        properties.setRegisterCommands(true);
        properties.setMode("polling");
    }

    @Test
    @SuppressWarnings("unchecked")
    void forumCommands_registeredWhenAllowedTopicsConfigured() {
        properties.getGroup().getAllowedTopics().add("123456789");
        properties.getGroup().getAllowedTopics().add("987654321");
        when(telegramClient.setMyCommands(any(List.class))).thenReturn(true);
        when(telegramClient.setMyCommandsForChat(anyLong(), any(List.class))).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties).onStartup();

        // Global registration
        verify(telegramClient).setMyCommands(any());
        // Per-forum registration: 2 calls
        verify(telegramClient, times(2)).setMyCommandsForChat(anyLong(), any(List.class));
        verify(telegramClient).setMyCommandsForChat(eq(123456789L), any(List.class));
        verify(telegramClient).setMyCommandsForChat(eq(987654321L), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forumCommands_skippedWhenNoAllowedTopics() {
        // allowedTopics is empty by default
        when(telegramClient.setMyCommands(any(List.class))).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties).onStartup();

        verify(telegramClient).setMyCommands(any());
        verify(telegramClient, never()).setMyCommandsForChat(anyLong(), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forumCommands_skipsInvalidChatId() {
        properties.getGroup().getAllowedTopics().add("not-a-number");
        properties.getGroup().getAllowedTopics().add("111222333");
        when(telegramClient.setMyCommands(any(List.class))).thenReturn(true);
        when(telegramClient.setMyCommandsForChat(anyLong(), any(List.class))).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties).onStartup();

        // Only valid chat_id should be registered
        verify(telegramClient, times(1)).setMyCommandsForChat(eq(111222333L), any(List.class));
        verify(telegramClient, never()).setMyCommandsForChat(eq(0L), any(List.class));
    }
}