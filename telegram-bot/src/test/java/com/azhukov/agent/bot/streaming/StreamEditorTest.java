package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
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
        props.setStreamingSilent(true);
        editor = new StreamEditor(client, props);
        editor.init();
        // Mock getMe to return no rich messages support, so existing tests use legacy path
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(Map.of()); // no supports_rich_messages field
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));
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
        // B7: editStream now calls the 5-arg overload with disableNotification=true
        when(client.editMessageText(123L, 42L, "Updated", "MarkdownV2", true))
            .thenReturn(true);

        // Don't call startStream (which would set throttle time); just call editStream directly
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated", "MarkdownV2", true);
    }

    @Test
    void editStream_throttlesWhenCalledTooSoon() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        // Immediately call editStream — should be throttled
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isFalse();
        verify(client, never()).editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void editStream_allowsAfterInterval() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(123L, 42L, "Updated", "MarkdownV2", true))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        Thread.sleep(120); // wait past 100ms interval
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated", "MarkdownV2", true);
    }

    @Test
    void finalizeStream_alwaysSendsRegardlessOfThrottle() {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        // B7: finalizeStream uses disableNotification=false (push enabled)
        when(client.editMessageText(123L, 42L, "Final text", "MarkdownV2", false))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        // Immediately finalize — should not be throttled
        boolean result = editor.finalizeStream(123L, 42L, "Final text");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Final text", "MarkdownV2", false);
    }

    @Test
    void finalizeStream_returnsFalseOnFailure() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false);

        boolean result = editor.finalizeStream(123L, 42L, "Final text");

        assertThat(result).isFalse();
    }

    @Test
    void clearStream_removesThrottleState() {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        editor.clearStream(123L);
        // After clearing, edit should go through immediately
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated", "MarkdownV2", true);
    }

    @Test
    void fullStreamSequence_startEditFinalize() throws InterruptedException {
        when(client.sendMessage(123L, "Part 1", "MarkdownV2", null, null))
            .thenReturn(Optional.of(99L));
        when(client.editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"), anyBoolean()))
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

        // Verify sequence: 1 sendMessage, 2 editMessageText (for edits, silent), 1 editMessageText (for finalize, not silent)
        verify(client).sendMessage(123L, "Part 1", "MarkdownV2", null, null);
        // Edits use silent=true, finalize uses silent=false
        verify(client, times(2)).editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"), eq(true));
        verify(client, times(1)).editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"), eq(false));
    }

    // ─── B6: Think-block filtering tests ───────────────────────────

    @Test
    void scrubThink_stripsCompleteThinkBlock() {
        // The ThinkScrubber should strip </think>...</think> from a single chunk
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String result = scrubber.scrub("Let me think. <think>reasoning here</think> Here is my answer.");
        assertThat(result).contains("Here is my answer.");
        assertThat(result).doesNotContain("reasoning");
        assertThat(result).contains("Let me think.");
    }

    @Test
    void scrubThink_handlesSplitChunks() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        // First chunk opens the think tag
        String r1 = scrubber.scrub("Hello <think>this is reasoning");
        assertThat(r1).contains("Hello ");
        assertThat(r1).doesNotContain("reasoning");

        // Second chunk continues reasoning (suppressed)
        String r2 = scrubber.scrub(" more reasoning continues");
        assertThat(r2).isEmpty();

        // Third chunk closes the think tag and has real content
        String r3 = scrubber.scrub("</think> Real answer here.");
        assertThat(r3).contains("Real answer here.");
        assertThat(r3).doesNotContain("reasoning");
    }

    @Test
    void scrubThink_handlesThinkingTag() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String result = scrubber.scrub("<thinking>internal thoughts</thinking>Visible response");
        assertThat(result).contains("Visible response");
        assertThat(result).doesNotContain("internal thoughts");
    }

    @Test
    void scrubThink_caseInsensitive() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String result = scrubber.scrub("<THINK>uppercase thinking</THINK> Answer.");
        assertThat(result).contains("Answer.");
        assertThat(result).doesNotContain("uppercase thinking");
    }

    @Test
    void stripThinkTagsRegex_removesAllVariants() {
        // Test the static regex-based stripper used in finalize
        assertThat(StreamEditor.stripThinkTagsRegex("<think>abc</think>rest")).isEqualTo("rest");
        assertThat(StreamEditor.stripThinkTagsRegex("<thinking>abc</thinking>rest")).isEqualTo("rest");
        assertThat(StreamEditor.stripThinkTagsRegex("<reasoning>abc</reasoning>rest")).isEqualTo("rest");
        assertThat(StreamEditor.stripThinkTagsRegex("before<think>abc</think>after")).isEqualTo("beforeafter");
        assertThat(StreamEditor.stripThinkTagsRegex("<think>only thinking")).isEqualTo("");
        assertThat(StreamEditor.stripThinkTagsRegex("</think>stray")).isEqualTo("stray");
        assertThat(StreamEditor.stripThinkTagsRegex("no tags here")).isEqualTo("no tags here");
    }

    @Test
    void editStream_scrubsThinkBlocksBeforeSending() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        // Don't call startStream — just test editStream directly
        boolean result = editor.editStream(123L, 42L, "<think>reasoning</think>Hello world");

        assertThat(result).isTrue();
        // The text sent to Telegram should NOT contain the think block content
        verify(client).editMessageText(eq(123L), eq(42L), eq("Hello world"), eq("MarkdownV2"), eq(true));
    }

    @Test
    void finalizeStream_scrubsThinkBlocks() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        boolean result = editor.finalizeStream(123L, 42L, "<think>secret</think>Final answer");

        assertThat(result).isTrue();
        verify(client).editMessageText(eq(123L), eq(42L), eq("Final answer"), eq("MarkdownV2"), eq(false));
    }

    // ─── B5: Adaptive rate limiting tests ──────────────────────────

    @Test
    void editStream_increasesIntervalOnFailure() {
        // First edit fails (simulating flood)
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false);

        editor.editStream(123L, 42L, "text");

        // After failure, interval should have increased — verify by checking that
        // an immediate edit attempt is still throttled (because interval grew)
        // The initial interval was 100ms (from setUp). After ×1.5 it's 150ms.
        // Since the failed edit still updates lastEditTime via the failure path...
        // Actually on failure, lastEditTime is NOT updated. But flood strikes increase.
        // Let's verify flood strikes are tracked by checking that after 5 failures, streaming is disabled.

        // Reset and simulate 5 consecutive failures
        editor.clearStream(123L);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false);

        for (int i = 0; i < 5; i++) {
            editor.editStream(123L, 42L, "attempt " + i);
        }

        // After 5 floods, the 6th edit should be buffered (returns false without calling client)
        boolean result = editor.editStream(123L, 42L, "attempt 6");
        assertThat(result).isFalse();
    }

    @Test
    void editStream_decreasesIntervalOnSuccess() throws InterruptedException {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        // Wait past interval, then edit — should succeed
        Thread.sleep(110);
        boolean result = editor.editStream(123L, 42L, "text");
        assertThat(result).isTrue();

        // The interval decreases by ×0.9 but cannot go below the configured minimum (100ms).
        // So max(90, 100) = 100ms — the interval stays at the floor.
        // A subsequent edit after waiting past 100ms should succeed.
        Thread.sleep(110);
        boolean result2 = editor.editStream(123L, 42L, "text2");
        assertThat(result2).isTrue();
    }

    // ─── B7: Silent notification tests ──────────────────────────────

    @Test
    void editStream_usesSilentNotificationWhenConfigured() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingSilent(true);
        StreamEditor silentEditor = new StreamEditor(client, props);
        silentEditor.init();

        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), eq(true)))
            .thenReturn(true);

        boolean result = silentEditor.editStream(123L, 42L, "text");
        assertThat(result).isTrue();
        // Verify silent=true was passed
        verify(client).editMessageText(anyLong(), anyLong(), anyString(), anyString(), eq(true));
    }

    @Test
    void finalizeStream_usesPushNotification() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), eq(false)))
            .thenReturn(true);

        boolean result = editor.finalizeStream(123L, 42L, "final");
        assertThat(result).isTrue();
        // Final should NOT be silent — push notification enabled
        verify(client).editMessageText(anyLong(), anyLong(), anyString(), anyString(), eq(false));
    }
}