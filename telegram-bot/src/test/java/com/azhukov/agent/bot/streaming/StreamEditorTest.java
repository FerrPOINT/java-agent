package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StreamEditorTest {

    private TelegramClient client;
    private StreamEditor editor;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        editor = new StreamEditor(client, props);
        editor.init();
    }

    @Test
    void startStream_sendsMessageAndReturnsId() {
        when(client.sendMessage(123L, "Hello", "MarkdownV2", null, null))
            .thenReturn(Optional.of(42L));

        Optional<Long> msgId = editor.startStream(123L, "Hello");

        assertThat(msgId).contains(42L);
        verify(client).sendMessage(123L, "Hello", "MarkdownV2", null, null);
    }

    @Test
    void startStream_returnsEmptyOnFailure() {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.empty());

        Optional<Long> msgId = editor.startStream(123L, "Hello");

        assertThat(msgId).isEmpty();
    }

    @Test
    void editStream_sendsEditWhenNotThrottled() {
        when(client.editMessageText(123L, 42L, "Updated", "MarkdownV2"))
            .thenReturn(true);

        // Don't call startStream (which would set throttle time); just call editStream directly
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated", "MarkdownV2");
    }

    @Test
    void editStream_throttlesWhenCalledTooSoon() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString()))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        // Immediately call editStream — should be throttled
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isFalse();
        verify(client, never()).editMessageText(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void editStream_allowsAfterInterval() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(123L, 42L, "Updated", "MarkdownV2"))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        Thread.sleep(120); // wait past 100ms interval
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated", "MarkdownV2");
    }

    @Test
    void finalizeStream_alwaysSendsRegardlessOfThrottle() {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(123L, 42L, "Final text", "MarkdownV2"))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        // Immediately finalize — should not be throttled
        boolean result = editor.finalizeStream(123L, 42L, "Final text");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Final text", "MarkdownV2");
    }

    @Test
    void finalizeStream_returnsFalseOnFailure() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString()))
            .thenReturn(false);

        boolean result = editor.finalizeStream(123L, 42L, "Final text");

        assertThat(result).isFalse();
    }

    @Test
    void clearStream_removesThrottleState() {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString()))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        editor.clearStream(123L);
        // After clearing, edit should go through immediately
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated", "MarkdownV2");
    }

    @Test
    void fullStreamSequence_startEditFinalize() throws InterruptedException {
        when(client.sendMessage(123L, "Part 1", "MarkdownV2", null, null))
            .thenReturn(Optional.of(99L));
        when(client.editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2")))
            .thenReturn(true);

        // Start
        Optional<Long> msgId = editor.startStream(123L, "Part 1");
        assertThat(msgId).contains(99L);

        // Wait and edit
        Thread.sleep(120);
        boolean edited = editor.editStream(123L, 99L, "Part 1 Part 2");
        assertThat(edited).isTrue();

        // Wait and edit again
        Thread.sleep(120);
        boolean edited2 = editor.editStream(123L, 99L, "Part 1 Part 2 Part 3");
        assertThat(edited2).isTrue();

        // Finalize immediately (no throttle)
        boolean finalized = editor.finalizeStream(123L, 99L, "Part 1 Part 2 Part 3 FINAL");
        assertThat(finalized).isTrue();

        // Verify sequence: 1 sendMessage, 2 editMessageText (for edits), 1 editMessageText (for finalize)
        verify(client).sendMessage(123L, "Part 1", "MarkdownV2", null, null);
        verify(client, times(3)).editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"));
    }
}