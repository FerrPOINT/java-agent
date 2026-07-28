package com.azhukov.agent.bot.reaction;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReactionManagerTest {

    private TelegramClient telegramClient;
    private BotProperties properties;
    private ReactionManager reactionManager;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        properties = new BotProperties();
        reactionManager = new ReactionManager(telegramClient, properties);
    }

    @Test
    void onProcessingStart_setsEyeReaction_whenEnabled() {
        properties.getReactions().setEnabled(true);
        when(telegramClient.setMessageReaction(anyLong(), anyLong(), anyString())).thenReturn(true);

        reactionManager.onProcessingStart(123L, 456L);

        verify(telegramClient).setMessageReaction(123L, 456L, "\uD83D\uDC40"); // 👀
    }

    @Test
    void onProcessingComplete_setsThumbsUp_onSuccess() {
        properties.getReactions().setEnabled(true);
        when(telegramClient.setMessageReaction(anyLong(), anyLong(), anyString())).thenReturn(true);

        reactionManager.onProcessingComplete(123L, 456L, true);

        verify(telegramClient).setMessageReaction(123L, 456L, "\uD83D\uDC4D"); // 👍
    }

    @Test
    void onProcessingComplete_setsThumbsDown_onFailure() {
        properties.getReactions().setEnabled(true);
        when(telegramClient.setMessageReaction(anyLong(), anyLong(), anyString())).thenReturn(true);

        reactionManager.onProcessingComplete(123L, 456L, false);

        verify(telegramClient).setMessageReaction(123L, 456L, "\uD83D\uDC4E"); // 👎
    }

    @Test
    void reactionsDisabled_doesNotCallTelegramClient() {
        properties.getReactions().setEnabled(false);

        reactionManager.onProcessingStart(123L, 456L);
        reactionManager.onProcessingComplete(123L, 456L, true);
        reactionManager.onCancel(123L, 456L);

        verify(telegramClient, never()).setMessageReaction(anyLong(), anyLong(), anyString());
    }
}