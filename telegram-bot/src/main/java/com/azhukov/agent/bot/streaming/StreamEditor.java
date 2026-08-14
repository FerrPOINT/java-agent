package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.formatting.MarkdownConverter;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.rich.RichMessageSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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
 * <p>B6: Think-block filtering — {@code <think>} tags (and variants
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
 *
 * <p>Streaming cursor: appends {@code ▉} (configurable) to the end of text
 * during editStream to give visual feedback. Stripped on finalizeStream.
 *
 * <p>Heartbeat: during long tool calls, if no tokens arrive within
 * {@code heartbeatIntervalSeconds}, edits the message to show
 * {@code ⏳ Working — Nm} (elapsed minutes).
 *
 * <p>Fresh-final: if streaming exceeds {@code freshFinalTimeoutMs}, the old
 * streaming message is deleted and a new one is sent with the final content,
 * so the message gets a fresh timestamp.
 *
 * <p>Split during streaming: if formatted text exceeds
 * {@code streamingMaxChars}, the current message is edited with the first
 * portion and remaining text is sent as new messages (threaded as replies).
 *
 * <p>400 vs 429: distinguishes "message too long" (400) from rate limit (429).
 * 400 does NOT increment flood strikes — just truncates and retries.
 *
 * <p>Per-chat adaptive rate limit: the edit interval is tracked per-chat in
 * a {@code ConcurrentHashMap<Long, AtomicLong>}, not a single shared field.
 *
 * <p>finalizeStream fallback: if editMessageText returns false, tries
 * sendMessage as a new message.
 *
 * <p>ThinkScrubber: stores partial closing tags across chunks and prepends
 * to the next chunk. Supports {@code <reasoning_scratchpad>} tags.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StreamEditor {

    private final TelegramClient telegramClient;
    private final BotProperties properties;
    private final MediaDeliveryService mediaDeliveryService;
    private String parseMode;
    private final Map<Long, AtomicLong> editIntervalMap = new ConcurrentHashMap<>();
    private long minIntervalMs; // Configured minimum interval (from streamEditInterval)
    private final Map<Long, Long> lastEditTime = new ConcurrentHashMap<>();

    // B5: Adaptive rate limiting — Hermes uses x2 multiplier, 10s max, 3 strikes
    private static final long MAX_INTERVAL_MS = 10000;
    private static final double FLOOD_MULTIPLIER = 2.0;
    private static final int MAX_FLOOD_STRIKES = 3;
    private static final int FLOOD_WARN_THRESHOLD = 3;
    private final Map<Long, Integer> floodStrikes = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> streamingDisabled = new ConcurrentHashMap<>();

    // P2-16: Flood fallback buffer — when streaming is disabled due to flood,
    // buffer the formatted content and send it as a new message on finalize.
    private final Map<Long, StringBuilder> floodFallbackBuffer = new ConcurrentHashMap<>();

    // P2-16: Redundant edit skip — track the last text that was actually
    // sent to Telegram per chat, so we can skip edits where the content
    // hasn't changed.
    private final Map<Long, String> lastSentText = new ConcurrentHashMap<>();

    // B7: Silent notification config
    private boolean streamingSilent;

    // B6: Think-block scrubber (stateful, per-chat)
    private final Map<Long, ThinkScrubber> thinkScrubbers = new ConcurrentHashMap<>();

    // P1: Rich message support for final delivery
    private RichMessageSupport richMessageSupport;

    // Streaming cursor config
    private String streamCursor;

    // Heartbeat
    private int heartbeatIntervalSeconds;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-heartbeat");
        t.setDaemon(true);
        return t;
    });
    // Per-chat heartbeat state: start time, last token time, heartbeat future
    private final Map<Long, Long> streamStartTime = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastTokenTime = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> heartbeatFutures = new ConcurrentHashMap<>();

    // Fresh-final
    private long freshFinalTimeoutMs;

    // Buffer threshold: trigger an edit when accumulated text reaches this many chars
    // since last edit, even if the interval hasn't elapsed.
    private int bufferThreshold;

    // Per-chat buffer tracking: chars accumulated since last edit
    private final Map<Long, Integer> charsSinceLastEdit = new ConcurrentHashMap<>();

    // Per-chat current tool name (for heartbeat display)
    private final Map<Long, String> currentToolName = new ConcurrentHashMap<>();

    // Split during streaming: track current message id per chat
    private final Map<Long, AtomicLong> currentMessageId = new ConcurrentHashMap<>();
    private int streamingMaxChars;

    // S5: Native draft streaming state (per-chat)
    // transport: "auto" (prefer draft, fallback to edit), "draft" (explicit),
    // "edit" (legacy default), "off" (no streaming)
    private String streamingTransport;
    // Per-chat draft streaming resolution: true when draft streaming is active
    private final Map<Long, Boolean> useDraftStreaming = new ConcurrentHashMap<>();
    // Monotonic draft id counter — increments on segment break so the next
    // text segment animates as a fresh preview
    private final Map<Long, Integer> draftIdMap = new ConcurrentHashMap<>();
    // Per-chat draft failure count — after 2 failures, fall back to edit-based
    private final Map<Long, Integer> draftFailures = new ConcurrentHashMap<>();
    // Per-chat chat type ("dm", "group", etc.) — set when stream starts
    private final Map<Long, String> chatTypeMap = new ConcurrentHashMap<>();
    // Class-wide monotonic counter for draft ids (mirrors Hermes _draft_id_counter)
    private static final AtomicInteger draftIdCounter = new AtomicInteger(0);

    @PostConstruct
    void init() {
        parseMode = properties.getParseMode();
        minIntervalMs = properties.getStreamEditInterval().toMillis();
        streamingSilent = properties.isStreamingSilent();
        streamCursor = properties.getStreamCursor();
        heartbeatIntervalSeconds = properties.getHeartbeatIntervalSeconds();
        freshFinalTimeoutMs = properties.getFreshFinalTimeoutMs();
        streamingMaxChars = properties.getStreamingMaxChars();
        bufferThreshold = properties.getBufferThreshold();
        streamingTransport = properties.getStreamingTransport() != null
            ? properties.getStreamingTransport().toLowerCase() : "auto";
        // P1: Initialize rich message support
        this.richMessageSupport = new RichMessageSupport(telegramClient);
        this.richMessageSupport.setRichMessagesEnabled(properties.getRichMessages().isEnabled());
    }

    @PreDestroy
    void destroy() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * Sends the initial streaming message.
     *
     * @param chatId      target chat id
     * @param initialText first chunk of text to display
     * @return the message id wrapped in Optional, or empty if the send failed
     */
    public Optional<Long> startStream(long chatId, String initialText) {
        return startStream(chatId, initialText, "dm");
    }

    /**
     * Sends the initial streaming message with a chat type hint.
     *
     * <p>S5: When the streaming transport is "auto" or "draft" and the chat
     * type is a DM (private chat), resolves native draft streaming. When draft
     * streaming is active, no initial message is sent — the draft preview
     * animates via {@link TelegramClient#sendDraft} and the final answer is
     * delivered as a regular {@code sendMessage} on
     * {@link #finalizeStream}.
     *
     * @param chatId      target chat id
     * @param initialText first chunk of text to display
     * @param chatType    chat type hint ("dm", "group", "supergroup", "forum", etc.)
     * @return the message id wrapped in Optional, or empty if the send failed or draft streaming is active
     */
    public Optional<Long> startStream(long chatId, String initialText, String chatType) {
        // S5: "off" transport — no streaming, just record start time and return.
        // Content will be buffered by the caller and sent on finalizeStream.
        if ("off".equals(streamingTransport)) {
            long now = System.currentTimeMillis();
            streamStartTime.put(chatId, now);
            lastTokenTime.put(chatId, now);
            thinkScrubbers.put(chatId, new ThinkScrubber());
            log.debug("Streaming transport is 'off' for chat {}, no initial message", chatId);
            return Optional.empty();
        }

        // S5: Store chat type and resolve draft streaming
        chatTypeMap.put(chatId, chatType != null ? chatType.toLowerCase() : "dm");
        boolean useDraft = resolveDraftStreaming(chatId);
        useDraftStreaming.put(chatId, useDraft);
        draftFailures.remove(chatId);

        if (useDraft) {
            // Assign a fresh draft id for this run
            int draftId = draftIdCounter.incrementAndGet();
            draftIdMap.put(chatId, draftId);
            log.debug("Draft streaming enabled for chat {} (draftId={})", chatId, draftId);

            // Record start time for heartbeat and fresh-final
            long now = System.currentTimeMillis();
            streamStartTime.put(chatId, now);
            lastTokenTime.put(chatId, now);
            // Start heartbeat — it will use currentMessageId if available
            startHeartbeat(chatId);

            // If we have initial text, send the first draft frame
            if (initialText != null && !initialText.isBlank()) {
                String scrubbed = scrubThink(chatId, initialText);
                String formatted = formatForTelegram(scrubbed);
                if (!formatted.isEmpty() && formatted.length() >= 4) {
                    boolean draftOk = sendDraftFrame(chatId, formatted);
                    if (!draftOk) {
                        // Draft failed on first attempt — fall back to edit-based
                        log.info("First draft frame failed for chat {}, falling back to edit-based", chatId);
                        useDraftStreaming.put(chatId, false);
                        // Continue with regular startStream path below
                    } else {
                        // Draft streaming active — no message id (drafts have no message_id)
                        thinkScrubbers.put(chatId, new ThinkScrubber());
                        return Optional.empty();
                    }
                } else {
                    // Not enough text yet — draft streaming will start on first editStream
                    thinkScrubbers.put(chatId, new ThinkScrubber());
                    return Optional.empty();
                }
            } else {
                // No initial text — draft streaming will start on first editStream
                thinkScrubbers.put(chatId, new ThinkScrubber());
                return Optional.empty();
            }
        }

        // B5: Reset flood state
        floodStrikes.remove(chatId);
        streamingDisabled.remove(chatId);
        // P2-16: Reset flood fallback buffer and redundant edit skip state
        floodFallbackBuffer.remove(chatId);
        lastSentText.remove(chatId);
        // Reset per-chat interval
        editIntervalMap.remove(chatId);
        // Reset current message id
        currentMessageId.remove(chatId);
        // Reset buffer threshold tracking
        charsSinceLastEdit.remove(chatId);
        // Reset tool name
        currentToolName.remove(chatId);
        // M27: Don't remove thinkScrubber yet — move to after successful send
        // But we need a fresh scrubber for the new stream — replace explicitly
        thinkScrubbers.put(chatId, new ThinkScrubber());

        String scrubbed = scrubThink(chatId, initialText);
        String formatted = formatForTelegram(scrubbed);

        // Hermes: if initial text is empty or too short (<4 chars), don't send a message yet.
        // Wait for editStream to accumulate >=4 chars before sending the first message.
        if (formatted.isEmpty() || formatted.length() < 4) {
            // Record start time for heartbeat and fresh-final even without a message
            long now = System.currentTimeMillis();
            streamStartTime.put(chatId, now);
            lastTokenTime.put(chatId, now);
            // Start heartbeat — it will use currentMessageId if available
            startHeartbeat(chatId);
            log.debug("Started stream for chat {} with no initial message (waiting for >=4 chars)", chatId);
            return Optional.empty();
        }

        // B7: Initial message — silent if configured
        Optional<Long> messageId = sendMessageWithNotification(chatId, formatted, false);
        if (messageId.isPresent()) {
            lastEditTime.put(chatId, System.currentTimeMillis());
            // Record start time for heartbeat and fresh-final
            long now = System.currentTimeMillis();
            streamStartTime.put(chatId, now);
            lastTokenTime.put(chatId, now);
            currentMessageId.put(chatId, new AtomicLong(messageId.get()));
            // Start heartbeat
            startHeartbeat(chatId);
            // M27: thinkScrubber is already set up — keep it for the active stream
            log.debug("Started stream for chat {}, messageId={}", chatId, messageId.get());
        } else {
            // M27: Send failed — clean up the think scrubber we created
            thinkScrubbers.remove(chatId);
            log.warn("Failed to start stream for chat {}", chatId);
        }
        return messageId;
    }

    /**
     * Edits the streaming message with updated text. Throttled to
     * the per-chat edit interval — calls made too soon after the last edit
     * are silently skipped.
     *
     * <p>B5: On 429 flood error, increases the interval exponentially.
     * After 5 consecutive floods, streaming edits are disabled and content
     * is buffered for the final send.
     *
     * <p>Streaming cursor: appends the configured cursor character to the
     * end of the text to give visual feedback that streaming is active.
     *
     * <p>Split: if formatted text exceeds streamingMaxChars, edits the
     * current message with the first portion and sends remaining as new
     * messages (threaded as replies).
     *
     * @param chatId    target chat id
     * @param messageId the message id returned by {@link #startStream}
     * @param text      the full accumulated text to display
     * @return {@code true} if the edit was sent, {@code false} if throttled or failed
     */
    public boolean editStream(long chatId, long messageId, String text) {
        // Update last token time (token arrived)
        lastTokenTime.put(chatId, System.currentTimeMillis());

        // S5: "off" transport — buffer content, no streaming edits
        if ("off".equals(streamingTransport)) {
            String scrubbed = scrubThink(chatId, text);
            String formatted = formatForTelegram(scrubbed);
            floodFallbackBuffer.compute(chatId, (k, sb) -> {
                if (sb == null) sb = new StringBuilder();
                sb.setLength(0);
                sb.append(formatted);
                return sb;
            });
            return false;
        }

        // S5: Native draft streaming — route mid-stream frames through sendDraft.
        // The final answer is delivered via regular sendMessage on finalizeStream.
        // Skip when:
        //   * draft streaming is not active for this chat
        //   * streaming has been disabled due to flood limits (fall through to edit path)
        if (Boolean.TRUE.equals(useDraftStreaming.get(chatId))
            && !Boolean.TRUE.equals(streamingDisabled.get(chatId))) {
            // Check failure threshold — after 2 failures, fall back to edit-based
            int failures = draftFailures.getOrDefault(chatId, 0);
            if (failures >= 2) {
                log.info("Draft streaming disabled for chat {} after {} failures, falling back to edit-based", chatId, failures);
                useDraftStreaming.put(chatId, false);
                // Fall through to edit-based path below
            } else {
                return editStreamDraft(chatId, text);
            }
        }

        // B5: Check if streaming has been disabled due to flood limits
        if (Boolean.TRUE.equals(streamingDisabled.get(chatId))) {
            // P2-16: Buffer content in fallback mode — will be sent as a new message on finalize
            String scrubbedFallback = scrubThink(chatId, text);
            String formattedFallback = formatForTelegram(scrubbedFallback);
            floodFallbackBuffer.compute(chatId, (k, sb) -> {
                if (sb == null) sb = new StringBuilder();
                sb.setLength(0);
                sb.append(formattedFallback);
                return sb;
            });
            log.debug("Streaming edits disabled for chat {} due to flood limits, buffering ({} chars)", chatId, formattedFallback.length());
            return false;
        }

        // Hermes: if no message was sent yet (startStream returned empty because
        // initial text was <4 chars), check if we now have >=4 chars to send the first message.
        AtomicLong currentMsgId = currentMessageId.get(chatId);
        if (currentMsgId == null && text.length() >= 4) {
            String scrubbed = scrubThink(chatId, text);
            String formatted = formatForTelegram(scrubbed);
            if (!formatted.isEmpty() && formatted.length() >= 4) {
                Optional<Long> newMsgId = sendMessageWithNotification(chatId, formatted + streamCursor, false);
                if (newMsgId.isPresent()) {
                    currentMessageId.put(chatId, new AtomicLong(newMsgId.get()));
                    lastEditTime.put(chatId, System.currentTimeMillis());
                    floodStrikes.remove(chatId);
                    log.debug("Sent first streaming message for chat {} (delayed start), messageId={}", chatId, newMsgId.get());
                    return true;
                }
                return false;
            }
            return false;
        }
        // If still not enough chars, skip
        if (currentMsgId == null) {
            return false;
        }

        // Use the internal currentMessageId (may differ from messageId parameter after segment break)
        long effectiveMessageId = currentMsgId.get();

        long now = System.currentTimeMillis();
        Long last = lastEditTime.get(chatId);
        // B5: Use adaptive interval (may have been adjusted by flood handling)
        long currentInterval = getEffectiveInterval(chatId);
        
        // Buffer threshold: Hermes measures TOTAL accumulated text length (not delta since last edit).
        // The accumulated text is the full scrubbed text passed to editStream.
        int charsAccumulated = text.length();
        
        boolean intervalElapsed = last == null || (now - last) >= currentInterval;
        boolean thresholdReached = bufferThreshold > 0 && charsAccumulated >= bufferThreshold;
        
        if (!intervalElapsed && !thresholdReached) {
            log.trace("Throttled edit for chat {} ({}ms since last, interval={}, charsAccumulated={}, threshold={})",
                chatId, last != null ? now - last : 0, currentInterval, charsAccumulated, bufferThreshold);
            return false;
        }

        // B6: Strip think blocks
        String scrubbed = scrubThink(chatId, text);
        String formatted = formatForTelegram(scrubbed);

        // Append streaming cursor
        String withCursor = formatted + streamCursor;

        // P2-16: Redundant edit skip — if the new text (with cursor) is identical
        // to what was last sent to Telegram, skip the edit to avoid unnecessary API calls.
        String lastSent = lastSentText.get(chatId);
        if (lastSent != null && lastSent.equals(withCursor)) {
            log.trace("Skipping redundant edit for chat {} (content unchanged)", chatId);
            return true;
        }

        // Split if text exceeds max chars
        if (streamingMaxChars > 0 && withCursor.length() > streamingMaxChars) {
            return editStreamSplit(chatId, effectiveMessageId, withCursor);
        }

        // B7: Silent notification during streaming
        // Hermes: send raw text (no parse_mode) during streaming edits
        boolean disableNotification = streamingSilent;
        boolean success;
        try {
            success = telegramClient.editMessageText(chatId, effectiveMessageId, withCursor, null, disableNotification);
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                // 429 from editMessageText — apply adaptive flood handling.
                // callApi already set lastApiErrorCode=429 before throwing,
                // so handleEditFailure will see the correct error code.
                success = handleEditFailure(chatId, effectiveMessageId, formatted);
            } else {
                throw e;
            }
        }

        if (success) {
            lastEditTime.put(chatId, now);
            // P2-16: Track last sent text for redundant edit skip
            lastSentText.put(chatId, withCursor);
            // Hermes: on success, only reset flood strikes — interval stays at backoff level
            floodStrikes.remove(chatId);
        } else {
            // B5: Edit failed — check error code. handleEditFailure may
            // do a truncated retry for 400 errors; if the retry succeeds,
            // treat the overall editStream as successful.
            success = handleEditFailure(chatId, effectiveMessageId, formatted);
        }
        return success;
    }

    /**
     * Handle splitting during streaming: edit current message with first
     * portion, send remaining as new messages (threaded as replies).
     */
    private boolean editStreamSplit(long chatId, long messageId, String withCursor) {
        // First portion: first streamingMaxChars chars (minus cursor space)
        int firstLen = streamingMaxChars - streamCursor.length();
        if (firstLen <= 0) firstLen = streamingMaxChars;
        String firstPart = withCursor.substring(0, Math.min(firstLen, withCursor.length()));
        String remainder = withCursor.length() > firstLen ? withCursor.substring(firstLen) : "";

        boolean disableNotification = streamingSilent;
        boolean success;
        try {
            // Hermes: raw text (no parse_mode) during streaming
            success = telegramClient.editMessageText(chatId, messageId, firstPart, null, disableNotification);
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                success = handleEditFailure(chatId, messageId, firstPart);
                return false;
            } else {
                throw e;
            }
        }

        if (success) {
            lastEditTime.put(chatId, System.currentTimeMillis());
            floodStrikes.remove(chatId);
        } else {
            handleEditFailure(chatId, messageId, firstPart);
            return false;
        }

        // Send remainder as new messages, threaded as replies to previous
        long prevMsgId = messageId;
        if (!remainder.isEmpty()) {
            // Split remainder further if needed
            int pos = 0;
            while (pos < remainder.length()) {
                int end = Math.min(pos + streamingMaxChars, remainder.length());
                String chunk = remainder.substring(pos, end);
                Optional<Long> newMsgId = telegramClient.sendMessage(
                    chatId, chunk, null, prevMsgId, null);
                if (newMsgId.isPresent()) {
                    prevMsgId = newMsgId.get();
                    currentMessageId.put(chatId, new AtomicLong(prevMsgId));
                }
                pos = end;
            }
        }

        return true;
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
     * <p>Fresh-final: if (now - startTime) > freshFinalTimeoutMs, deletes the
     * old streaming message and sends a NEW one with the final content
     * (fresh timestamp).
     *
     * <p>Fallback: if editMessageText returns false, tries sendMessage.
     *
     * @param chatId    target chat id
     * @param messageId the message id returned by {@link #startStream}
     * @param finalText the complete final text
     * @return {@code true} if the edit succeeded
     */
    public boolean finalizeStream(long chatId, long messageId, String finalText) {
        // Stop heartbeat
        stopHeartbeat(chatId);

        // S5: Draft streaming — the draft is "committed" by sending the final
        // text as a regular sendMessage. Drafts have no message_id to edit or
        // delete; the draft preview clears naturally on the client when the
        // real message arrives. Try rich message delivery first (same as the
        // edit-based finalize path), then fall back to MarkdownV2 sendMessage.
        if (Boolean.TRUE.equals(useDraftStreaming.get(chatId))) {
            String scrubbed = scrubThinkFinal(chatId, finalText);

            // P1: Try rich message delivery first
            if (richMessageSupport != null && richMessageSupport.shouldAttemptRich(scrubbed)) {
                Optional<Long> richMsgId = richMessageSupport.sendRichMessage(chatId, scrubbed, null, null);
                if (richMsgId.isPresent()) {
                    log.debug("Draft finalized via rich message for chat {}", chatId);
                    cleanupStream(chatId);
                    return true;
                }
                log.debug("Rich message delivery failed for chat {}, falling back to MarkdownV2", chatId);
            }

            // Send the final text as a regular message (commits the draft)
            String formatted = formatForTelegram(scrubbed);
            Optional<Long> finalMsgId = sendFormattedMessage(chatId, formatted);
            cleanupStream(chatId);
            if (finalMsgId.isPresent()) {
                log.debug("Draft finalized for chat {}, messageId={}", chatId, finalMsgId.get());
                return true;
            } else {
                log.warn("Draft finalize sendMessage failed for chat {}", chatId);
                return false;
            }
        }

        // P2-16: If we were in flood fallback mode, send the buffered content
        // as a new message (or continuation messages) instead of editing.
        StringBuilder buffer = floodFallbackBuffer.get(chatId);
        if (buffer != null && buffer.length() > 0) {
            log.info("Flood fallback mode: sending buffered content ({} chars) as new message for chat {}", buffer.length(), chatId);
            String bufferedContent = buffer.toString();
            floodFallbackBuffer.remove(chatId);
            // Delete the old streaming message if it exists
            AtomicLong currentMsgId = currentMessageId.get(chatId);
            long oldMsgId = currentMsgId != null ? currentMsgId.get() : messageId;
            if (oldMsgId > 0) {
                telegramClient.deleteMessage(chatId, oldMsgId);
            }
            // Send the buffered content as a new message (with parse_mode for formatting)
            Optional<Long> newMsgId = sendFormattedMessage(chatId, bufferedContent);
            cleanupStream(chatId);
            if (newMsgId.isPresent()) {
                log.debug("Flood fallback sent for chat {}, new messageId={}", chatId, newMsgId.get());
                return true;
            } else {
                log.warn("Flood fallback sendMessage failed for chat {}", chatId);
                return false;
            }
        }

        // Use internal currentMessageId if available (may differ from messageId
        // parameter after a segment break created a new message).
        AtomicLong currentMsgId = currentMessageId.get(chatId);
        long effectiveMessageId = currentMsgId != null ? currentMsgId.get() : messageId;

        // B6: Final scrub — also flush any remaining think-block state
        String scrubbed = scrubThinkFinal(chatId, finalText);

        // Check fresh-final: if streaming exceeded timeout, delete old message
        // and send a new one
        Long startTime = streamStartTime.get(chatId);
        boolean freshFinal = startTime != null
            && (System.currentTimeMillis() - startTime) > freshFinalTimeoutMs;

        if (freshFinal) {
            log.debug("Fresh-final for chat {} (stream exceeded {}ms), deleting old msg {} and sending new",
                chatId, freshFinalTimeoutMs, effectiveMessageId);
            // P1: Try rich message delivery first
            if (richMessageSupport != null && richMessageSupport.shouldAttemptRich(scrubbed)) {
                Optional<Long> richMsgId = richMessageSupport.sendRichMessage(chatId, scrubbed, null, null);
                if (richMsgId.isPresent()) {
                    telegramClient.deleteMessage(chatId, effectiveMessageId);
                    cleanupStream(chatId);
                    return true;
                }
            }
            // Delete old message and send new one
            telegramClient.deleteMessage(chatId, effectiveMessageId);
            String formatted = formatForTelegram(scrubbed);
            Optional<Long> newMsgId = sendFormattedMessage(chatId, formatted);
            cleanupStream(chatId);
            if (newMsgId.isPresent()) {
                log.debug("Fresh-final sent for chat {}, new messageId={}", chatId, newMsgId.get());
                return true;
            } else {
                log.warn("Fresh-final sendMessage failed for chat {}", chatId);
                return false;
            }
        }

        // P1: Try rich message delivery first (uses raw markdown, not formatted)
        if (richMessageSupport != null && richMessageSupport.shouldAttemptRich(scrubbed)) {
            Optional<Long> richMsgId = richMessageSupport.sendRichMessage(chatId, scrubbed, null, null);
            if (richMsgId.isPresent()) {
                // Rich message sent successfully — delete the old streaming message
                log.debug("Finalized stream via rich message for chat {}, deleting old msg {}", chatId, effectiveMessageId);
                telegramClient.deleteMessage(chatId, effectiveMessageId);
                cleanupStream(chatId);
                return true;
            }
            // Rich failed — fall through to MarkdownV2 edit
            log.debug("Rich message delivery failed for chat {}, falling back to MarkdownV2", chatId);
        }

        // Hermes: on finalize, apply MarkdownConverter formatting (raw during streaming, formatted final)
        String formatted = formatForTelegram(scrubbed);
        // B7: Final message — NOT silent (push notification enabled)
        boolean success;
        try {
            success = telegramClient.editMessageText(chatId, effectiveMessageId, formatted, parseMode, false);
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                // 429 on final edit — fall through to sendMessage fallback
                log.warn("Final edit 429 rate limited for chat {}, trying sendMessage fallback", chatId);
                success = false;
            } else {
                throw e;
            }
        }

        // Fallback: if edit failed, try sendMessage as a new message
        if (!success) {
            log.warn("Final edit failed for chat {}, messageId={}, trying sendMessage fallback", chatId, effectiveMessageId);
            Optional<Long> fallbackMsgId = sendFormattedMessage(chatId, formatted);
            if (fallbackMsgId.isPresent()) {
                // Optionally delete the old stale message
                telegramClient.deleteMessage(chatId, effectiveMessageId);
                cleanupStream(chatId);
                log.debug("Finalize fallback succeeded for chat {}, new messageId={}", chatId, fallbackMsgId.get());
                return true;
            }
        }

        cleanupStream(chatId);
        if (success) {
            log.debug("Finalized stream for chat {}, messageId={}", chatId, effectiveMessageId);
        } else {
            log.warn("Failed to finalize stream for chat {}, messageId={}", chatId, effectiveMessageId);
        }
        return success;
    }

    /** Clean up streaming state for a chat. */
    private void cleanupStream(long chatId) {
        lastEditTime.remove(chatId);
        floodStrikes.remove(chatId);
        streamingDisabled.remove(chatId);
        thinkScrubbers.remove(chatId);
        editIntervalMap.remove(chatId);
        streamStartTime.remove(chatId);
        lastTokenTime.remove(chatId);
        currentMessageId.remove(chatId);
        charsSinceLastEdit.remove(chatId);
        currentToolName.remove(chatId);
        floodFallbackBuffer.remove(chatId);
        lastSentText.remove(chatId);
        // S5: Clean up draft streaming state
        useDraftStreaming.remove(chatId);
        draftIdMap.remove(chatId);
        draftFailures.remove(chatId);
        chatTypeMap.remove(chatId);
        stopHeartbeat(chatId);
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
        editIntervalMap.remove(chatId);
        streamStartTime.remove(chatId);
        lastTokenTime.remove(chatId);
        currentMessageId.remove(chatId);
        charsSinceLastEdit.remove(chatId);
        currentToolName.remove(chatId);
        floodFallbackBuffer.remove(chatId);
        lastSentText.remove(chatId);
        // S5: Clean up draft streaming state
        useDraftStreaming.remove(chatId);
        draftIdMap.remove(chatId);
        draftFailures.remove(chatId);
        chatTypeMap.remove(chatId);
        stopHeartbeat(chatId);
    }

    // ─── S5: Native draft streaming ───────────────────────────────

    /**
     * S5: Resolve whether native draft streaming should be used for this chat.
     *
     * <p>Honors {@code streamingTransport}:
     * <ul>
     *   <li>{@code "edit"} — never use drafts (legacy progressive-edit path)</li>
     *   <li>{@code "off"} — no streaming (treated as edit defensively)</li>
     *   <li>{@code "draft"} — require draft support; gracefully fall back to edit</li>
     *   <li>{@code "auto"} — use drafts when the adapter supports them for this chat type</li>
     * </ul>
     *
     * @param chatId the target chat id
     * @return {@code true} if draft streaming should be used
     */
    private boolean resolveDraftStreaming(long chatId) {
        if ("edit".equals(streamingTransport) || "off".equals(streamingTransport)) {
            return false;
        }
        String chatType = chatTypeMap.getOrDefault(chatId, "dm");
        boolean supported = telegramClient.supportsDraftStreaming(chatType);
        if (!supported) {
            if ("draft".equals(streamingTransport)) {
                log.debug("Draft streaming requested but unsupported (chat={}, type={}) — falling back to edit",
                    chatId, chatType);
            }
            return false;
        }
        return true;
    }

    /**
     * S5: Send a single draft frame for the current accumulated text.
     *
     * <p>Uses the per-chat draft id to animate the preview. On failure,
     * increments the failure counter and disables draft streaming after
     * 2 failures.
     *
     * @param chatId target chat id
     * @param text   the formatted text to display as a draft
     * @return {@code true} if the draft frame was successfully sent
     */
    private boolean sendDraftFrame(long chatId, String text) {
        Integer draftId = draftIdMap.get(chatId);
        if (draftId == null) {
            // Defensive: should never happen — draft id is set in startStream
            useDraftStreaming.put(chatId, false);
            return false;
        }
        boolean ok = telegramClient.sendDraft(chatId, text, draftId);
        if (!ok) {
            int failures = draftFailures.merge(chatId, 1, Integer::sum);
            log.debug("Draft frame failed for chat {} (failures={})", chatId, failures);
            if (failures >= 2) {
                log.info("Disabling draft streaming for chat {} after {} failures, falling back to edit-based",
                    chatId, failures);
                useDraftStreaming.put(chatId, false);
            }
        }
        return ok;
    }

    /**
     * S5: Draft-based editStream — sends the accumulated text as a draft frame
     * instead of editing a message. Throttled to the same per-chat edit interval.
     *
     * <p>No streaming cursor is appended (the draft animation itself provides
     * visual feedback). No message_id is needed (drafts have no message_id).
     *
     * @param chatId target chat id
     * @param text   the full accumulated text to display
     * @return {@code true} if the draft frame was sent, {@code false} if throttled or failed
     */
    private boolean editStreamDraft(long chatId, String text) {
        // Throttle draft frames the same way as edits
        long now = System.currentTimeMillis();
        Long last = lastEditTime.get(chatId);
        long currentInterval = getEffectiveInterval(chatId);
        int charsAccumulated = text.length();
        boolean intervalElapsed = last == null || (now - last) >= currentInterval;
        boolean thresholdReached = bufferThreshold > 0 && charsAccumulated >= bufferThreshold;
        if (!intervalElapsed && !thresholdReached) {
            log.trace("Throttled draft frame for chat {} ({}ms since last, interval={})",
                chatId, last != null ? now - last : 0, currentInterval);
            return false;
        }

        // Scrub think blocks and format
        String scrubbed = scrubThink(chatId, text);
        String formatted = formatForTelegram(scrubbed);

        // Skip if content is unchanged
        String lastSent = lastSentText.get(chatId);
        if (lastSent != null && lastSent.equals(formatted)) {
            log.trace("Skipping redundant draft frame for chat {} (content unchanged)", chatId);
            return true;
        }

        // Send the draft frame
        boolean ok = sendDraftFrame(chatId, formatted);
        if (ok) {
            lastEditTime.put(chatId, now);
            lastSentText.put(chatId, formatted);
            floodStrikes.remove(chatId);
        }
        return ok;
    }

    // ─── Tool name tracking & segment break ───────────────────────

    /**
     * Set the current tool name for a chat (used in heartbeat display).
     * Called when a tool starts executing — the name is shown in the heartbeat
     * message but NOT in the streaming text (tool_progress: off).
     */
    public void setCurrentToolName(long chatId, String toolName) {
        currentToolName.put(chatId, toolName);
    }

    /**
     * Handle a segment break — called after a tool execution completes.
     * Finalizes the current streaming message with the accumulated text so far,
     * then starts a new streaming message for the continuation.
     * This creates a visual separation between text segments and tool executions
     * without showing tool progress in the stream.
     */
    public void onSegmentBreak(long chatId, long messageId, String accumulatedText) {
        if (accumulatedText == null || accumulatedText.isBlank()) {
            // No text accumulated yet — just clear the tool name
            currentToolName.remove(chatId);
            return;
        }

        // S5: Draft streaming — on segment break, bump draftId so the next
        // text segment animates as a fresh preview. The accumulated text
        // from this segment is committed as a regular sendMessage (drafts
        // have no message_id to edit or finalize in-place).
        if (Boolean.TRUE.equals(useDraftStreaming.get(chatId))) {
            // Send the accumulated text as a real message (committing the draft)
            String scrubbed = scrubThink(chatId, accumulatedText);
            String formatted = formatForTelegram(scrubbed);
            sendFormattedMessage(chatId, formatted);
            // Bump draftId for the next segment
            int newDraftId = draftIdCounter.incrementAndGet();
            draftIdMap.put(chatId, newDraftId);
            // Reset buffer tracking for the new segment
            charsSinceLastEdit.remove(chatId);
            lastEditTime.remove(chatId);
            lastSentText.remove(chatId);
            currentToolName.remove(chatId);
            log.debug("Segment break for chat {} — draft committed, new draftId={}", chatId, newDraftId);
            return;
        }

        // Finalize the current message with what we have (no cursor, no silent)
        // Hermes: raw text (no parse_mode) during streaming
        String scrubbed = scrubThink(chatId, accumulatedText);
        String formatted = scrubbed; // Raw text during streaming — no formatForTelegram
        try {
            telegramClient.editMessageText(chatId, messageId, formatted, null, false);
        } catch (TelegramApiException e) {
            if (!e.isRateLimit()) {
                throw e;
            }
            log.debug("Segment break edit 429 rate limited for chat {}, skipping", chatId);
        }
        // Clear the tool name since the tool is done
        currentToolName.remove(chatId);
        // Reset buffer tracking for the new segment
        charsSinceLastEdit.remove(chatId);
        lastEditTime.put(chatId, System.currentTimeMillis());
        // Hermes: reset currentMessageId to null so the next editStream call
        // creates a NEW message (text after tool call appears below as a new message).
        // Track the old message ID for reply threading.
        currentMessageId.remove(chatId);
    }

    // ─── Heartbeat ───────────────────────────────────────────────

    private void startHeartbeat(long chatId) {
        stopHeartbeat(chatId); // Cancel any existing
        int interval = heartbeatIntervalSeconds > 0 ? heartbeatIntervalSeconds : 60;
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                checkHeartbeat(chatId);
            } catch (Exception e) {
                log.warn("Heartbeat error for chat {}: {}", chatId, e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
        heartbeatFutures.put(chatId, future);
    }

    private void stopHeartbeat(long chatId) {
        ScheduledFuture<?> future = heartbeatFutures.remove(chatId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void checkHeartbeat(long chatId) {
        Long startTime = streamStartTime.get(chatId);
        Long lastToken = lastTokenTime.get(chatId);
        AtomicLong msgIdRef = currentMessageId.get(chatId);
        // M5: For draft streaming, currentMessageId is null (no edit message to update).
        // Use useDraftStreaming to check if draft streaming is active — if so, skip
        // the heartbeat since there's no message to edit. Draft streaming uses
        // setMessageStreamingProgress instead of editMessageText.
        Boolean draftActive = useDraftStreaming.get(chatId);
        if (startTime == null || lastToken == null || msgIdRef == null) {
            // M5: If draft streaming is active but no messageId, still can't edit — skip
            if (draftActive == null || !draftActive) {
                return;
            }
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMs = heartbeatIntervalSeconds > 0 ? heartbeatIntervalSeconds * 1000L : 60000L;

        // Check if no tokens arrived recently (within the heartbeat interval)
        if ((now - lastToken) < intervalMs) {
            return; // Tokens are arriving, no need for heartbeat
        }

        long elapsedMinutes = (now - startTime) / 60000;
        if (elapsedMinutes < 1) {
            return; // Less than 1 minute, don't show heartbeat yet
        }

        long msgId = msgIdRef.get();
        String toolName = currentToolName.get(chatId);
        String heartbeatText = "⏳ Working — " + elapsedMinutes + " min";
        if (toolName != null && !toolName.isBlank()) {
            heartbeatText += " — " + toolName;
        }
        boolean disableNotification = streamingSilent;
        log.debug("Heartbeat for chat {}: {}", chatId, heartbeatText);
        try {
            // Hermes: raw text (no parse_mode) during streaming
            telegramClient.editMessageText(chatId, msgId, heartbeatText, null, disableNotification);
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                log.debug("Heartbeat edit 429 rate limited for chat {}, skipping", chatId);
            } else {
                throw e;
            }
        }
    }

    // ─── B5: Adaptive rate limiting ──────────────────────────────

    private long getEffectiveInterval(long chatId) {
        AtomicLong interval = editIntervalMap.get(chatId);
        if (interval == null) {
            return minIntervalMs;
        }
        return interval.get();
    }

    private void increaseInterval(long chatId) {
        AtomicLong interval = editIntervalMap.computeIfAbsent(chatId, k -> new AtomicLong(minIntervalMs));
        long current = interval.get();
        long newInterval = Math.min((long) (current * FLOOD_MULTIPLIER), MAX_INTERVAL_MS);
        if (newInterval != current) {
            log.info("Increasing edit interval from {}ms to {}ms due to flood (chat {})",
                current, newInterval, chatId);
            interval.set(newInterval);
        }
    }

    // Note: Hermes does not decrease the interval on success.
    // The interval stays at the backoff level and only flood strikes are reset to 0.

    /**
     * Handle edit failure. Distinguishes 400 (message too long) from 429 (flood).
     * - 400: don't increment flood strikes, just truncate and retry.
     *   Returns true if the truncated retry succeeds, false otherwise.
     * - 429: increment flood strikes, increase interval, possibly disable.
     *   Returns false.
     * - Other: treat as generic failure (increment flood strikes conservatively).
     *   Returns false.
     *
     * @return true if the failure was handled successfully (e.g. truncated retry
     *         succeeded), false otherwise
     */
    private boolean handleEditFailure(long chatId, long messageId, String formatted) {
        int errorCode = telegramClient.getLastApiErrorCode();

        if (errorCode == 400) {
            // Message too long — don't increment flood strikes, just truncate and retry
            log.debug("Edit failed with 400 (message too long) for chat {}, truncating and retrying", chatId);
            // Truncate to a safe length and retry
            int safeLen = streamingMaxChars > 0 ? streamingMaxChars : 4000;
            String truncated = formatted.length() > safeLen ? formatted.substring(0, safeLen) : formatted;
            boolean disableNotification = streamingSilent;
            boolean retried;
            try {
                // L12: Use null parseMode in retry — the original editMessageText call for
                // streaming content uses null (raw text), so the truncated retry should match.
                retried = telegramClient.editMessageText(chatId, messageId, truncated, null, disableNotification);
            } catch (TelegramApiException retryEx) {
                if (retryEx.isRateLimit()) {
                    // Truncated retry also got 429 — treat as flood
                    log.debug("Truncated retry got 429 for chat {}", chatId);
                    return false;
                }
                throw retryEx;
            }
            if (retried) {
                lastEditTime.put(chatId, System.currentTimeMillis());
                floodStrikes.remove(chatId);
            }
            return retried;
        }

        // 429 or other failure — increment flood strikes
        int strikes = floodStrikes.merge(chatId, 1, Integer::sum);
        log.debug("Edit failure (errorCode={}) for chat {}, flood strikes: {}", errorCode, chatId, strikes);

        // Increase interval on flood
        increaseInterval(chatId);

        if (strikes >= FLOOD_WARN_THRESHOLD) {
            log.warn("Flood strike threshold ({}) reached for chat {}, current interval: {}ms",
                strikes, chatId, getEffectiveInterval(chatId));
        }

        if (strikes >= MAX_FLOOD_STRIKES) {
            log.warn("Max flood strikes ({}) exceeded for chat {}, disabling streaming edits — buffering until final",
                MAX_FLOOD_STRIKES, chatId);
            streamingDisabled.put(chatId, true);
            // P2-16: Initialize the flood fallback buffer with the current formatted content
            floodFallbackBuffer.computeIfAbsent(chatId, k -> new StringBuilder()).append(formatted);
        }
        return false;
    }

    // ─── B6: Think-block filtering ───────────────────────────────

    /**
     * Stateful scrubber for {@code <think>} blocks.
     * Handles split chunks where the opening or closing tag spans
     * multiple stream deltas.
     *
     * <p>Also supports {@code <reasoning_scratchpad>} tags.
     *
     * <p>Partial closing tags are stored in a field and prepended to the
     * next chunk to handle split closing tags across stream deltas.
     */
    static class ThinkScrubber {
        private boolean insideThinkBlock = false;
        private StringBuilder pendingTag = new StringBuilder();
        private String pendingClosingTag = null; // Partial closing tag stored across chunks
        private String accumulatedBefore = ""; // Text accumulated outside think blocks (for boundary check)

        // Exact tag lists — case-sensitive, matching Hermes behavior.
        // Using \u003C and \u003E for angle brackets to avoid encoding issues.
        // Hermes uses: <REASONING_SCRATCHPAD>, <think>, <reasoning>, <THINKING>, <thinking>, <thought>
        // Also includes <antml:thinking> which some models emit.
        // Lowercase <reasoning_scratchpad> included for compatibility with models that emit it.
        private static final String[] OPENING_TAGS = {
            "\u003Cthink\u003E", "\u003Cthinking\u003E", "\u003Creasoning\u003E", "\u003Cthought\u003E",
            "\u003CTHINKING\u003E", "\u003CREASONING_SCRATCHPAD\u003E", "\u003Creasoning_scratchpad\u003E", "\u003Cantml:thinking\u003E"
        };
        private static final String[] CLOSING_TAGS = {
            "\u003C/think\u003E", "\u003C/thinking\u003E", "\u003C/reasoning\u003E", "\u003C/thought\u003E",
            "\u003C/THINKING\u003E", "\u003C/REASONING_SCRATCHPAD\u003E", "\u003C/reasoning_scratchpad\u003E", "\u003C/antml:thinking\u003E"
        };
        // Prefixes for partial tag detection (case-sensitive)
        private static final String[] CLOSING_TAG_PREFIXES = {
            "\u003C/think", "\u003C/thinking", "\u003C/reasoning", "\u003C/thought",
            "\u003C/THINKING", "\u003C/REASONING_SCRATCHPAD", "\u003C/reasoning_scratchpad", "\u003C/antml:thinking"
        };
        private static final String[] OPENING_TAG_PREFIXES = {
            "\u003Cthink", "\u003Cthinking", "\u003Creasoning", "\u003Cthought",
            "\u003CTHINKING", "\u003CREASONING_SCRATCHPAD", "\u003Creasoning_scratchpad", "\u003Cantml:thinking"
        };

        /**
         * Process a text chunk, removing any think-block content.
         * Stateful: if a {@code <think>} tag opens but no closing tag is seen,
         * all subsequent content is suppressed until the closing tag arrives.
         *
         * <p>Matches Hermes behavior:
         * <ul>
         *   <li>Case-sensitive matching (not case-insensitive)</li>
         *   <li>Exact tag match (not prefix matching)</li>
         *   <li>Boundary check: opening tag must be at block boundary
         *       (start of text or preceded by newline + optional whitespace)</li>
         * </ul>
         *
         * @param input the raw text chunk
         * @return the text with think-block content removed
         */
        String scrub(String input) {
            if (input == null || input.isEmpty()) {
                return "";
            }

            // If we have a pending partial closing tag from the previous chunk,
            // prepend it to this chunk
            if (pendingClosingTag != null) {
                input = pendingClosingTag + input;
                pendingClosingTag = null;
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

            // Not inside a think block — look for opening tags at block boundaries
            StringBuilder result = new StringBuilder();
            int i = 0;
            while (i < input.length()) {
                int openIdx = findOpeningTagAtBoundary(input, i);
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

                // Find the end of the opening tag (the '>')
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

            String output = result.toString();
            accumulatedBefore += output;
            return output;
        }

        /**
         * Flush any remaining state — called on finalize.
         * If we were inside a think block, return empty (the think content
         * was suppressed and never displayed).
         */
        String flush() {
            insideThinkBlock = false;
            pendingTag.setLength(0);
            pendingClosingTag = null;
            accumulatedBefore = "";
            return "";
        }

        /**
         * Find the earliest opening tag at a block boundary.
         * Boundary check (mirrors Hermes stream_consumer.py logic):
         * - Tag at index 0: boundary if accumulated text is empty or ends with newline
         * - Tag at index > 0: boundary if text between last newline and tag is whitespace-only
         *   (or if no newline before tag, all text before tag is whitespace AND
         *   accumulated text is empty or ends with newline)
         */
        private int findOpeningTagAtBoundary(String text, int from) {
            int earliest = -1;
            for (String tag : OPENING_TAGS) {
                int searchStart = from;
                while (true) {
                    int idx = text.indexOf(tag, searchStart);
                    if (idx < 0) break;

                    // Boundary check
                    boolean isBoundary;
                    if (idx == from) {
                        // At the start of the current chunk
                        isBoundary = accumulatedBefore.isEmpty()
                            || accumulatedBefore.endsWith("\n");
                    } else {
                        // Check text before the tag within this chunk
                        String preceding = text.substring(from, idx);
                        int lastNl = preceding.lastIndexOf('\n');
                        if (lastNl == -1) {
                            // No newline before tag in this chunk
                            isBoundary = (accumulatedBefore.isEmpty()
                                || accumulatedBefore.endsWith("\n"))
                                && preceding.strip().isEmpty();
                        } else {
                            isBoundary = preceding.substring(lastNl + 1).strip().isEmpty();
                        }
                    }

                    if (isBoundary) {
                        if (earliest < 0 || idx < earliest) {
                            earliest = idx;
                        }
                        break; // first boundary hit for this tag is enough
                    }
                    searchStart = idx + 1;
                }
            }
            return earliest;
        }

        private int findClosingTag(String text) {
            return findClosingTag(text, 0);
        }

        private int findClosingTag(String text, int from) {
            int earliest = -1;
            for (String tag : CLOSING_TAGS) {
                int idx = text.indexOf(tag, from);
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

        /**
         * Check if the end of text contains a partial closing tag like "</thin".
         * If found, store it in pendingClosingTag so it can be prepended to the
         * next chunk.
         */
        private void checkPartialClosingTag(String text) {
            for (String tag : CLOSING_TAG_PREFIXES) {
                for (int len = Math.min(tag.length() - 1, text.length()); len >= 2; len--) {
                    if (text.endsWith(tag.substring(0, len))) {
                        // Partial closing tag at end — store it for the next chunk
                        pendingClosingTag = tag.substring(0, len);
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
            for (String tag : OPENING_TAG_PREFIXES) {
                for (int len = Math.min(tag.length() - 1, text.length()); len >= 1; len--) {
                    String suffix = tag.substring(0, len);
                    if (text.endsWith(suffix)) {
                        return new String[]{text.substring(0, text.length() - len), suffix};
                    }
                }
            }
            return new String[]{text, null};
        }
    }

    /**
     * B6: Scrub think blocks from a streaming chunk.
     * Uses a stateful ThinkScrubber per chat to handle split chunks.
     * S-2: Also strips MEDIA: tags and directives so the user doesn't see
     * raw MEDIA: tags during streaming.
     */
    String scrubThink(long chatId, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        ThinkScrubber scrubber = thinkScrubbers.computeIfAbsent(chatId, k -> new ThinkScrubber());
        String result = scrubber.scrub(text);
        // S-2: Strip MEDIA: tags from streaming display
        result = mediaDeliveryService.stripMediaTagsForDisplay(result);
        return result;
    }

    /**
     * B6: Final scrub — flushes any remaining think-block state.
     * Matches Hermes: relies solely on the stateful scrubber, no regex safety net.
     * S-2: Also strips MEDIA: tags as a safety net (the onComplete callback
     * in BotMessageProcessor already extracts them before calling finalizeStream,
     * but this catches the onError/interrupt paths too).
     */
    String scrubThinkFinal(long chatId, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        ThinkScrubber scrubber = thinkScrubbers.get(chatId);
        if (scrubber == null) {
            // No scrubber exists (e.g. finalizeStream called without prior startStream).
            // Create a temporary one to scrub the final text.
            scrubber = new ThinkScrubber();
        }
        // Process the final text through the scrubber and flush
        String result = scrubber.scrub(text);
        scrubber.flush();
        // S-2: Strip MEDIA: tags as a safety net
        result = mediaDeliveryService.stripMediaTagsForDisplay(result);
        return result;
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
        // Hermes: during streaming, send raw text (no parse_mode).
        return telegramClient.sendMessage(chatId, text, null, null, null);
    }

    /**
     * Send a formatted final message with parse_mode enabled.
     * Used by finalizeStream fallback paths where MarkdownV2 formatting is needed.
     */
    private Optional<Long> sendFormattedMessage(long chatId, String text) {
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

    // ─── S5: Draft streaming accessors ────────────────────────────

    /**
     * S5: Check if draft streaming is active for a chat.
     *
     * @param chatId target chat id
     * @return {@code true} if draft streaming is currently active
     */
    public boolean isDraftStreamingActive(long chatId) {
        return Boolean.TRUE.equals(useDraftStreaming.get(chatId));
    }

    /**
     * S5: Check if streaming is disabled (transport "off").
     *
     * @return {@code true} if streaming transport is "off"
     */
    public boolean isStreamingOff() {
        return "off".equals(streamingTransport);
    }
}