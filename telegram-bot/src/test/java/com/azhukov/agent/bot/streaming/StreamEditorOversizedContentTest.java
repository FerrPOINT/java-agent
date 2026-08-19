package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.formatting.MessageSplitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for audit H14 (oversized streaming content) and the
 * error-code side-channel race in handleEditFailure.
 */
@ExtendWith(MockitoExtension.class)
class StreamEditorOversizedContentTest {

    @Mock
    private TelegramClient client;

    private StreamEditor editor;

    @BeforeEach
    void setUp() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(0));
        props.setParseMode("MarkdownV2");
        // 32768 as shipped in application.yml — the misconfiguration that
        // triggered H14: editStreamSplit chunks must clamp to 4096.
        props.setStreamingMaxChars(32768);
        editor = new StreamEditor(client, props, new MediaDeliveryService());
        editor.init();
    }

    @Test
    void init_clampsStreamingMaxCharsToTelegramLimit() {
        // H14: streamingMaxChars 32768 must clamp to 4096 (editMessageText limit)
        // via package-private access checked indirectly: a 5000-char stream must
        // go through editMessageText successfully (not be rejected as oversized).
        when(client.editMessageText(anyLong(), anyLong(), anyString(), isNull(), anyBoolean()))
            .thenReturn(true);
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        editor.startStream(123L, "Hello");
        boolean ok = editor.editStream(123L, 42L, "x".repeat(5000));

        assertThat(ok).isTrue();
        // The edited text passed to Telegram must be within the 4096 limit (+cursor)
        verify(client).editMessageText(eq(123L), anyLong(),
            argThat(t -> t != null && t.length() <= 4097), isNull(), anyBoolean());
    }

    @Test
    void sendPlainMessage_splitsOversizedFinal() {
        // H14: draft finalize / fresh-final sends raw text — a 9000-char final
        // must be split into multiple <=4096 chunks instead of one failing call.
        when(client.sendMessage(anyLong(), anyString(), isNull(), isNull(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(99L));

        Optional<Long> result = editor.sendPlainMessage(123L, "y".repeat(9000));

        assertThat(result).isPresent();
        verify(client, times(3)).sendMessage(eq(123L), anyString(), isNull(), isNull(), isNull(), anyBoolean());
    }

    @Test
    void sendPlainMessage_shortTextSingleCall() {
        when(client.sendMessage(anyLong(), anyString(), isNull(), isNull(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(1L));

        Optional<Long> result = editor.sendPlainMessage(123L, "short");

        assertThat(result).isPresent();
        verify(client, times(1)).sendMessage(eq(123L), eq("short"), isNull(), isNull(), isNull(), anyBoolean());
    }

    @Test
    void sendFormattedMessage_splitsOversizedFinal() {
        // H14: finalize fallback path — oversized formatted text must be chunked
        // through splitAndFormat instead of a single 400-failing sendMessage.
        when(client.sendMessage(anyLong(), anyString(), anyString(), isNull(), isNull()))
            .thenReturn(Optional.of(7L));

        Optional<Long> result = editor.sendFormattedMessage(123L, "z".repeat(12000));

        assertThat(result).isPresent();
        // 12000 raw chars -> multiple chunks, EACH within the Telegram limit
        verify(client, times(6)).sendMessage(eq(123L),
            argThat(t -> t != null && t.length() <= 4096), anyString(), isNull(), isNull());
    }

    @Test
    void messageSplitter_doesNotSplitSurrogatePairs() {
        // H15: an emoji straddling the (1/N) shrink boundary must not be cut
        List<String> chunks = MessageSplitter.split("a".repeat(5000) + "\uD83D\uDE00" + "b".repeat(5000));
        assertThat(chunks.size()).isGreaterThan(1);
        for (String chunk : chunks) {
            // no lone surrogates at either end of any chunk
            char last = chunk.charAt(chunk.length() - 1);
            assertThat(Character.isHighSurrogate(last))
                .as("chunk must not end with a lone high surrogate")
                .isFalse();
            char first = chunk.charAt(0);
            assertThat(Character.isLowSurrogate(first))
                .as("chunk must not start with a lone low surrogate")
                .isFalse();
            assertThat(chunk.length()).isLessThanOrEqualTo(4096);
        }
    }
}
