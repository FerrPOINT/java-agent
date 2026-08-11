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

    // Use Unicode escapes for angle brackets to avoid HTML/XML interpretation issues
    private static final String LT = "\u003C";
    private static final String GT = "\u003E";
    private static final String THINK_OPEN = LT + "think" + GT;
    private static final String THINK_CLOSE = LT + "/think" + GT;
    private static final String THINKING_OPEN = LT + "thinking" + GT;
    private static final String THINKING_CLOSE = LT + "/thinking" + GT;
    private static final String REASONING_OPEN = LT + "reasoning" + GT;
    private static final String REASONING_CLOSE = LT + "/reasoning" + GT;
    private static final String REASONING_SCRATCHPAD_OPEN = LT + "reasoning_scratchpad" + GT;
    private static final String REASONING_SCRATCHPAD_CLOSE = LT + "/reasoning_scratchpad" + GT;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingSilent(true);
        props.setHeartbeatIntervalSeconds(1); // Short for testing
        editor = new StreamEditor(client, props);
        editor.init();
        // Mock getMe to return no rich messages support, so existing tests use legacy path
        TelegramResponse meResponse = mock(TelegramResponse.class);
        when(meResponse.isSuccess()).thenReturn(true);
        when(meResponse.resultAsMap()).thenReturn(Map.of()); // no supports_rich_messages field
        when(client.callApi("getMe", Map.of())).thenReturn(Optional.of(meResponse));
        // Default: last API error code = 0 (success)
        when(client.getLastApiErrorCode()).thenReturn(0);
    }

    // --- Streaming cursor tests ---

    @Test
    void editStream_appendsStreamingCursor() {
        String cursor = " \u2589"; // \u2589 = ▉
        when(client.editMessageText(123L, 42L, "Hello" + cursor, "MarkdownV2", true))
            .thenReturn(true);

        boolean result = editor.editStream(123L, 42L, "Hello");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Hello" + cursor, "MarkdownV2", true);
    }

    @Test
    void editStream_appendsConfigurableCursor() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamCursor(" \u23F3"); // ⏳
        StreamEditor customEditor = new StreamEditor(client, props);
        customEditor.init();

        String cursor = " \u23F3";
        when(client.editMessageText(123L, 42L, "Hello" + cursor, "MarkdownV2", true))
            .thenReturn(true);

        boolean result = customEditor.editStream(123L, 42L, "Hello");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Hello" + cursor, "MarkdownV2", true);
    }

    @Test
    void finalizeStream_stripsStreamingCursor() {
        // finalizeStream should NOT append the cursor
        when(client.editMessageText(123L, 42L, "Final text", "MarkdownV2", false))
            .thenReturn(true);

        boolean result = editor.finalizeStream(123L, 42L, "Final text");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Final text", "MarkdownV2", false);
    }

    // --- Basic start/edit/finalize tests ---

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
        String cursor = " \u2589";
        when(client.editMessageText(123L, 42L, "Updated" + cursor, "MarkdownV2", true))
            .thenReturn(true);

        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated" + cursor, "MarkdownV2", true);
    }

    @Test
    void editStream_throttlesWhenCalledTooSoon() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        // Immediately call editStream -- should be throttled
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isFalse();
        verify(client, never()).editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void editStream_allowsAfterInterval() throws InterruptedException {
        String cursor = " \u2589";
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(123L, 42L, "Updated" + cursor, "MarkdownV2", true))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        Thread.sleep(120); // wait past 100ms interval
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated" + cursor, "MarkdownV2", true);
    }

    @Test
    void finalizeStream_alwaysSendsRegardlessOfThrottle() {
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(123L, 42L, "Final text", "MarkdownV2", false))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        boolean result = editor.finalizeStream(123L, 42L, "Final text");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Final text", "MarkdownV2", false);
    }

    @Test
    void finalizeStream_returnsFalseOnFailure() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false);
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.empty());

        boolean result = editor.finalizeStream(123L, 42L, "Final text");

        assertThat(result).isFalse();
    }

    // --- finalizeStream fallback test ---

    @Test
    void finalizeStream_fallsBackToSendMessageOnEditFailure() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false);
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(99L));

        boolean result = editor.finalizeStream(123L, 42L, "Final text");

        assertThat(result).isTrue();
        verify(client).editMessageText(eq(123L), eq(42L), anyString(), eq("MarkdownV2"), eq(false));
        verify(client).sendMessage(eq(123L), anyString(), eq("MarkdownV2"), any(), any());
        verify(client).deleteMessage(123L, 42L);
    }

    // --- Clear stream test ---

    @Test
    void clearStream_removesThrottleState() {
        String cursor = " \u2589";
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        editor.clearStream(123L);
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated" + cursor, "MarkdownV2", true);
    }

    @Test
    void fullStreamSequence_startEditFinalize() throws InterruptedException {
        String cursor = " \u2589";
        when(client.sendMessage(123L, "Part 1", "MarkdownV2", null, null))
            .thenReturn(Optional.of(99L));
        when(client.editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"), anyBoolean()))
            .thenReturn(true);

        Optional<Long> msgId = editor.startStream(123L, "Part 1");
        assertThat(msgId).contains(99L);

        Thread.sleep(120);
        boolean edited = editor.editStream(123L, 99L, "Part 1 Part 2");
        assertThat(edited).isTrue();

        Thread.sleep(120);
        boolean edited2 = editor.editStream(123L, 99L, "Part 1 Part 2 Part 3");
        assertThat(edited2).isTrue();

        boolean finalized = editor.finalizeStream(123L, 99L, "Part 1 Part 2 Part 3 FINAL");
        assertThat(finalized).isTrue();

        verify(client).sendMessage(123L, "Part 1", "MarkdownV2", null, null);
        verify(client, times(2)).editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"), eq(true));
        verify(client, times(1)).editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"), eq(false));
    }

    // --- B6: Think-block filtering tests ---

    @Test
    void scrubThink_stripsCompleteThinkBlock() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String input = "Let me think. " + THINK_OPEN + "reasoning here" + THINK_CLOSE + " Here is my answer.";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Here is my answer.");
        assertThat(result).doesNotContain("reasoning");
        assertThat(result).contains("Let me think.");
    }

    @Test
    void scrubThink_handlesSplitChunks() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String r1 = scrubber.scrub("Hello " + THINK_OPEN + "this is reasoning");
        assertThat(r1).contains("Hello ");
        assertThat(r1).doesNotContain("reasoning");

        String r2 = scrubber.scrub(" more reasoning continues");
        assertThat(r2).isEmpty();

        String r3 = scrubber.scrub(THINK_CLOSE + " Real answer here.");
        assertThat(r3).contains("Real answer here.");
        assertThat(r3).doesNotContain("reasoning");
    }

    @Test
    void scrubThink_handlesThinkingTag() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String input = THINKING_OPEN + "internal thoughts" + THINKING_CLOSE + "Visible response";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Visible response");
        assertThat(result).doesNotContain("internal thoughts");
    }

    @Test
    void scrubThink_caseInsensitive() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String open = LT + "THINK" + GT;
        String close = LT + "/THINK" + GT;
        String input = open + "uppercase thinking" + close + " Answer.";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Answer.");
        assertThat(result).doesNotContain("uppercase thinking");
    }

    @Test
    void stripThinkTagsRegex_removesAllVariants() {
        assertThat(StreamEditor.stripThinkTagsRegex(THINK_OPEN + "abc" + THINK_CLOSE + "rest")).isEqualTo("rest");
        assertThat(StreamEditor.stripThinkTagsRegex(THINKING_OPEN + "abc" + THINKING_CLOSE + "rest")).isEqualTo("rest");
        assertThat(StreamEditor.stripThinkTagsRegex(REASONING_OPEN + "abc" + REASONING_CLOSE + "rest")).isEqualTo("rest");
        assertThat(StreamEditor.stripThinkTagsRegex("before" + THINK_OPEN + "abc" + THINK_CLOSE + "after")).isEqualTo("beforeafter");
        assertThat(StreamEditor.stripThinkTagsRegex(THINK_OPEN + "only thinking")).isEqualTo("");
        assertThat(StreamEditor.stripThinkTagsRegex(THINK_CLOSE + "stray")).isEqualTo("stray");
        assertThat(StreamEditor.stripThinkTagsRegex("no tags here")).isEqualTo("no tags here");
    }

    @Test
    void stripThinkTagsRegex_removesReasoningScratchpad() {
        assertThat(StreamEditor.stripThinkTagsRegex(
            REASONING_SCRATCHPAD_OPEN + "secret" + REASONING_SCRATCHPAD_CLOSE + "visible")).isEqualTo("visible");
        assertThat(StreamEditor.stripThinkTagsRegex(
            "before" + REASONING_SCRATCHPAD_OPEN + "hidden" + REASONING_SCRATCHPAD_CLOSE + "after"))
            .isEqualTo("beforeafter");
    }

    @Test
    void scrubThink_handlesReasoningScratchpadTag() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String input = REASONING_SCRATCHPAD_OPEN + "secret thinking" + REASONING_SCRATCHPAD_CLOSE + "Answer";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Answer");
        assertThat(result).doesNotContain("secret thinking");
    }

    @Test
    void editStream_scrubsThinkBlocksBeforeSending() {
        String cursor = " \u2589";
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String input = THINK_OPEN + "reasoning" + THINK_CLOSE + "Hello world";
        boolean result = editor.editStream(123L, 42L, input);

        assertThat(result).isTrue();
        verify(client).editMessageText(eq(123L), eq(42L), eq("Hello world" + cursor), eq("MarkdownV2"), eq(true));
    }

    @Test
    void finalizeStream_scrubsThinkBlocks() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String input = THINK_OPEN + "secret" + THINK_CLOSE + "Final answer";
        boolean result = editor.finalizeStream(123L, 42L, input);

        assertThat(result).isTrue();
        verify(client).editMessageText(eq(123L), eq(42L), eq("Final answer"), eq("MarkdownV2"), eq(false));
    }

    // --- B5: Adaptive rate limiting tests ---

    @Test
    void editStream_increasesIntervalOnFailure() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false);
        when(client.getLastApiErrorCode()).thenReturn(429);

        editor.editStream(123L, 42L, "text");

        editor.clearStream(123L);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false);
        when(client.getLastApiErrorCode()).thenReturn(429);

        for (int i = 0; i < 5; i++) {
            editor.editStream(123L, 42L, "attempt " + i);
        }

        boolean result = editor.editStream(123L, 42L, "attempt 6");
        assertThat(result).isFalse();
    }

    @Test
    void editStream_decreasesIntervalOnSuccess() throws InterruptedException {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        Thread.sleep(110);
        boolean result = editor.editStream(123L, 42L, "text");
        assertThat(result).isTrue();

        Thread.sleep(110);
        boolean result2 = editor.editStream(123L, 42L, "text2");
        assertThat(result2).isTrue();
    }

    // --- 400 vs 429 tests ---

    @Test
    void editStream_400DoesNotIncrementFloodStrikes() {
        // First edit fails with 400, then truncated retry succeeds
        when(client.getLastApiErrorCode()).thenReturn(400);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false, true);

        boolean result = editor.editStream(123L, 42L, "Hello world");
        assertThat(result).isTrue(); // Should succeed via retry

        // Now try another edit -- should NOT be throttled by flood strikes
        editor.clearStream(123L);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);
        when(client.getLastApiErrorCode()).thenReturn(0);

        boolean result2 = editor.editStream(123L, 42L, "Next text");
        assertThat(result2).isTrue();
    }

    @Test
    void editStream_429IncrementsFloodStrikes() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(false);
        when(client.getLastApiErrorCode()).thenReturn(429);

        for (int i = 0; i < 5; i++) {
            editor.editStream(123L, 42L, "attempt " + i);
        }

        boolean result = editor.editStream(123L, 42L, "attempt 6");
        assertThat(result).isFalse();
    }

    // --- B7: Silent notification tests ---

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
        verify(client).editMessageText(anyLong(), anyLong(), anyString(), anyString(), eq(true));
    }

    @Test
    void finalizeStream_usesPushNotification() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), eq(false)))
            .thenReturn(true);

        boolean result = editor.finalizeStream(123L, 42L, "final");
        assertThat(result).isTrue();
        verify(client).editMessageText(anyLong(), anyLong(), anyString(), anyString(), eq(false));
    }

    // --- Per-chat adaptive rate limit test ---

    @Test
    void editStream_perChatIntervalIsIndependent() throws InterruptedException {
        when(client.editMessageText(eq(111L), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);
        when(client.editMessageText(eq(222L), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        boolean a1 = editor.editStream(111L, 1L, "text A");
        assertThat(a1).isTrue();

        boolean b1 = editor.editStream(222L, 2L, "text B");
        assertThat(b1).isTrue();
    }

    // --- Split during streaming test ---

    @Test
    void editStream_splitsWhenExceedingMaxChars() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingMaxChars(20);
        props.setStreamCursor("|");
        StreamEditor splitEditor = new StreamEditor(client, props);
        splitEditor.init();

        String text = "This is a very long text that exceeds the max chars limit";

        when(client.editMessageText(eq(123L), eq(42L), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);
        when(client.sendMessage(eq(123L), anyString(), anyString(), anyLong(), any()))
            .thenReturn(Optional.of(43L));

        boolean result = splitEditor.editStream(123L, 42L, text);
        assertThat(result).isTrue();

        verify(client).editMessageText(eq(123L), eq(42L), anyString(), eq("MarkdownV2"), anyBoolean());
        verify(client).sendMessage(eq(123L), anyString(), eq("MarkdownV2"), eq(42L), any());
    }

    // --- ThinkScrubber partial closing tag test ---

    @Test
    void scrubThink_handlesPartialClosingTagAcrossChunks() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();

        String r1 = scrubber.scrub("Hello " + THINK_OPEN + " some reasoning");
        assertThat(r1).contains("Hello ");
        assertThat(r1).doesNotContain("reasoning");

        // Partial closing tag at end: "</t" could be start of "</think>"
        String r2 = scrubber.scrub(" more reasoning " + LT + "/t");
        assertThat(r2).isEmpty();

        // Complete the closing tag
        String r3 = scrubber.scrub("hink" + GT + " Real answer");
        assertThat(r3).contains("Real answer");
        assertThat(r3).doesNotContain("reasoning");
    }

    @Test
    void scrubThink_handlesReasoningScratchpadSplitChunks() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();

        String r1 = scrubber.scrub("Hello " + REASONING_SCRATCHPAD_OPEN + "secret thoughts");
        assertThat(r1).contains("Hello ");
        assertThat(r1).doesNotContain("secret");

        String r2 = scrubber.scrub(" more secrets " + REASONING_SCRATCHPAD_CLOSE + " Real answer");
        assertThat(r2).contains("Real answer");
        assertThat(r2).doesNotContain("secret");
    }
}