package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.formatting.MessageSplitter;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.formatting.MarkdownConverter;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.rich.RichMessageSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Manages edit-message streaming: sends an initial message, then edits it
 * as more content arrives. Throttles edits to {@code streamEditInterval}
 * to avoid hitting Telegram's rate limits.
 *
 * <p>c6: Per-chat streaming state has been extracted into {@link StreamSession},
 * replacing the 17 parallel {@code ConcurrentHashMap}s that previously lived
 * in this class. A single {@code ConcurrentHashMap<Long, StreamSession>} now
 * holds all per-chat state. Public methods retain their {@code chatId}-keyed
 * signatures for backward compatibility and internally look up the session;
 * {@code StreamSession}-keyed overloads are provided for callers that already
 * hold the session.
 *
 * <p>B5: Adaptive rate limiting — the edit interval increases exponentially
 * on 429 flood errors (×2, cap 10000ms). After 3 consecutive floods, streaming
 * edits are stopped and all content is buffered for the final send.
 *
 * <p>B6: Think-block filtering — {@code Ӥ tags (and variants
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
 * <p>An unconfirmed final edit leaves the existing preview in place.
 *
 * <p>ThinkScrubber: stores partial closing tags across chunks and prepends
 * to the next chunk. Supports {@code <reasoning_scratchpad>} tags.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StreamEditor {

    public static final long FLOOD_DELIVERY_WINDOW_SECONDS = 30;

    private final TelegramClient telegramClient;
    private final BotProperties properties;
    private final MediaDeliveryService mediaDeliveryService;
    private String parseMode;
    private long minIntervalMs; // Configured minimum interval (from streamEditInterval)

    // c6: All per-chat streaming state consolidated into a single map.
    private final ConcurrentHashMap<Long, StreamSession> sessions = new ConcurrentHashMap<>();

    // B5: Adaptive rate limiting constants extracted to EditThrottlePolicy

    // B7: Silent notification config
    private boolean streamingSilent;

    // P1 (m25): rich message support injected as a Spring bean
    private final RichMessageSupport richMessageSupport;

    // Streaming cursor config
    private String streamCursor;

    // Heartbeat
    private int heartbeatIntervalSeconds;
    private final java.util.concurrent.ExecutorService deliveryWorkers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deliveryExecutor =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("telegram-delivery").factory());
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stream-heartbeat");
        t.setDaemon(true);
        return t;
    });

    // Fresh-final
    private long freshFinalTimeoutMs;

    // Buffer threshold: trigger an edit when accumulated text reaches this many chars
    // since last edit, even if the interval hasn't elapsed.
    private int bufferThreshold;

    private int streamingMaxChars;

    // S5: Native draft streaming transport
    // transport: "auto" (prefer draft, fallback to edit), "draft" (explicit),
    // "edit" (legacy default), "off" (no streaming)
    private String streamingTransport;
    // Class-wide monotonic counter for draft ids (mirrors Hermes _draft_id_counter)
    // Hermes parity (stream_consumer.py:195): seed draft IDs with a random
    // 49-bit nonce to avoid collisions after restart. Java was starting at 0,
    // which repeats IDs after restart and conflicts with transport-side tombstones.
    private static final AtomicInteger draftIdCounter = new AtomicInteger(
        new java.security.SecureRandom().nextInt(1_000_000, 9_999_999));

    @PostConstruct
    void init() {
        parseMode = properties.getParseMode();
        minIntervalMs = properties.getStreamEditInterval().toMillis();
        streamingSilent = properties.isStreamingSilent();
        streamCursor = properties.getStreamCursor();
        heartbeatIntervalSeconds = properties.getHeartbeatIntervalSeconds();
        freshFinalTimeoutMs = properties.getFreshFinalTimeoutMs();
        // BUG FIX (audit H14): clamp the streaming split threshold to the Telegram
        // editMessageText limit. streaming-max-chars is documented as a rich-message
        // style limit (32768) but editStreamSplit uses it to chunk edited text —
        // anything above 4096 makes Telegram reject every edit with 400 and the
        // stream freezes until finalize.
        int configuredMax = properties.getStreamingMaxChars();
        streamingMaxChars = configuredMax > 0
            ? Math.min(configuredMax, MessageSplitter.TELEGRAM_MAX_LENGTH)
            : configuredMax;
        bufferThreshold = properties.getBufferThreshold();
        streamingTransport = properties.getStreamingTransport() != null
            ? properties.getStreamingTransport().toLowerCase() : "auto";
        // P1 (m25): bean-injected; apply the rich-message config flag
        richMessageSupport.setRichMessagesEnabled(properties.getRichMessages().isEnabled());
    }

    @PreDestroy
    void destroy() {
        deliveryExecutor.shutdownNow();
        deliveryWorkers.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }

    // ─── c6: session lookup helpers ───────────────────────────────

    /**
     * Get the {@link StreamSession} for a chat, creating a fresh one if absent.
     * @param chatId target chat id
     * @return the session (never null)
     */
    StreamSession sessionFor(long chatId) {
        return sessions.computeIfAbsent(chatId, k -> new StreamSession());
    }

    /**
     * Remove the {@link StreamSession} for a chat (full cleanup).
     * @param chatId target chat id
     */
    void removeSession(long chatId) {
        StreamSession s = sessions.remove(chatId);
        if (s != null) {
            stopHeartbeat(s);
            synchronized (s) {
                s.deliveryCancelled = true;
                if (s.deliveryRetry != null) s.deliveryRetry.cancel(false);
                if (!s.deliveryDraining) failDelivery(s);
            }
        }
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
     * P2.S6: startStream with forum-topic routing. The thread id is recorded on the
     * StreamSession so every subsequent send in this stream (progress bubbles, splits,
     * fresh finals) lands in the same topic.
     */
    public Optional<Long> startStream(long chatId, String initialText, String chatType, long threadId) {
        return startStream(chatId, initialText, chatType, threadId, 0L);
    }

    /** Start a stream routed to a forum topic and replying to a user message. */
    public Optional<Long> startStream(long chatId, String initialText, String chatType,
                                      long threadId, long replyToMessageId) {
        StreamSession session = sessionFor(chatId);
        session.messageThreadId = threadId;
        session.replyToMessageId = replyToMessageId;
        return startStream(chatId, initialText, chatType, session);
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
        StreamSession session = sessionFor(chatId);
        return startStream(chatId, initialText, chatType, session);
    }

    Optional<Long> startStream(long chatId, String initialText, String chatType, StreamSession session) {
        session.partialFinalDelivery = false;
        // S5: "off" transport — no streaming, just record start time and return.
        // Content will be buffered by the caller and sent on finalizeStream.
        if ("off".equals(streamingTransport)) {
            long now = System.currentTimeMillis();
            session.streamStartTime = now;
            session.lastTokenTime = now;
            session.thinkScrubber = new ThinkTagFilter.ThinkScrubber();
            log.debug("Streaming transport is 'off' for chat {}, no initial message", chatId);
            return Optional.empty();
        }

        // S5: Store chat type and resolve draft streaming
        session.chatType = chatType != null ? chatType.toLowerCase() : "dm";
        boolean useDraft = resolveDraftStreaming(session);
        session.useDraftStreaming = useDraft;
        session.draftFailures.set(0);

        if (useDraft) {
            // Assign a fresh draft id for this run
            int draftId = draftIdCounter.incrementAndGet();
            session.draftId = draftId;
            log.debug("Draft streaming enabled for chat {} (draftId={})", chatId, draftId);

            // Record start time for heartbeat and fresh-final
            long now = System.currentTimeMillis();
            session.streamStartTime = now;
            session.lastTokenTime = now;
            // Start heartbeat — it will use currentMessageId if available
            startHeartbeat(chatId, session);

            // If we have initial text, send the first draft frame
            if (initialText != null && !initialText.isBlank()) {
                String scrubbed = scrubThink(session, initialText);
                String formatted = formatForTelegramDelegate(scrubbed);
                if (!formatted.isEmpty() && formatted.length() >= 4) {
                    boolean draftOk = sendDraftFrame(chatId, formatted, session);
                    if (!draftOk) {
                        // Draft failed on first attempt — fall back to edit-based
                        log.info("First draft frame failed for chat {}, falling back to edit-based", chatId);
                        session.useDraftStreaming = false;
                        // Continue with regular startStream path below
                    } else {
                        // Draft streaming active — no message id (drafts have no message_id)
                        session.thinkScrubber = new ThinkTagFilter.ThinkScrubber();
                        return Optional.empty();
                    }
                } else {
                    // Not enough text yet — draft streaming will start on first editStream
                    session.thinkScrubber = new ThinkTagFilter.ThinkScrubber();
                    return Optional.empty();
                }
            } else {
                // No initial text — draft streaming will start on first editStream
                session.thinkScrubber = new ThinkTagFilter.ThinkScrubber();
                return Optional.empty();
            }
        }

        // B5: Reset flood state for the new stream
        session.resetForNewStream();
        // M27: fresh scrubber for the new stream
        session.thinkScrubber = new ThinkTagFilter.ThinkScrubber();

        String scrubbed = scrubThink(session, initialText);
        // Edit-based streaming sends with parseMode=null (plain text) — do NOT apply
        // formatForTelegram/MarkdownV2 escaping here, or backslashes will be visible.
        String formatted = scrubbed;

        // Hermes: if initial text is empty or too short (<4 chars), don't send a message yet.
        // Wait for editStream to accumulate >=4 chars before sending the first message.
        if (formatted.isEmpty() || formatted.length() < 4) {
            // Record start time for heartbeat and fresh-final even without a message
            long now = System.currentTimeMillis();
            session.streamStartTime = now;
            session.lastTokenTime = now;
            // Start heartbeat — it will use currentMessageId if available
            startHeartbeat(chatId, session);
            log.debug("Started stream for chat {} with no initial message (waiting for >=4 chars)", chatId);
            return Optional.empty();
        }

        // B7: Initial message — silent if configured
        Optional<Long> messageId = sendMessageWithNotification(chatId, formatted, false);
        if (messageId.isPresent()) {
            session.lastEditTime = System.currentTimeMillis();
            session.lastRenderedChars = formatted.length();
            // Record start time for heartbeat and fresh-final
            long now = System.currentTimeMillis();
            session.streamStartTime = now;
            session.lastTokenTime = now;
            session.currentMessageId.set(messageId.get());
            // Start heartbeat
            startHeartbeat(chatId, session);
            // M27: thinkScrubber is already set up — keep it for the active stream
            log.debug("Started stream for chat {}, messageId={}", chatId, messageId.get());
        } else {
            // M27: Send failed — clean up the think scrubber we created
            session.thinkScrubber = null;
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
     * After 3 consecutive floods, streaming edits are disabled and content
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
        StreamSession session = sessionFor(chatId);
        return editStream(chatId, messageId, text, session);
    }

    boolean editStream(long chatId, long messageId, String text, StreamSession session) {
        if (session.deferredDelivery != null || session.partialFinalDelivery) return false;
        // Update last token time (token arrived)
        session.lastTokenTime = System.currentTimeMillis();

        // S5: "off" transport — buffer content, no streaming edits
        if ("off".equals(streamingTransport)) {
            String scrubbed = scrubThink(session, text);
            String formatted = formatForTelegramDelegate(scrubbed);
            session.floodFallbackBuffer.setLength(0);
            session.floodFallbackBuffer.append(formatted);
            return false;
        }

        // S5: Native draft streaming — route mid-stream frames through sendDraft.
        if (session.useDraftStreaming && !session.streamingDisabled) {
            // Check failure threshold — after 2 failures, fall back to edit-based
            if (session.draftFailures.get() >= 1) {
                log.info("Draft streaming disabled for chat {} after {} failures, falling back to edit-based", chatId, session.draftFailures.get());
                session.useDraftStreaming = false;
                // Fall through to edit-based path below
            } else {
                return editStreamDraft(chatId, text, session);
            }
        }

        // B5: Check if streaming has been disabled due to flood limits
        if (session.streamingDisabled) {
            // P2-16: Buffer content in fallback mode — will be sent as a new message on finalize
            String scrubbedFallback = scrubThink(session, text);
            String formattedFallback = formatForTelegramDelegate(scrubbedFallback);
            session.floodFallbackBuffer.setLength(0);
            session.floodFallbackBuffer.append(formattedFallback);
            log.debug("Streaming edits disabled for chat {} due to flood limits, buffering ({} chars)", chatId, formattedFallback.length());
            return false;
        }

        // Hermes: if no message was sent yet (startStream returned empty because
        // initial text was <4 chars), check if we now have >=4 chars to send the first message.
        long currentMsg = session.currentMessageId.get();
        boolean noMsgYet = currentMsg < 0;
        if (noMsgYet && text.length() >= 4) {
            String scrubbed = scrubThink(session, text);
            // Edit-based streaming sends with parseMode=null (plain text) — no MarkdownV2 escaping.
            String formatted = scrubbed;
            if (!formatted.isEmpty() && formatted.length() >= 4) {
                Optional<Long> newMsgId = sendMessageWithNotification(chatId, formatted + streamCursor, false);
                if (newMsgId.isPresent()) {
                    session.currentMessageId.set(newMsgId.get());
                    session.lastEditTime = System.currentTimeMillis();
                    session.floodStrikes.set(0);
                    log.debug("Sent first streaming message for chat {} (delayed start), messageId={}", chatId, newMsgId.get());
                    return true;
                }
                return false;
            }
            return false;
        }
        // If still not enough chars, skip
        if (noMsgYet) {
            return false;
        }

        // Use the internal currentMessageId (may differ from messageId parameter after segment break)
        long effectiveMessageId = session.currentMessageId.get();

        long now = System.currentTimeMillis();
        long last = session.lastEditTime;
        // B5: Use adaptive interval (may have been adjusted by flood handling)
        long currentInterval = EditThrottlePolicy.getEffectiveInterval(session, minIntervalMs);

        // Buffer threshold limits the delta between Telegram edits; applying it
        // to the total accumulated answer bypasses time throttling after the
        // first threshold and quickly triggers Telegram flood limits.
        int charsAccumulated = text.length();
        int charsSinceLastEdit = Math.max(0, charsAccumulated - session.lastRenderedChars);

        boolean intervalElapsed = last == 0 || (now - last) >= currentInterval;
        boolean thresholdReached = bufferThreshold > 0 && charsSinceLastEdit >= bufferThreshold;

        if (!intervalElapsed && !thresholdReached) {
            log.trace("Throttled edit for chat {} ({}ms since last, interval={}, charsAccumulated={}, threshold={})",
                chatId, last != 0 ? now - last : 0, currentInterval, charsAccumulated, bufferThreshold);
            return false;
        }

        // B6: Strip think blocks
        // Edit-based streaming sends with parseMode=null (plain text) — no MarkdownV2 escaping.
        // Applying formatForTelegram here would add backslashes before _ . - | ( ) etc.
        // which Telegram shows literally when parse_mode is not set.
        String scrubbed = scrubThink(session, text);
        // Hermes parity (stream_consumer.py:2353): balance code fences on EVERY
        // streaming edit, not just finalize. Unclosed ``` mid-stream shows as
        // broken formatting in Telegram. Lightweight — regex, no allocation.
        String formatted = ensureClosedCodeFences(scrubbed);

        // Partial silence marker holdback: don't render a chunk that ends with an incomplete
        // marker like "NO" / "NO_R" / "[SILE" / "**". Wait for the next chunk.
        if (SilenceMarkerUtils.endsWithPartialSilenceMarker(formatted)) {
            log.debug("Holding back partial silence marker for chat {}: '{}'", chatId, formatted);
            return false;
        }

        // Append streaming cursor
        String withCursor = formatted + streamCursor;

        // P2-16: Redundant edit skip — if the new text (with cursor) is identical
        // to what was last sent to Telegram, skip the edit to avoid unnecessary API calls.
        String lastSent = session.lastSentText;
        if (lastSent != null && lastSent.equals(withCursor)) {
            log.trace("Skipping redundant edit for chat {} (content unchanged)", chatId);
            return true;
        }

        // Split if text exceeds max chars
        if (streamingMaxChars > 0 && withCursor.length() > streamingMaxChars) {
            return editStreamSplit(chatId, effectiveMessageId, withCursor, session);
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
                success = EditThrottlePolicy.handleEditFailure(telegramClient, chatId, effectiveMessageId, formatted, session, 429, minIntervalMs, streamingSilent, streamingMaxChars, this::formatForTelegramDelegate);
            } else {
                throw e;
            }
        }

        if (success) {
            session.lastEditTime = now;
            session.lastRenderedChars = charsAccumulated;
            // P2-16: Track last sent text for redundant edit skip
            session.lastSentText = withCursor;
            // Hermes: on success, only reset flood strikes — interval stays at backoff level
            session.floodStrikes.set(0);
        } else {
            // B5: Edit failed. BUG FIX: use the error code from the typed exception —
            // TelegramClient.getLastApiErrorCode() is a shared mutable side-channel
            // and races between concurrently streaming chats (wrong flood strikes).
            success = EditThrottlePolicy.handleEditFailure(telegramClient, chatId, effectiveMessageId, formatted, session,
                telegramClient.getLastApiErrorCode(), minIntervalMs, streamingSilent, streamingMaxChars, this::formatForTelegramDelegate);
        }
        return success;
    }

    /**
     * Handle splitting during streaming: edit current message with first
     * portion, send remaining as new messages (threaded as replies).
     */
    private boolean editStreamSplit(long chatId, long messageId, String withCursor, StreamSession session) {
        // First portion: first streamingMaxChars chars (minus cursor space)
        int firstLen = streamingMaxChars - streamCursor.length();
        if (firstLen <= 0) firstLen = streamingMaxChars;
        firstLen = Math.min(firstLen, withCursor.length());

        // Hermes parity (stream_consumer.py:1146 _custom_unit_to_cp): avoid
        // splitting a Java UTF-16 surrogate pair. If the char at firstLen-1 is
        // a high surrogate, step back one to include the full pair in the first
        // chunk (the remainder starts at a code point boundary).
        if (firstLen > 0 && firstLen < withCursor.length()
            && Character.isHighSurrogate(withCursor.charAt(firstLen - 1))) {
            firstLen--;
        }

        // Code fence balancing: if the split point falls inside an open ``` block,
        // close the fence at the end of the first chunk and reopen it at the start
        // of the remainder (Hermes balance_fences_across_chunks).
        String firstPart = withCursor.substring(0, firstLen);
        String remainder = withCursor.length() > firstLen ? withCursor.substring(firstLen) : "";

        // Count ``` (triple backtick) occurrences in firstPart
        // If odd, we're inside an unclosed code block at the split point
        int fenceCount = TelegramTextFormatter.countCodeFences(firstPart);
        if (fenceCount % 2 != 0) {
            // Inside a code block — close in firstPart, reopen in remainder
            firstPart = firstPart + "\n```";
            remainder = "```\n" + remainder;
        }

        boolean disableNotification = streamingSilent;
        boolean success;
        try {
            // Hermes: raw text (no parse_mode) during streaming
            success = telegramClient.editMessageText(chatId, messageId, firstPart, null, disableNotification);
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                EditThrottlePolicy.handleEditFailure(telegramClient, chatId, messageId, firstPart, session, 429, minIntervalMs, streamingSilent, streamingMaxChars, this::formatForTelegramDelegate);
                return false;
            } else {
                throw e;
            }
        }

        if (success) {
            session.lastEditTime = System.currentTimeMillis();
            session.floodStrikes.set(0);
        } else {
            EditThrottlePolicy.handleEditFailure(telegramClient, chatId, messageId, firstPart, session, 400, minIntervalMs, streamingSilent, streamingMaxChars, this::formatForTelegramDelegate);
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
                    session.currentMessageId.set(prevMsgId);
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
     * <p>An unconfirmed edit preserves the existing preview without another send.
     *
     * @param chatId    target chat id
     * @param messageId the message id returned by {@link #startStream}
     * @param finalText the complete final text
     * @return {@code true} if the edit succeeded
     */
    public boolean finalizeTurn(long chatId, String text, long userMessageId, long threadId) {
        var session = sessionFor(chatId);
        session.replyToMessageId = userMessageId;
        session.messageThreadId = threadId;
        return finalizeStream(chatId, -1L, text, session);
    }

    public boolean finalizeStream(long chatId, long messageId, String finalText) {
        StreamSession session = sessionFor(chatId);
        return finalizeStream(chatId, messageId, finalText, session);
    }

    boolean finalizeStream(long chatId, long messageId, String finalText, StreamSession session) {
        if (session.partialFinalDelivery) return false;
        // R8: close orphaned code fences BEFORE delivery (Hermes ensure_closed_code_fences)
        finalText = ensureClosedCodeFences(finalText);
        // Stop heartbeat
        stopHeartbeat(session);

        // Hermes parity: scrub think tags BEFORE silence marker check.
        // Hermes (stream_consumer.py:968) works with already-scrubbed accumulated
        // display text, then suppresses the marker. Java was checking raw text
        // first, so a NO_REPLY wrapped in <think> tags would leak to the user.
        finalText = scrubThinkFinal(session, finalText);

        // Silence marker suppression (Hermes parity: _is_intentional_silence_response).
        // If the final text is a silence marker, retract the streaming message instead
        // of showing NO_REPLY/[SILENT]/*** to the user.
        if (finalText.isBlank() || SilenceMarkerUtils.isSilenceMarker(finalText)) {
            if (session.deferredDelivery != null) return false;
            log.debug("Silence marker detected for chat {}, retracting streaming message", chatId);
            try {
                if (messageId > 0) telegramClient.deleteMessage(chatId, messageId);
            } catch (Exception e) {
                log.debug("Failed to delete streaming message for silence marker: {}", e.getMessage());
            }
            removeSession(chatId);
            return true;
        }

        long current = session.currentMessageId.get();
        long preview = current > 0 ? current : messageId;
        if (session.deferredDelivery != null) {
            enqueueDelivery(chatId, session, formattedOperations(chatId, finalText, session, preview));
            return false;
        }
        String formatted = formatForTelegramDelegate(finalText);
        boolean fresh = freshFinalTimeoutMs > 0 && session.streamStartTime != 0
            && System.currentTimeMillis() - session.streamStartTime > freshFinalTimeoutMs;
        boolean replace = session.useDraftStreaming || preview <= 0 || fresh
            || formatted.length() > MessageSplitter.TELEGRAM_MAX_LENGTH || !session.floodFallbackBuffer.isEmpty();
        List<DeliveryOperation> normal = replace
            ? formattedOperations(chatId, finalText, session, preview)
            : List.of(editOperation(chatId, preview, finalText, formatted, parseMode));
        List<DeliveryOperation> operations = normal;
        if (richMessageSupport != null && richMessageSupport.shouldAttemptRich(finalText)) {
            String richText = finalText;
            Long topic = session.messageThreadId > 0 ? session.messageThreadId : null;
            Long reply = replyFor(session, 0);
            operations = List.of(new DeliveryOperation(
                () -> richMessageSupport.sendRichMessage(chatId, richText, reply, topic),
                richMessageSupport::formatWasRejected, normal, preview));
        }
        Optional<Long> confirmed = enqueueDelivery(chatId, session, operations);
        if (confirmed.isPresent() && !hasDeferredDelivery(chatId)) {
            afterConfirmedDelivery(chatId);
            removeSession(chatId);
            return true;
        }
        return false;
    }

    /** Remove the current preview without creating an empty Telegram message. */
    public void discardStreamPreview(long chatId) {
        StreamSession session = sessions.get(chatId);
        if (session == null) return;
        stopHeartbeat(session);
        long messageId = session.currentMessageId.get();
        try {
            if (messageId > 0) telegramClient.deleteMessage(chatId, messageId);
        } catch (RuntimeException e) {
            log.debug("Failed to remove streaming preview for chat {}: {}", chatId, e.getMessage());
        } finally {
            removeSession(chatId);
        }
    }

    public boolean hasUnconfirmedDelivery(long chatId) {
        StreamSession session = sessions.get(chatId);
        return session != null && session.partialFinalDelivery;
    }

    /** Clears streaming state after an error or session reset. */
    public void clearStream(long chatId) {
        removeSession(chatId);
    }

    // ─── S5: Native draft streaming ───────────────────────────────

    /**
     * S5: Resolve whether native draft streaming should be used for this chat.
     *
     * @param session the per-chat session
     * @return {@code true} if draft streaming should be used
     */
    private boolean resolveDraftStreaming(StreamSession session) {
        if ("edit".equals(streamingTransport) || "off".equals(streamingTransport)) {
            return false;
        }
        String chatType = session.chatType != null ? session.chatType : "dm";
        boolean supported = telegramClient.supportsDraftStreaming(chatType);
        if (!supported) {
            if ("draft".equals(streamingTransport)) {
                log.debug("Draft streaming requested but unsupported (type={}) — falling back to edit", chatType);
            }
            return false;
        }
        return true;
    }

    /**
     * S5: Send a single draft frame for the current accumulated text.
     *
     * @param chatId target chat id
     * @param text   the formatted text to display as a draft
     * @param session the per-chat session
     * @return {@code true} if the draft frame was successfully sent
     */
    private boolean sendDraftFrame(long chatId, String text, StreamSession session) {
        int draftId = session.draftId;
        if (draftId == 0) {
            // Defensive: should never happen — draft id is set in startStream
            session.useDraftStreaming = false;
            return false;
        }
        boolean ok = telegramClient.sendDraft(chatId, text, draftId);
        if (!ok) {
            session.draftFailures.incrementAndGet();
            log.debug("Draft frame failed for chat {} (failures={})", chatId, session.draftFailures.get());
            if (session.draftFailures.get() >= 1) {
                log.info("Disabling draft streaming for chat {} after {} failures, falling back to edit-based",
                    chatId, session.draftFailures.get());
                session.useDraftStreaming = false;
            }
        }
        return ok;
    }

    /**
     * S5: Draft-based editStream — sends the accumulated text as a draft frame
     * instead of editing a message. Throttled to the same per-chat edit interval.
     *
     * @param chatId target chat id
     * @param text   the full accumulated text to display
     * @param session the per-chat session
     * @return {@code true} if the draft frame was sent, {@code false} if throttled or failed
     */
    private boolean editStreamDraft(long chatId, String text, StreamSession session) {
        // Throttle draft frames the same way as edits
        long now = System.currentTimeMillis();
        long last = session.lastEditTime;
        long currentInterval = EditThrottlePolicy.getEffectiveInterval(session, minIntervalMs);
        int charsAccumulated = text.length();
        boolean intervalElapsed = last == 0 || (now - last) >= currentInterval;
        boolean thresholdReached = bufferThreshold > 0 && charsAccumulated >= bufferThreshold;
        // Draft frames are rate-limited more strictly than edit-based: always
        // enforce the interval (the threshold bypass causes 429 floods because
        // each SSE token adds enough chars to exceed bufferThreshold=24).
        if (!intervalElapsed) {
            log.trace("Throttled draft frame for chat {} ({}ms since last, interval={})",
                chatId, last != 0 ? now - last : 0, currentInterval);
            return false;
        }

        // Scrub think blocks and format
        String scrubbed = scrubThink(session, text);

        // Hermes parity: hold back partial silence markers in draft too.
        // Without this, [SILENT] or NO_REPLY can flash to the user mid-stream.
        if (SilenceMarkerUtils.endsWithPartialSilenceMarker(scrubbed)) {
            log.debug("Holding back partial silence marker in draft for chat {}", chatId);
            return false;
        }

        String formatted = formatForTelegramDelegate(scrubbed);

        // Skip if content is unchanged
        String lastSent = session.lastSentText;
        if (lastSent != null && lastSent.equals(formatted)) {
            log.trace("Skipping redundant draft frame for chat {} (content unchanged)", chatId);
            return true;
        }

        // Send the draft frame
        boolean ok = sendDraftFrame(chatId, formatted, session);
        if (ok) {
            session.lastEditTime = now;
            session.lastSentText = formatted;
            session.floodStrikes.set(0);
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
        StreamSession session = sessionFor(chatId);
        setCurrentToolName(toolName, session);
    }

    void setCurrentToolName(String toolName, StreamSession session) {
        session.currentToolName = toolName;
    }

    /**
     * Handle a segment break — called after a tool execution completes.
     * Finalizes the current streaming message with the accumulated text so far,
     * then starts a new streaming message for the continuation.
     * This creates a visual separation between text segments and tool executions
     * without showing tool progress in the stream.
     */
    public void onSegmentBreak(long chatId, long messageId, String accumulatedText) {
        StreamSession session = sessionFor(chatId);
        onSegmentBreak(chatId, messageId, accumulatedText, session);
    }

    /**
     * Send a short progress message (tool call bubble) as a separate Telegram message.
     * Mirrors Hermes tool progress: each tool call gets its own message like "🔎 session_search..."
     * Not accumulated into the main streaming text.
     * Sent as PLAIN TEXT (no MarkdownV2 escaping) — tool args may contain regex/special chars.
     */
    public void sendProgressMessage(long chatId, String text) {
        try {
            // Hermes: raw text (no parse_mode) for tool progress — avoids escaping issues
            sendMessageWithNotification(chatId, text, streamingSilent);
        } catch (Exception e) {
            log.debug("Failed to send progress message for chat {}: {}", chatId, e.getMessage());
        }
    }

    void onSegmentBreak(long chatId, long messageId, String accumulatedText, StreamSession session) {
        if (session.partialFinalDelivery || accumulatedText == null || accumulatedText.isBlank()) {
            // No text accumulated yet — just clear the tool name
            session.currentToolName = null;
            return;
        }

        // S5: Draft streaming — on segment break, bump draftId so the next
        // text segment animates as a fresh preview. The accumulated text
        // from this segment is committed as a regular sendMessage (drafts
        // have no message_id to edit or finalize in-place).
        if (session.useDraftStreaming) {
            // Send the accumulated text as a real message (committing the draft)
            String scrubbed = scrubThink(session, accumulatedText);
            sendFormattedFinalMessage(chatId, scrubbed);
            // Bump draftId for the next segment
            int newDraftId = draftIdCounter.incrementAndGet();
            session.draftId = newDraftId;
            // Reset buffer tracking for the new segment
            session.charsSinceLastEdit = 0;
            session.lastEditTime = 0;
            session.lastSentText = null;
            session.currentToolName = null;
            log.debug("Segment break for chat {} — draft committed, new draftId={}", chatId, newDraftId);
            return;
        }

        // The caller's original ID may refer to an earlier committed segment.
        // If no current preview exists, send this segment rather than edit a stale ID.
        long effectiveMessageId = session.currentMessageId.get();
        String scrubbed = scrubThink(session, accumulatedText);
        if (effectiveMessageId <= 0) {
            sendFormattedFinalMessage(chatId, scrubbed);
        } else {
            String formatted = formatForTelegramDelegate(scrubbed);
            var operations = formatted.length() > MessageSplitter.TELEGRAM_MAX_LENGTH
                ? formattedOperations(chatId, scrubbed, session, effectiveMessageId)
                : List.of(editOperation(chatId, effectiveMessageId, scrubbed, formatted, parseMode));
            enqueueDelivery(chatId, session, operations);
        }
        // Clear the tool name since the tool is done
        session.currentToolName = null;
        // Reset buffer tracking for the new segment
        session.charsSinceLastEdit = 0;
        session.lastEditTime = System.currentTimeMillis();
        // Hermes: reset currentMessageId to null so the next editStream call
        // creates a NEW message (text after tool call appears below as a new message).
        // Track the old message ID for reply threading.
        session.currentMessageId.set(-1L);
    }

    // ─── Heartbeat ───────────────────────────────────────────────

    private void startHeartbeat(long chatId, StreamSession session) {
        stopHeartbeat(session); // Cancel any existing
        int interval = heartbeatIntervalSeconds > 0 ? heartbeatIntervalSeconds : 60;
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                checkHeartbeat(chatId, session);
            } catch (Exception e) {
                log.warn("Heartbeat error for chat {}: {}", chatId, e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
        session.heartbeatFuture = future;
    }

    void stopHeartbeat(StreamSession session) {
        ScheduledFuture<?> future = session.heartbeatFuture;
        if (future != null) {
            future.cancel(false);
            session.heartbeatFuture = null;
        }
    }

    private void checkHeartbeat(long chatId, StreamSession session) {
        if (session.partialFinalDelivery || session.deferredDelivery != null) return;
        long startTime = session.streamStartTime;
        long lastToken = session.lastTokenTime;
        long msgId = session.currentMessageId.get();
        // M5: For draft streaming, currentMessageId is null (no edit message to update).
        boolean draftActive = session.useDraftStreaming;
        if (startTime == 0 || lastToken == 0) {
            return;
        }
        if (msgId < 0 && !draftActive) {
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMs = heartbeatIntervalSeconds > 0 ? heartbeatIntervalSeconds * 1000L : 60000L;

        // Check if no tokens arrived recently (within the heartbeat interval)
        if ((now - lastToken) < intervalMs) {
            return; // Tokens are arriving, no need for heartbeat
        }

        long elapsedSeconds = (now - startTime) / 1000;
        if (elapsedSeconds < 10) {
            return; // Less than 10 seconds, don't show heartbeat yet
        }

        String toolName = session.currentToolName;
        long elapsedMinutes = elapsedSeconds / 60;
        String heartbeatText = "⏳ Working — " + (elapsedMinutes >= 1 ? elapsedMinutes + " min" : elapsedSeconds + "s");
        if (toolName != null && !toolName.isBlank()) {
            heartbeatText += " — " + toolName;
        }
        boolean disableNotification = streamingSilent;
        log.debug("Heartbeat for chat {}: {} (msgId={}, draft={})", chatId, heartbeatText, msgId, draftActive);
        try {
            if (msgId >= 0) {
                // Edit-based streaming: edit the existing message in-place
                // Hermes: raw text (no parse_mode) during streaming
                telegramClient.editMessageText(chatId, msgId, heartbeatText, null, disableNotification);
            } else {
                // Drafts have no message id. Create one quiet heartbeat message once,
                // then edit that same message rather than sending a new chat message
                // every heartbeat interval.
                long heartbeatMessageId = session.heartbeatMessageId.get();
                if (heartbeatMessageId >= 0) {
                    telegramClient.editMessageText(chatId, heartbeatMessageId, heartbeatText, null, disableNotification);
                } else {
                    telegramClient.sendMessage(chatId, heartbeatText, null, null, null, disableNotification)
                        .ifPresent(id -> session.heartbeatMessageId.compareAndSet(-1L, id));
                }
            }
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                log.debug("Heartbeat 429 rate limited for chat {}, skipping", chatId);
            } else {
                throw e;
            }
        }
    }

    // ─── B5: Adaptive rate limiting (delegated to EditThrottlePolicy) ──

    // Note: Hermes does not decrease the interval on success.
    // The interval stays at the backoff level and only flood strikes are reset to 0.

    // ─── B6: Think-block filtering (delegated to ThinkTagFilter) ───


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
        StreamSession session = sessionFor(chatId);
        return scrubThink(session, text);
    }

    /**
     * Stateless think-tag scrubbing for streaming display.
     * Uses regex on the full accumulated text — safe because it doesn't
     * maintain cross-call state. The stateful ThinkScrubber is reserved
     * for finalizeStream where boundary precision matters.
     *
     * Hermes parity: Hermes (stream_consumer.py) feeds delta to a stateful
     * filter, but java-agent passes accumulated text per token. Using the
     * stateful scrubber on accumulated text causes state corruption
     * (insideThinkBlock persists across re-processing). The regex approach
     * is equivalent for display purposes and avoids the state bug.
     */
    String scrubThink(StreamSession session, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Stateless regex scrub — handles closed tags, orphaned open tags,
        // and stray tags without cross-call state.
        String result = ThinkTagFilter.stripThinkTagsRegex(text);
        // S-2: Strip MEDIA: tags from streaming display. M3 (WP-11): streaming
        // variant holds back a trailing PARTIAL tag — the path may still be
        // arriving across chunk boundaries and must not flash raw.
        result = mediaDeliveryService.stripMediaTagsForDisplay(result, true);
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
        StreamSession session = sessionFor(chatId);
        return scrubThinkFinal(session, text);
    }

    String scrubThinkFinal(StreamSession session, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Use stateful scrubber for final — processes the complete text
        // with boundary-precise logic, then flushes any pending state.
        // Hermes parity: flush() releases pending partial tags as visible text
        // if they weren't confirmed as think tags (stream_consumer.py:830).
        ThinkTagFilter.ThinkScrubber scrubber = session.thinkScrubber;
        if (scrubber == null) {
            scrubber = new ThinkTagFilter.ThinkScrubber();
        }
        String result = scrubber.scrub(text);
        String flushed = scrubber.flush();
        if (!flushed.isEmpty()) {
            result = result + flushed;
        }
        // S-2: Strip MEDIA: tags as a safety net
        result = mediaDeliveryService.stripMediaTagsForDisplay(result);
        return result;
    }

    /**
     * B6: Regex-based think-tag stripping — delegate to {@link ThinkTagFilter}.
     * Kept as public static for backward compatibility (BotMessageProcessor calls this).
     */
    public static String stripThinkTagsRegex(String content) {
        return ThinkTagFilter.stripThinkTagsRegex(content);
    }

    // ─── B7: Silent notification helper ───────────────────────────

    /**
     * B7: Send a message with optional silent mode.
     * During streaming, the initial message is sent silently if streamingSilent is true.
     */
    private Optional<Long> sendMessageWithNotification(long chatId, String text, boolean forceNotification) {
        // Send with notification control: if forceNotification is true, send with notification
        // (disableNotification=false). If streamingSilent is true and forceNotification is false,
        // send silently (disableNotification=true). Hermes sends preview/progress with notify=false.
        boolean disableNotification = forceNotification ? false : streamingSilent;
        // P2.S6: route into the forum topic the stream was started in (StreamSession carries it).
        StreamSession session = sessionFor(chatId);
        Integer threadId = session.messageThreadId > 0 ? (int) session.messageThreadId : null;
        // Reply-to: only on the initial streaming message (reply-to-mode: first semantics).
        // After the first send, clear the replyToMessageId so edits, splits, and fresh-finals
        // do not reply to the user message — matching sendFormatted's "first" behavior.
        Long replyToMessageId = null;
        if (session.replyToMessageId > 0) {
            String mode = properties.getReplyToMode();
            if (mode == null || mode.isBlank() || "first".equalsIgnoreCase(mode) || "all".equalsIgnoreCase(mode)) {
                replyToMessageId = session.replyToMessageId;
            }
            // "first" → only the initial message replies; clear after use.
            if (mode == null || mode.isBlank() || "first".equalsIgnoreCase(mode)) {
                session.replyToMessageId = 0L;
            }
        }
        return telegramClient.sendMessage(chatId, text, null, replyToMessageId, threadId, disableNotification);
    }

    record DeliveryOperation(java.util.function.Supplier<Optional<Long>> send,
                             java.util.function.Predicate<TelegramApiException> formatRefusal,
                             List<DeliveryOperation> onFormatRefusal, long replacedPreview) {
        DeliveryOperation(java.util.function.Supplier<Optional<Long>> send, long replacedPreview) {
            this(send, error -> false, List.of(), replacedPreview);
        }
    }

    private DeliveryOperation editOperation(long chatId, long messageId, String plain, String formatted, String mode) {
        var plainEdit = new DeliveryOperation(() -> telegramClient.editFinalMessage(chatId, messageId, plain, null), 0);
        return new DeliveryOperation(() -> telegramClient.editFinalMessage(chatId, messageId, formatted, mode),
            error -> mode != null && error.getErrorCode() == 400 && error.isParseError(), List.of(plainEdit), 0);
    }

    public Optional<Long> sendFormattedFinalMessage(long chatId, String text) {
        var session = sessionFor(chatId);
        return enqueueDelivery(chatId, session, formattedOperations(chatId, text, session, 0));
    }

    private List<DeliveryOperation> formattedOperations(long chatId, String text, StreamSession session, long replacedPreview) {
        String formatted = formatForTelegramDelegate(text);
        Integer topic = session.messageThreadId > 0 ? (int) session.messageThreadId : null;
        boolean html = "HTML".equalsIgnoreCase(parseMode);
        List<String> parts = html
            ? (formatted.length() <= MessageSplitter.TELEGRAM_MAX_LENGTH ? List.of(text) : MessageSplitter.splitHtmlSource(text))
            : (formatted.length() <= MessageSplitter.TELEGRAM_MAX_LENGTH ? List.of(formatted)
                : MessageSplitter.splitAndFormat(text, parseMode));
        List<String> chunks = parts.stream().filter(chunk -> !chunk.isBlank()).toList();
        var operations = new java.util.ArrayList<DeliveryOperation>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String payload = html ? formatForTelegramDelegate(chunk) : chunk;
            Long reply = replyFor(session, i);
            long preview = i == chunks.size() - 1 ? replacedPreview : 0;
            var plain = new DeliveryOperation(() -> telegramClient.sendMessageChecked(
                chatId, chunk, null, reply, topic, null, false), preview);
            operations.add(parseMode == null ? plain : new DeliveryOperation(
                () -> telegramClient.sendMessageChecked(chatId, payload, parseMode, reply, topic, null, false),
                error -> error.getErrorCode() == 400 && error.isParseError(), List.of(plain), preview));
        }
        return operations;
    }

    private Long replyFor(StreamSession session, int part) {
        String mode = properties.getReplyToMode();
        return session.replyToMessageId > 0 && ("all".equalsIgnoreCase(mode)
            || part == 0 && "first".equalsIgnoreCase(mode)) ? session.replyToMessageId : null;
    }

    private Optional<Long> enqueueDelivery(long chatId, StreamSession session, List<DeliveryOperation> operations) {
        synchronized (session) {
            if (session.partialFinalDelivery || session.deliveryCancelled) return Optional.empty();
            session.delivery.addAll(operations);
            if (session.deliveryRetry != null || session.deliveryDraining) return Optional.empty();
            session.deliveryDraining = true;
        }
        return deliverQueued(chatId, session);
    }

    private Optional<Long> deliverQueued(long chatId, StreamSession session) {
        Optional<Long> last = Optional.empty();
        for (;;) {
            DeliveryOperation operation;
            synchronized (session) {
                if (session.deliveryCancelled || sessions.get(chatId) != session) {
                    failDelivery(session);
                    return Optional.empty();
                }
                operation = session.delivery.peekFirst();
                if (operation == null) {
                    session.deliveryDraining = false;
                    if (session.deliverySealed) session.deferredDelivery.complete(true);
                    return last;
                }
                if (session.deferredDelivery != null && System.nanoTime() >= session.floodDeliveryDeadlineNanos) {
                    failDelivery(session);
                    return Optional.empty();
                }
            }
            try {
                last = operation.send().get();
                synchronized (session) {
                    if (session.deliveryCancelled) {
                        failDelivery(session);
                        return Optional.empty();
                    }
                    if (last.isEmpty()) {
                        failDelivery(session);
                        return Optional.empty();
                    }
                    session.delivery.removeFirst();
                }
                if (operation.replacedPreview() > 0) {
                    removeReplacedPreview(chatId, operation.replacedPreview());
                }
            } catch (TelegramApiException error) {
                synchronized (session) {
                    if (session.deliveryCancelled) {
                        failDelivery(session);
                        return Optional.empty();
                    }
                    if (operation.formatRefusal().test(error)) {
                        session.delivery.removeFirst();
                        for (var fallback : operation.onFormatRefusal().reversed()) session.delivery.addFirst(fallback);
                        continue;
                    }
                    if (error.isRateLimit()) scheduleDelivery(chatId, session, error.getRetryAfter());
                    else failDelivery(session);
                }
                return Optional.empty();
            } catch (RuntimeException error) {
                log.warn("Could not confirm Telegram delivery for chat {}", chatId, error);
                synchronized (session) { failDelivery(session); }
                return Optional.empty();
            }
        }
    }

    private void scheduleDelivery(long chatId, StreamSession session, int retryAfter) {
        session.deliveryDraining = false;
        if (session.deliveryCancelled || retryAfter <= 0) {
            failDelivery(session);
            return;
        }
        long now = System.nanoTime();
        if (session.deferredDelivery == null) {
            session.deferredDelivery = new java.util.concurrent.CompletableFuture<>();
            session.floodDeliveryDeadlineNanos = now + TimeUnit.SECONDS.toNanos(FLOOD_DELIVERY_WINDOW_SECONDS);
        }
        long delay = TimeUnit.SECONDS.toNanos(retryAfter);
        if (delay >= session.floodDeliveryDeadlineNanos - now) {
            failDelivery(session);
            return;
        }
        try {
            session.deliveryRetry = deliveryExecutor.schedule(() -> {
                synchronized (session) {
                    session.deliveryRetry = null;
                    if (sessions.get(chatId) != session || session.deliveryCancelled) return;
                    session.deliveryDraining = true;
                    try {
                        deliveryWorkers.execute(() -> deliverQueued(chatId, session));
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        failDelivery(session);
                    }
                }
            }, delay, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            failDelivery(session);
        }
    }

    private void failDelivery(StreamSession session) {
        session.partialFinalDelivery = true;
        session.deliveryDraining = false;
        session.delivery.clear();
        if (session.deferredDelivery != null) session.deferredDelivery.complete(false);
    }

    public boolean hasDeferredDelivery(long chatId) {
        StreamSession session = sessions.get(chatId);
        return session != null && session.deferredDelivery != null;
    }

    public void afterDeferredDelivery(long chatId, Runnable action) {
        StreamSession session = sessions.get(chatId);
        if (session != null) synchronized (session) { session.afterDelivery.add(action); }
    }

    public void afterConfirmedDelivery(long chatId) {
        StreamSession session = sessions.get(chatId);
        if (session == null) return;
        List<Runnable> after;
        synchronized (session) {
            after = List.copyOf(session.afterDelivery);
            session.afterDelivery.clear();
        }
        for (Runnable action : after) {
            if (session.deliveryCancelled || sessions.get(chatId) != session) break;
            action.run();
        }
    }

    private void removeReplacedPreview(long chatId, long preview) {
        try {
            if (telegramClient.deleteMessage(chatId, preview)) return;
        } catch (RuntimeException error) {
            log.debug("Telegram preview removal failed ({})", error.getClass().getSimpleName());
        }
        log.warn("Could not remove replaced Telegram preview {} for chat {}", preview, chatId);
    }

    public boolean isFinishingDelivery(long chatId) {
        StreamSession session = sessions.get(chatId);
        return session != null && session.deliverySealed;
    }

    public void cancelDeferredDelivery(long chatId) {
        StreamSession session = sessions.get(chatId);
        if (session == null) return;
        synchronized (session) {
            session.deliveryCancelled = true;
            if (session.deliveryRetry != null) session.deliveryRetry.cancel(false);
            // A running drain owns the HTTP call. Keep the chat busy until it returns.
            if (!session.deliveryDraining) failDelivery(session);
        }
    }

    public java.util.concurrent.CompletableFuture<Boolean> sealDeferredDelivery(long chatId) {
        StreamSession session = sessions.get(chatId);
        if (session == null) return null;
        synchronized (session) {
            if (session.deferredDelivery == null) return null;
            session.deliverySealed = true;
            if (!session.deliveryDraining && session.delivery.isEmpty()) {
                session.deferredDelivery.complete(!session.partialFinalDelivery);
            }
            return session.deferredDelivery;
        }
    }

    // ─── Formatting ──────────────────────────────────────────────

    /**
     * Count the number of ``` (triple backtick) code fence markers in text.
     */

    /**
     * R8 (Hermes stream_consumer.py ensure_closed_code_fences): a response
     * truncated mid-code-block (finish_reason=length etc.) leaves an orphaned
     * ``` fence — everything after it renders as one giant code block; an
     * orphaned single backtick renders the rest as inline code. Append the
     * missing closers: triple fence if odd count, then inline backtick if the
     * standalone count (outside complete fences) is odd.
     */
    static String ensureClosedCodeFences(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Step 1: balance triple-backtick fences
        if (TelegramTextFormatter.countOccurrences(text, "```") % 2 == 1) {
            text = TelegramTextFormatter.stripTrailingNewlines(text) + "\n```";
        }
        // Step 2: balance single-backtick inline spans outside complete fences
        String withoutFences = text.replaceAll("(?s)```.*?```", "");
        withoutFences = withoutFences.replaceAll("```[^`]*$", "");
        if (TelegramTextFormatter.countOccurrences(withoutFences, "`") % 2 == 1) {
            text = text + "`";
        }
        return text;
    }

    // ─── Formatting (delegated to TelegramTextFormatter & SilenceMarkerUtils) ──

    /**
     * Instance delegate for {@link TelegramTextFormatter#formatForTelegram(String, String)}
     * using this editor's configured parse mode.
     */
    private String formatForTelegramDelegate(String text) {
        return TelegramTextFormatter.formatForTelegram(text, parseMode);
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
        StreamSession session = sessions.get(chatId);
        return session != null && session.useDraftStreaming;
    }

    /**
     * S5: Check if streaming is disabled (transport "off").
     *
     * @return {@code true} if streaming transport is "off"
     */
    public boolean isStreamingOff() {
        return "off".equals(streamingTransport);
    }

    // ─── c6: Session accessors for testing ────────────────────────

    /**
     * c6: Get the {@link StreamSession} for a chat (without creating one).
     * Package-private for testing.
     *
     * @param chatId target chat id
     * @return the session, or null if absent
     */
    StreamSession getSession(long chatId) {
        return sessions.get(chatId);
    }
}