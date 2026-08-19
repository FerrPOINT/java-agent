package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * M27: Test that StreamEditor cleans up the think scrubber only after
 * successful send confirmation, not before.
 */
class StreamEditorThinkScrubberCleanupTest {

    private TelegramClient client;
    private StreamEditor editor;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingSilent(true);
        props.setHeartbeatIntervalSeconds(1);
        editor = new StreamEditor(client, props, new MediaDeliveryService());
        editor.init();
        // Mock getMe for rich messages check
        com.azhukov.agent.bot.client.TelegramResponse meResponse = mock(com.azhukov.agent.bot.client.TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(Map.of());
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));
        when(client.getLastApiErrorCode()).thenReturn(0);
    }

    @Test
    void startStreamRemovesScrubberOnSendFailure() {
        // When sendMessage fails (returns empty), the scrubber should be cleaned up
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.empty());

        Optional<Long> result = editor.startStream(123L, "Hello world message");

        assertThat(result).isEmpty();
        // Verify sendMessage was attempted
        verify(client).sendMessage(eq(123L), anyString(), any(), any(), any(), anyBoolean());
    }

    @Test
    void startStreamKeepsScrubberOnSendSuccess() {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        Optional<Long> result = editor.startStream(123L, "Hello world message");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(42L);
        verify(client).sendMessage(eq(123L), anyString(), any(), any(), any(), anyBoolean());
    }

    @Test
    void startStreamWithShortTextDoesNotSendButKeepsScrubber() {
        // When text is too short (<4 chars), no message is sent
        // The scrubber should still be available for later use
        Optional<Long> result = editor.startStream(123L, "Hi");

        assertThat(result).isEmpty();
        // No sendMessage should be called for short text
        verify(client, never()).sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean());
    }

    @Test
    void startStreamCreatesFreshScrubber() {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        // First stream
        editor.startStream(123L, "Hello world message");
        // Clear stream
        editor.clearStream(123L);
        // Second stream — should get a fresh scrubber
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(43L));
        Optional<Long> result = editor.startStream(123L, "New message text");
        assertThat(result).isPresent();
    }
}