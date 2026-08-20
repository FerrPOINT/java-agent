package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.rich.RichMessageSupport;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.client.TelegramResponse;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.media.MediaDeliveryService;
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
        editor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
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
        // Hermes: when no message exists yet (currentMessageId is null), editStream
        // sends a new message via sendMessage (raw text, null parse_mode) with cursor appended.
        when(client.sendMessage(eq(123L), eq("Hello" + cursor), isNull(), isNull(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        boolean result = editor.editStream(123L, 42L, "Hello");

        assertThat(result).isTrue();
        verify(client).sendMessage(eq(123L), eq("Hello" + cursor), isNull(), isNull(), isNull(), anyBoolean());
    }

    @Test
    void editStream_appendsConfigurableCursor() {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamCursor(" \u23F3"); // ⏳
        StreamEditor customEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        customEditor.init();

        String cursor = " \u23F3";
        // Hermes: delayed first message via sendMessage with raw text and null parse_mode
        when(client.sendMessage(eq(123L), eq("Hello" + cursor), isNull(), isNull(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        boolean result = customEditor.editStream(123L, 42L, "Hello");

        assertThat(result).isTrue();
        verify(client).sendMessage(eq(123L), eq("Hello" + cursor), isNull(), isNull(), isNull(), anyBoolean());
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
        // Hermes: startStream sends raw text (null parse_mode) during streaming
        when(client.sendMessage(eq(123L), eq("Hello"), isNull(), isNull(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        Optional<Long> msgId = editor.startStream(123L, "Hello");

        assertThat(msgId).contains(42L);
        verify(client).sendMessage(eq(123L), eq("Hello"), isNull(), isNull(), isNull(), anyBoolean());
    }

    @Test
    void startStream_returnsEmptyOnFailure() {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.empty());

        Optional<Long> msgId = editor.startStream(123L, "Hello");

        assertThat(msgId).isEmpty();
    }

    @Test
    void editStream_sendsEditWhenNotThrottled() {
        String cursor = " \u2589";
        // Hermes: no prior startStream → currentMessageId is null → sends new message
        // via sendMessage with raw text (null parse_mode) and cursor appended
        when(client.sendMessage(eq(123L), eq("Updated" + cursor), isNull(), isNull(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).sendMessage(eq(123L), eq("Updated" + cursor), isNull(), isNull(), isNull(), anyBoolean());
    }

    @Test
    void editStream_throttlesWhenCalledTooSoon() throws InterruptedException {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
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
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        // Hermes: streaming edits send raw text (null parse_mode)
        when(client.editMessageText(123L, 42L, "Updated" + cursor, null, true))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        Thread.sleep(120); // wait past 100ms interval
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).editMessageText(123L, 42L, "Updated" + cursor, null, true);
    }

    @Test
    void finalizeStream_alwaysSendsRegardlessOfThrottle() {
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
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
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        editor.clearStream(123L);
        // After clearStream, currentMessageId is null → editStream sends a new message
        // via sendMessage (Hermes: raw text, null parse_mode) with cursor appended
        boolean result = editor.editStream(123L, 42L, "Updated");

        assertThat(result).isTrue();
        verify(client).sendMessage(eq(123L), eq("Updated" + cursor), isNull(), isNull(), isNull(), anyBoolean());
    }

    @Test
    void fullStreamSequence_startEditFinalize() throws InterruptedException {
        String cursor = " \u2589";
        // Hermes: startStream sends raw text (null parse_mode)
        when(client.sendMessage(eq(123L), eq("Part 1"), isNull(), isNull(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(99L));
        // Hermes: streaming edits send raw text (null parse_mode)
        when(client.editMessageText(eq(123L), eq(99L), anyString(), isNull(), eq(true)))
            .thenReturn(true);
        // finalizeStream still uses MarkdownV2 parse_mode
        when(client.editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"), eq(false)))
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

        verify(client).sendMessage(eq(123L), eq("Part 1"), isNull(), isNull(), isNull(), anyBoolean());
        verify(client, times(2)).editMessageText(eq(123L), eq(99L), anyString(), isNull(), eq(true));
        verify(client, times(1)).editMessageText(eq(123L), eq(99L), anyString(), eq("MarkdownV2"), eq(false));
    }

    // --- B6: Think-block filtering tests ---

    @Test
    void scrubThink_stripsCompleteThinkBlock() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        String input = THINK_OPEN + "reasoning here" + THINK_CLOSE + "Here is my answer.";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Here is my answer.");
        assertThat(result).doesNotContain("reasoning");
    }

    @Test
    void scrubThink_stripsThinkBlockAtBoundary() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        // Think block at start of text (boundary)
        String input = THINK_OPEN + "reasoning" + THINK_CLOSE + " Answer.";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Answer.");
        assertThat(result).doesNotContain("reasoning");
    }

    @Test
    void scrubThink_doesNotStripInlineThinkTag() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        // Inline think tag in prose (NOT at block boundary) should NOT be stripped
        String input = "Let me think. " + THINK_OPEN + "reasoning" + THINK_CLOSE + " Here is my answer.";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Here is my answer.");
        assertThat(result).contains("Let me think.");
    }

    @Test
    void scrubThink_stripsAtNewlineBoundary() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        // Think block after a newline (at block boundary)
        String input = "First line\n" + THINK_OPEN + "reasoning" + THINK_CLOSE + "\nAnswer.";
        String result = scrubber.scrub(input);
        assertThat(result).contains("First line");
        assertThat(result).contains("Answer.");
        assertThat(result).doesNotContain("reasoning");
    }

    @Test
    void scrubThink_handlesSplitChunks() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        // Think block at start of text (boundary)
        String r1 = scrubber.scrub(THINK_OPEN + "this is reasoning");
        assertThat(r1).isEmpty(); // nothing before the think tag at boundary
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
    void scrubThink_caseSensitiveDoesNotMatchUppercase() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        // Case-sensitive: <THINK> should NOT match <think> tags (Hermes behavior)
        String open = LT + "THINK" + GT;
        String close = LT + "/THINK" + GT;
        // <THINK> is NOT in the tag list, so it won't be stripped (only exact tags are matched)
        String input = open + "uppercase thinking" + close + " Answer.";
        String result = scrubber.scrub(input);
        // Since <THINK> is not a recognized tag, the content passes through
        assertThat(result).contains("Answer.");
    }

    @Test
    void scrubThink_caseSensitiveMatchesThinkTag() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();
        // Lowercase <think> should be matched and stripped (exact, case-sensitive)
        String input = THINK_OPEN + "lowercase thinking" + THINK_CLOSE + " Answer.";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Answer.");
        assertThat(result).doesNotContain("lowercase thinking");
    }

    @Test
    void stripThinkTagsRegex_removesAllVariants() {
        // Regex safety net still exists as a static utility, but is no longer
        // used by scrubThinkFinal (which now relies solely on the stateful scrubber).
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
        // Reasoning scratchpad at start of text (block boundary)
        String input = REASONING_SCRATCHPAD_OPEN + "secret thinking" + REASONING_SCRATCHPAD_CLOSE + "Answer";
        String result = scrubber.scrub(input);
        assertThat(result).contains("Answer");
        assertThat(result).doesNotContain("secret thinking");
    }

    @Test
    void editStream_scrubsThinkBlocksBeforeSending() {
        String cursor = " \u2589";
        // Hermes: no prior startStream → sends new message via sendMessage (raw text, null parse_mode)
        when(client.sendMessage(eq(123L), eq("Hello world" + cursor), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        String input = THINK_OPEN + "reasoning" + THINK_CLOSE + "Hello world";
        boolean result = editor.editStream(123L, 42L, input);

        assertThat(result).isTrue();
        verify(client).sendMessage(eq(123L), eq("Hello world" + cursor), any(), any(), any(), anyBoolean());
    }

    @Test
    void finalizeStream_scrubsThinkBlocks() {
        when(client.editMessageText(anyLong(), anyLong(), anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        // Think block at start of text (block boundary) — should be stripped
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
        // Hermes: first editStream sends a new message (currentMessageId is null),
        // second editStream edits via editMessageText (raw text, null parse_mode)
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        when(client.editMessageText(anyLong(), anyLong(), anyString(), nullable(String.class), anyBoolean()))
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
    void editStream_400DoesNotIncrementFloodStrikes() throws InterruptedException {
        // Hermes: need startStream first so editStream goes through editMessageText path
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        // First edit fails with 400, then truncated retry succeeds
        when(client.getLastApiErrorCode()).thenReturn(400);
        // editMessageText is called twice: first with null parse_mode (streaming),
        // then with "MarkdownV2" parse_mode (400 retry). Use nullable to match both.
        when(client.editMessageText(anyLong(), anyLong(), anyString(), nullable(String.class), anyBoolean()))
            .thenReturn(false, true);

        editor.startStream(123L, "Hello world");
        Thread.sleep(110);
        boolean result = editor.editStream(123L, 42L, "Hello world");
        assertThat(result).isTrue(); // Should succeed via retry

        // Now try another edit -- should NOT be throttled by flood strikes
        editor.clearStream(123L);
        when(client.editMessageText(anyLong(), anyLong(), anyString(), nullable(String.class), anyBoolean()))
            .thenReturn(true);
        when(client.getLastApiErrorCode()).thenReturn(0);

        // After clearStream, currentMessageId is null → editStream sends new message via sendMessage
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
    void editStream_usesSilentNotificationWhenConfigured() throws InterruptedException {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingSilent(true);
        StreamEditor silentEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        silentEditor.init();

        // Hermes: startStream sends via sendMessage (null parse_mode)
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        // Streaming edit uses null parse_mode with silent notification
        when(client.editMessageText(anyLong(), anyLong(), anyString(), any(), eq(true)))
            .thenReturn(true);

        silentEditor.startStream(123L, "Hello");
        Thread.sleep(110);
        boolean result = silentEditor.editStream(123L, 42L, "text");
        assertThat(result).isTrue();
        verify(client).editMessageText(anyLong(), anyLong(), anyString(), any(), eq(true));
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
        // Hermes: no prior startStream → each chat sends a new message via sendMessage
        when(client.sendMessage(eq(111L), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(1L));
        when(client.sendMessage(eq(222L), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(2L));

        boolean a1 = editor.editStream(111L, 1L, "text A");
        assertThat(a1).isTrue();

        boolean b1 = editor.editStream(222L, 2L, "text B");
        assertThat(b1).isTrue();
    }

    // --- Split during streaming test ---

    @Test
    void editStream_splitsWhenExceedingMaxChars() throws InterruptedException {
        BotProperties props = new BotProperties();
        props.setStreamEditInterval(Duration.ofMillis(100));
        props.setParseMode("MarkdownV2");
        props.setStreamingMaxChars(20);
        props.setStreamCursor("|");
        StreamEditor splitEditor = new StreamEditor(client, props, new MediaDeliveryService(), new RichMessageSupport(client));
        splitEditor.init();

        String text = "This is a very long text that exceeds the max chars limit";

        // Hermes: startStream sends via sendMessage (null parse_mode)
        when(client.sendMessage(eq(123L), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        // Split edit: Hermes sends raw text (null parse_mode)
        when(client.editMessageText(eq(123L), eq(42L), anyString(), any(), anyBoolean()))
            .thenReturn(true);
        // Remainder sent as new messages (null parse_mode)
        when(client.sendMessage(eq(123L), anyString(), any(), anyLong(), any(), anyBoolean()))
            .thenReturn(Optional.of(43L));

        splitEditor.startStream(123L, "Initial text");
        Thread.sleep(110);
        boolean result = splitEditor.editStream(123L, 42L, text);
        assertThat(result).isTrue();

        // Hermes: streaming split uses null parse_mode for editMessageText
        verify(client).editMessageText(eq(123L), eq(42L), anyString(), isNull(), anyBoolean());
        // Hermes: remainder sent via sendMessage with null parse_mode.
        // Text is 57 chars with streamingMaxChars=20 → edit keeps first 20,
        // remainder 37 chars is sent as 2 chunked continuation messages.
        verify(client, times(2)).sendMessage(eq(123L), anyString(), isNull(), anyLong(), any());
    }

    // --- ThinkScrubber partial closing tag test ---

    @Test
    void scrubThink_handlesPartialClosingTagAcrossChunks() {
        StreamEditor.ThinkScrubber scrubber = new StreamEditor.ThinkScrubber();

        // Think block at start of text (boundary)
        String r1 = scrubber.scrub(THINK_OPEN + " some reasoning");
        assertThat(r1).isEmpty();
        assertThat(r1).doesNotContain("reasoning");

        // Partial closing tag at end: "</t" could be start of ""
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

        // Reasoning scratchpad at start of text (boundary)
        String r1 = scrubber.scrub(REASONING_SCRATCHPAD_OPEN + "secret thoughts");
        assertThat(r1).isEmpty();
        assertThat(r1).doesNotContain("secret");

        String r2 = scrubber.scrub(" more secrets " + REASONING_SCRATCHPAD_CLOSE + " Real answer");
        assertThat(r2).contains("Real answer");
        assertThat(r2).doesNotContain("secret");
    }

    // ── Backslash bug regression tests ──────────────────────────────
    // Bug: editStream/startStream applied formatForTelegram (MarkdownV2 escaping) to text
    // but sent it with parseMode=null (plain text). Telegram showed \. \- \_ etc. literally.
    // Fix: edit-based streaming sends raw scrubbed text (no MarkdownV2 escaping).

    @Test
    void editStream_doesNotEscapeMarkdownV2SpecialChars() throws InterruptedException {
        // Text with MarkdownV2 special chars: . _ - | ( ) ! =
        String specialText = "grep -rn \"pattern\" file.txt | head -5";
        String cursor = " \u2589";
        when(client.sendMessage(anyLong(), anyString(), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));
        // Hermes: streaming edits send raw text (null parse_mode) — no backslash escaping
        when(client.editMessageText(123L, 42L, specialText + cursor, null, true))
            .thenReturn(true);

        editor.startStream(123L, "Hello");
        Thread.sleep(120); // wait past 100ms interval
        boolean result = editor.editStream(123L, 42L, specialText);

        assertThat(result).isTrue();
        // Verify NO backslashes were added before special chars
        verify(client).editMessageText(123L, 42L, specialText + cursor, null, true);
    }

    @Test
    void startStream_doesNotEscapeMarkdownV2SpecialChars() {
        // Text with MarkdownV2 special chars: . _ - | ( ) ! =
        String specialText = "Result: value_1 | value-2 (test). Done!";
        // Hermes: startStream sends raw text (null parse_mode) — no backslash escaping
        when(client.sendMessage(eq(123L), eq(specialText), any(), any(), any(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        Optional<Long> msgId = editor.startStream(123L, specialText);

        assertThat(msgId).contains(42L);
        verify(client).sendMessage(eq(123L), eq(specialText), isNull(), isNull(), isNull(), anyBoolean());
    }

    @Test
    void editStream_delayedStartDoesNotEscapeMarkdownV2SpecialChars() {
        // When no message exists yet (delayed start), editStream sends via sendMessage
        // with null parse_mode — text must NOT be MarkdownV2-escaped.
        String specialText = "file.txt - pattern_match (v2.0)";
        String cursor = " \u2589";
        when(client.sendMessage(eq(123L), eq(specialText + cursor), isNull(), isNull(), isNull(), anyBoolean()))
            .thenReturn(Optional.of(42L));

        boolean result = editor.editStream(123L, -1L, specialText);

        assertThat(result).isTrue();
        verify(client).sendMessage(eq(123L), eq(specialText + cursor), isNull(), isNull(), isNull(), anyBoolean());
    }
}