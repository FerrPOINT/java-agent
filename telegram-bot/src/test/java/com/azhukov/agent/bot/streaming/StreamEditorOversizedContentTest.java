package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.rich.RichMessageSupport;

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
        // triggered H14: preview must clamp to 4096.
        props.setStreamingMaxChars(32768);
        editor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        editor.init();
    }

    @Test
    void init_clampsStreamingMaxCharsToTelegramLimit() {
        // H14: streamingMaxChars 32768 must clamp to 4096 (editMessageText limit)
        // via package-private access checked indirectly: a 5000-char stream must
        // go through editMessageText successfully (not be rejected as oversized).
        when(client.editMessageChecked(anyLong(), anyLong(), anyString(), isNull(), anyBoolean(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.of(42L));
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        editor.startStream(123L, "Hello");
        boolean ok = editor.editStream(123L, 42L, "x".repeat(5000));

        assertThat(ok).isTrue();
        // The edited text passed to Telegram must be within the 4096 limit including the cursor
        verify(client).editMessageChecked(eq(123L), anyLong(),
            argThat(t -> t != null && t.length() <= 4096), isNull(), anyBoolean(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void messageSplitter_doesNotSplitSurrogatePairs() {
        // H15: an emoji straddling the (1/N) shrink boundary must not be cut
        List<String> chunks = MessageSplitter.splitPlainText("a".repeat(5000) + "\uD83D\uDE00" + "b".repeat(5000));
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
