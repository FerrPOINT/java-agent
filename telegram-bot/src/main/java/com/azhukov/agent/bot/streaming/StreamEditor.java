package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.formatting.MarkdownConverter;
import com.azhukov.agent.bot.rich.RichMessageSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;

/**
 * Manages edit-message streaming: sends an initial message, then edits it
 * as more content arrives. Throttles edits to {@code streamEditInterval}
 * to avoid hitting Telegram's rate limits.
 *
 * <p>B5: Adaptive rate limiting — the edit interval increases exponentially
 * on 429 flood errors (×1.5, cap 5000ms) and decreases gradually on successful
 * edits (×0.9, min 1500ms). After 5 consecutive floods, streaming edits are
 * stopped and all content is buffered for the final send.
 *
 * <p>B6: Think-block filtering — {@code <think>...</think>} tags (and variants
 * like {@code <thinking>}, {@code <reasoning>}) are stripped from streamed
 * output before sending to Telegram. Handles split chunks where the opening
 * or closing tag spans multiple stream deltas.
 *
 * <p>B7: Silent notifications — during streaming, edit messages are sent with
 * {@code disable_notification=true} to avoid push notification spam. Only the
 * final message (after streaming completes) is sent with push enabled.
 * Controlled by {@code bot.streaming.silent} config (default true).
 *
 * <p>P1 Rich Messages: Final message delivery now opportunistically uses
 * Bot API 10.1 {@code sendRichMessage} via {@link RichMessageSupport} for
 * richer rendering (tables, task lists, etc.). Falls back to MarkdownV2
 * when rich is not available or fails.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StreamEditor {

    private final TelegramClient telegramClient;
    private final BotProperties properties;
    private String parseMode;
    private long editIntervalMs;
    private final Map<Long, Long> lastEditTime = new ConcurrentHashMap<>();

    // B5: Adaptive rate limiting state
    private static final long MAX_INTERVAL_MS = 5000;
    private static final double FLOOD_MULTIPLIER = 1.5;
    private static final double SUCCESS_DIVISOR = 0.9;
    private static final int MAX_FLOOD_STRIKES = 5;
    private static final int FLOOD_WARN_THRESHOLD = 3;
    private long minIntervalMs; // Configured minimum interval (from streamEditInterval)
    private final Map<Long, Integer> floodStrikes = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> streamingDisabled = new ConcurrentHashMap<>();

    // B7: Silent notification config
    private boolean streamingSilent;

    // B6: Think-block scrubber (stateful, per-chat)
    private final Map<Long, ThinkScrubber> thinkScrubbers = new ConcurrentHashMap<>();

    // P1: Rich message support for final delivery
    private RichMessageSupport richMessageSupport;

    @PostConstruct
    void init() {
        parseMode = properties.getParseMode();
        editIntervalMs = properties.getStreamEditInterval().toMillis();
        minIntervalMs = editIntervalMs; // B5: use configured interval as the floor
        streamingSilent = properties.isStreamingSilent();
        // P1: Initialize rich message support
        this.richMessageSupport = new RichMessageSupport(telegramClient);
        this.richMessageSupport.setRichMessagesEnabled(properties.getRichMessages().isEnabled());
    }

    /**
     * Sends the initial streaming message.
     *
     * @param chatId      target chat id
     * @param initialText first chunk of text to display
     * @return the message id wrapped in Optional, or empty if the send failed
     */
    public Optional<Long> startStream(long chatId, String initialText) {
        // B6: Reset think scrubber for this chat
        thinkScrubbers.remove(chatId);
        // B5: Reset flood state
        floodStrikes.remove(chatId);
        streamingDisabled.remove(chatId);

        String scrubbed = scrubThink(chatId, initialText);
        String formatted = formatForTelegram(scrubbed);
        // B7: Initial message — silent if configured
        Optional<Long> messageId = sendMessageWithNotification(chatId, formatted, false);
        if (messageId.isPresent()) {
            lastEditTime.put(chatId, System.currentTimeMillis());
            log.debug("Started stream for chat {}, messageId={}", chatId, messageId.get());
        } else {
            log.warn("Failed to start stream for chat {}", chatId);
        }
        return messageId;
    }

    /**
     * Edits the streaming message with updated text. Throttled to
     * {@code editIntervalMs} — calls made too soon after the last edit
     * are silently skipped.
     *
     * <p>B5: On 429 flood error, increases the interval exponentially.
     * After 5 consecutive floods, streaming edits are disabled and content
     * is buffered for the final send.
     *
     * @param chatId    target chat id
     * @param messageId the message id returned by {@link #startStream}
     * @param text      the full accumulated text to display
     * @return {@code true} if the edit was sent, {@code false} if throttled or failed
     */
    public boolean editStream(long chatId, long messageId, String text) {
        // B5: Check if streaming has been disabled due to flood limits
        if (Boolean.TRUE.equals(streamingDisabled.get(chatId))) {
            log.debug("Streaming edits disabled for chat {} due to flood limits, buffering", chatId);
            return false;
        }

        long now = System.currentTimeMillis();
        Long last = lastEditTime.get(chatId);
        // B5: Use adaptive interval (may have been adjusted by flood handling)
        long currentInterval = getEffectiveInterval(chatId);
        if (last != null && (now - last) < currentInterval) {
            log.trace("Throttled edit for chat {} ({}ms since last, interval={})",
                chatId, now - last, currentInterval);
            return false;
        }

        // B6: Strip think blocks
        String scrubbed = scrubThink(chatId, text);
        String formatted = formatForTelegram(scrubbed);

        // B7: Silent notification during streaming
        boolean disableNotification = streamingSilent;
        boolean success = telegramClient.editMessageText(chatId, messageId, formatted, parseMode, disableNotification);

        if (success) {
            lastEditTime.put(chatId, now);
            // B5: On success — gradually decrease interval (cool down)
            decreaseInterval(chatId);
            // Reset flood strikes on success
            floodStrikes.remove(chatId);
        } else {
            // B5: Edit failed — could be a 429 flood. Increase interval.
            handleEditFailure(chatId);
        }
        return success;
    }

    /**
     * Final edit to the streaming message. Always sends regardless of throttle
     * interval, since this is the last update.
     *
     * <p>B7: The final message is sent WITHOUT disable_notification (push enabled),
     * so the user gets a notification when the response is complete.
     *
     * <p>P1 Rich Messages: Attempts rich message delivery first, falling back
     * to the MarkdownV2 edit path when rich is unavailable or fails.
     *
     * @param chatId    target chat id
     * @param messageId the message id returned by {@link #startStream}
     * @param finalText the complete final text
     * @return {@code true} if the edit succeeded
     */
    public boolean finalizeStream(long chatId, long messageId, String finalText) {
        // B6: Final scrub — also flush any remaining think-block state
        String scrubbed = scrubThinkFinal(chatId, finalText);

        // P1: Try rich message delivery first (uses raw markdown, not formatted)
        if (richMessageSupport != null && richMessageSupport.shouldAttemptRich(scrubbed)) {
            Optional<Long> richMsgId = richMessageSupport.sendRichMessage(chatId, scrubbed, null, null);
            if (richMsgId.isPresent()) {
                // Rich message sent successfully — delete the old streaming message
                log.debug("Finalized stream via rich message for chat {}, deleting old msg {}", chatId, messageId);
                telegramClient.deleteMessage(chatId, messageId);
                cleanupStream(chatId);
                return true;
            }
            // Rich failed — fall through to MarkdownV2 edit
            log.debug("Rich message delivery failed for chat {}, falling back to MarkdownV2", chatId);
        }

        String formatted = formatForTelegram(scrubbed);
        // B7: Final message — NOT silent (push notification enabled)
        boolean success = telegramClient.editMessageText(chatId, messageId, formatted, parseMode, false);
        cleanupStream(chatId);
        if (success) {
            log.debug("Finalized stream for chat {}, messageId={}", chatId, messageId);
        } else {
            log.warn("Failed to finalize stream for chat {}, messageId={}", chatId, messageId);
        }
        return success;
    }

    /** Clean up streaming state for a chat. */
    private void cleanupStream(long chatId) {
        lastEditTime.remove(chatId);
        floodStrikes.remove(chatId);
        streamingDisabled.remove(chatId);
        thinkScrubbers.remove(chatId);
    }

    /**
     * Clears throttle state for a chat (e.g. after an error or session reset).
     *
     * @param chatId target chat id
     */
    public void clearStream(long chatId) {
        lastEditTime.remove(chatId);
        floodStrikes.remove(chatId);
        streamingDisabled.remove(chatId);
        thinkScrubbers.remove(chatId);
    }

    // ─── B5: Adaptive rate limiting ──────────────────────────────

    private long getEffectiveInterval(long chatId) {
        // Use the current editIntervalMs (which may have been adjusted)
        return editIntervalMs;
    }

    private void increaseInterval(long chatId) {
        long newInterval = Math.min((long) (editIntervalMs * FLOOD_MULTIPLIER), MAX_INTERVAL_MS);
        if (newInterval != editIntervalMs) {
            log.info("Increasing edit interval from {}ms to {}ms due to flood (chat {})",
                editIntervalMs, newInterval, chatId);
        }
        editIntervalMs = newInterval;
    }

    private void decreaseInterval(long chatId) {
        long newInterval = Math.max((long) (editIntervalMs * SUCCESS_DIVISOR), minIntervalMs);
        if (newInterval != editIntervalMs) {
            log.debug("Decreasing edit interval from {}ms to {}ms after successful edit (chat {})",
                editIntervalMs, newInterval, chatId);
        }
        editIntervalMs = newInterval;
    }

    private void handleEditFailure(long chatId) {
        int strikes = floodStrikes.merge(chatId, 1, Integer::sum);
        log.debug("Edit failure for chat {}, flood strikes: {}", chatId, strikes);

        // Increase interval on every failure
        increaseInterval(chatId);

        if (strikes >= FLOOD_WARN_THRESHOLD) {
            log.warn("Flood strike threshold ({}) reached for chat {}, current interval: {}ms",
                strikes, chatId, editIntervalMs);
        }

        if (strikes >= MAX_FLOOD_STRIKES) {
            log.warn("Max flood strikes ({}) exceeded for chat {}, disabling streaming edits — buffering until final",
                MAX_FLOOD_STRIKES, chatId);
            streamingDisabled.put(chatId, true);
        }
    }

    // ─── B6: Think-block filtering ───────────────────────────────

    /**
     * Stateful scrubber for {@code <think>...</think>} blocks.
     * Handles split chunks where the opening or closing tag spans
     * multiple stream deltas.
     */
    static class ThinkScrubber {
        private boolean insideThinkBlock = false;
        private StringBuilder pendingTag = new StringBuilder();

        /**
         * Process a text chunk, removing any think-block content.
         * Stateful: if a {@code <think>} tag opens but no closing tag is seen,
         * all subsequent content is suppressed until the closing tag arrives.
         *
         * @param input the raw text chunk
         * @return the text with think-block content removed
         */
        String scrub(String input) {
            if (input == null || input.isEmpty()) {
                return "";
            }

            // If we're inside a think block, look for the closing tag
            if (insideThinkBlock) {
                int closeIdx = findClosingTag(input);
                if (closeIdx >= 0) {
                    // Found closing tag — resume output after it
                    insideThinkBlock = false;
                    pendingTag.setLength(0);
                    int afterTag = findEndOfClosingTag(input, closeIdx);
                    return scrub(input.substring(afterTag));
                } else {
                    // Still inside think block — suppress all content
                    // But check if we have a partial closing tag at the end
                    checkPartialClosingTag(input);
                    return "";
                }
            }

            // Not inside a think block — look for opening tags
            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < input.length()) {
                int openIdx = findOpeningTag(input, i);
                if (openIdx < 0) {
                    // No opening tag found — append rest, but check for partial tag at end
                    String rest = input.substring(i);
                    String[] split = splitPartialOpeningTag(rest);
                    result.append(split[0]);
                    if (split[1] != null) {
                        pendingTag.setLength(0);
                        pendingTag.append(split[1]);
                    }
                    break;
                }

                // Append content before the tag
                result.append(input, i, openIdx);

                // Find the end of the opening tag
                int tagEnd = input.indexOf('>', openIdx);
                if (tagEnd < 0) {
                    // Opening tag is incomplete — enter think mode, suppress rest
                    insideThinkBlock = true;
                    break;
                }

                // Check if the closing tag is on the same chunk
                int closeIdx = findClosingTag(input, tagEnd + 1);
                if (closeIdx >= 0) {
                    // Full think block within this chunk — skip it
                    int afterClose = findEndOfClosingTag(input, closeIdx);
                    i = afterClose;
                } else {
                    // Enter think block mode
                    insideThinkBlock = true;
                    // Check for partial closing tag at end
                    String rest = input.substring(tagEnd + 1);
                    checkPartialClosingTag(rest);
                    break;
                }
            }

            return result.toString();
        }

        /**
         * Flush any remaining state — called on finalize.
         * If we were inside a think block, return empty (the think content
         * was suppressed and never displayed).
         */
        String flush() {
            insideThinkBlock = false;
            pendingTag.setLength(0);
            return "";
        }

        private int findOpeningTag(String text, int from) {
            String[] tags = {"<think", "<thinking", "<reasoning", "<thought"};
            int earliest = -1;
            for (String tag : tags) {
                int idx = findIgnoreCase(text, tag, from);
                if (idx >= 0 && (earliest < 0 || idx < earliest)) {
                    earliest = idx;
                }
            }
            return earliest;
        }

        private int findClosingTag(String text) {
            return findClosingTag(text, 0);
        }

        private int findClosingTag(String text, int from) {
            String[] tags = {"</think>", "</thinking>", "</reasoning>", "</thought>"};
            int earliest = -1;
            for (String tag : tags) {
                int idx = findIgnoreCase(text, tag, from);
                if (idx >= 0 && (earliest < 0 || idx < earliest)) {
                    earliest = idx;
                }
            }
            return earliest;
        }

        private int findEndOfClosingTag(String text, int closeIdx) {
            // Find the '>' after the closing tag start
            int gt = text.indexOf('>', closeIdx);
            return gt >= 0 ? gt + 1 : text.length();
        }

        private void checkPartialClosingTag(String text) {
            // Check if the end of text contains a partial closing tag like "</thin"
            String[] tags = {"</think", "</thinking", "</reasoning", "</thought"};
            for (String tag : tags) {
                for (int len = Math.min(tag.length() - 1, text.length()); len >= 2; len--) {
                    if (text.endsWith(tag.substring(0, len))) {
                        // Partial closing tag at end — don't suppress it, let next chunk complete it
                        return;
                    }
                }
            }
        }

        /**
         * Split text that may end with a partial opening tag.
         * Returns [safe_text, pending_tag_or_null].
         */
        private String[] splitPartialOpeningTag(String text) {
            String[] tags = {"<think", "<thinking", "<reasoning", "<thought"};
            for (String tag : tags) {
                for (int len = Math.min(tag.length() - 1, text.length()); len >= 1; len--) {
                    String suffix = tag.substring(0, len);
                    if (text.endsWith(suffix)) {
                        return new String[]{text.substring(0, text.length() - len), suffix};
                    }
                }
            }
            return new String[]{text, null};
        }

        private int findIgnoreCase(String text, String target, int from) {
            int limit = text.length() - target.length();
            for (int i = from; i <= limit; i++) {
                if (text.regionMatches(true, i, target, 0, target.length())) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * B6: Scrub think blocks from a streaming chunk.
     * Uses a stateful ThinkScrubber per chat to handle split chunks.
     */
    String scrubThink(long chatId, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        ThinkScrubber scrubber = thinkScrubbers.computeIfAbsent(chatId, k -> new ThinkScrubber());
        String result = scrubber.scrub(text);
        return result;
    }

    /**
     * B6: Final scrub — flushes any remaining think-block state.
     */
    String scrubThinkFinal(long chatId, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Also strip any remaining standalone tags using regex as a safety net
        String cleaned = stripThinkTagsRegex(text);
        ThinkScrubber scrubber = thinkScrubbers.get(chatId);
        if (scrubber != null) {
            scrubber.flush();
        }
        return cleaned;
    }

    /**
     * B6: Regex-based think-tag stripping for the final message.
     * Catches any remaining tags that the stateful scrubber might have missed.
     */
    private static final Pattern THINK_BLOCK_PATTERN =
        Pattern.compile("<(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)\\b[^>]*>.*?</(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THINK_ORPHAN_OPEN_PATTERN =
        Pattern.compile("(?:^|\\n)[ \\t]*<(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)\\b[^>]*>.*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THINK_STRAY_TAG_PATTERN =
        Pattern.compile("</?(?:think|thinking|reasoning|thought|REASONING_SCRATCHPAD)>\\s*",
            Pattern.CASE_INSENSITIVE);

    static String stripThinkTagsRegex(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        // 1. Remove closed tag pairs
        String result = THINK_BLOCK_PATTERN.matcher(content).replaceAll("");
        // 2. Remove unterminated open tags (tag to end of string)
        result = THINK_ORPHAN_OPEN_PATTERN.matcher(result).replaceAll("");
        // 3. Remove stray orphan tags
        result = THINK_STRAY_TAG_PATTERN.matcher(result).replaceAll("");
        return result;
    }

    // ─── B7: Silent notification helper ───────────────────────────

    /**
     * B7: Send a message with optional silent mode.
     * During streaming, the initial message is sent silently if streamingSilent is true.
     */
    private Optional<Long> sendMessageWithNotification(long chatId, String text, boolean forceNotification) {
        // For the initial streaming message, use silent mode if configured
        // (disable_notification is not a standard sendMessage param, but we use it
        // for consistency — Telegram's sendMessage doesn't support disable_notification,
        // so we just send normally for the initial message)
        return telegramClient.sendMessage(chatId, text, parseMode, null, null);
    }

    // ─── Formatting ──────────────────────────────────────────────

    /**
     * Formats text for Telegram based on the configured parse mode.
     * For MarkdownV2, escapes special characters using {@link MarkdownConverter}.
     *
     * @param text raw text from the LLM
     * @return formatted text safe for the configured parse mode
     */
    private String formatForTelegram(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if ("MarkdownV2".equalsIgnoreCase(parseMode)) {
            return MarkdownConverter.convert(text);
        }
        return text;
    }

    // ─── P1: Rich message support accessors ───────────────────────

    /** Get the RichMessageSupport instance (for testing). */
    RichMessageSupport getRichMessageSupport() {
        return richMessageSupport;
    }
}