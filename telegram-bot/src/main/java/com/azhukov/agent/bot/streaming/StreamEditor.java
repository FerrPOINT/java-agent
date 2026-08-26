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
    private long minIntervalMs; // Configured minimum interval (from streamEditInterval)

    // c6: All per-chat streaming state consolidated into a single map.
    private final ConcurrentHashMap<Long, StreamSession> sessions = new ConcurrentHashMap<>();

    // B5: Adaptive rate limiting — Hermes uses x2 multiplier, 10s max, 3 strikes
    private static final long MAX_INTERVAL_MS = 10000;
    private static final double FLOOD_MULTIPLIER = 2.0;
    private static final int MAX_FLOOD_STRIKES = 3;
    private static final int FLOOD_WARN_THRESHOLD = 3;

    // B7: Silent notification config
    private boolean streamingSilent;

    // P1 (m25): rich message support injected as a Spring bean
    private final RichMessageSupport richMessageSupport;

    // Streaming cursor config
    private String streamCursor;

    // Heartbeat
    private int heartbeatIntervalSeconds;
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
        if (s != null && s.heartbeatFuture != null) {
            s.heartbeatFuture.cancel(false);
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
        StreamSession session = sessionFor(chatId);
        session.messageThreadId = threadId;
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
        long currentInterval = getEffectiveInterval(session);

        // Buffer threshold: Hermes measures TOTAL accumulated text length (not delta since last edit).
        // The accumulated text is the full scrubbed text passed to editStream.
        int charsAccumulated = text.length();

        boolean intervalElapsed = last == 0 || (now - last) >= currentInterval;
        boolean thresholdReached = bufferThreshold > 0 && charsAccumulated >= bufferThreshold;

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
                success = handleEditFailure(chatId, effectiveMessageId, formatted, session, 429);
            } else {
                throw e;
            }
        }

        if (success) {
            session.lastEditTime = now;
            // P2-16: Track last sent text for redundant edit skip
            session.lastSentText = withCursor;
            // Hermes: on success, only reset flood strikes — interval stays at backoff level
            session.floodStrikes.set(0);
        } else {
            // B5: Edit failed. BUG FIX: use the error code from the typed exception —
            // TelegramClient.getLastApiErrorCode() is a shared mutable side-channel
            // and races between concurrently streaming chats (wrong flood strikes).
            success = handleEditFailure(chatId, effectiveMessageId, formatted, session,
                telegramClient.getLastApiErrorCode());
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
                handleEditFailure(chatId, messageId, firstPart, session, 429);
                return false;
            } else {
                throw e;
            }
        }

        if (success) {
            session.lastEditTime = System.currentTimeMillis();
            session.floodStrikes.set(0);
        } else {
            handleEditFailure(chatId, messageId, firstPart, session, 400);
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
     * <p>Fallback: if editMessageText returns false, tries sendMessage.
     *
     * @param chatId    target chat id
     * @param messageId the message id returned by {@link #startStream}
     * @param finalText the complete final text
     * @return {@code true} if the edit succeeded
     */
    public boolean finalizeStream(long chatId, long messageId, String finalText) {
        StreamSession session = sessionFor(chatId);
        return finalizeStream(chatId, messageId, finalText, session);
    }

    boolean finalizeStream(long chatId, long messageId, String finalText, StreamSession session) {
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
        if (SilenceMarkerUtils.isSilenceMarker(finalText)) {
            log.debug("Silence marker detected for chat {}, retracting streaming message", chatId);
            try {
                telegramClient.deleteMessage(chatId, messageId);
            } catch (Exception e) {
                log.debug("Failed to delete streaming message for silence marker: {}", e.getMessage());
            }
            removeSession(chatId);
            return true;
        }

        // S5: Draft streaming — the draft is "committed" by sending the final
        // text as a regular sendMessage. Drafts have no message_id to edit or
        // delete; the draft preview clears naturally on the client when the
        // real message arrives. Try rich message delivery first (same as the
        // edit-based finalize path), then fall back to MarkdownV2 sendMessage.
        if (session.useDraftStreaming) {
            // finalText already scrubbed above (before silence marker check)
            String scrubbed = finalText;

            // P1: Try rich message delivery first
            if (richMessageSupport != null && richMessageSupport.shouldAttemptRich(scrubbed)) {
                Optional<Long> richMsgId = richMessageSupport.sendRichMessage(chatId, scrubbed, null, null);
                if (richMsgId.isPresent()) {
                    log.debug("Draft finalized via rich message for chat {}", chatId);
                    removeSession(chatId);
                    return true;
                }
                log.debug("Rich message delivery failed for chat {}, falling back to MarkdownV2", chatId);
            }

            // Send the final text as a formatted message (commits the draft)
            // Hermes parity: apply MarkdownConverter before delivery, not raw markdown
            Optional<Long> finalMsgId = sendFormattedFinalMessage(chatId, scrubbed);
            removeSession(chatId);
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
        StringBuffer buffer = session.floodFallbackBuffer;
        if (buffer.length() > 0) {
            log.info("Flood fallback mode: sending buffered content ({} chars) as new message for chat {}", buffer.length(), chatId);
            String bufferedContent = buffer.toString();
            session.floodFallbackBuffer.setLength(0);
            // Delete the old streaming message if it exists
            long currentMsg = session.currentMessageId.get();
            long oldMsgId = currentMsg >= 0 ? currentMsg : messageId;
            if (oldMsgId > 0) {
                telegramClient.deleteMessage(chatId, oldMsgId);
            }
            // Send the buffered content as a formatted message (Hermes parity: apply MarkdownV2)
            Optional<Long> newMsgId = sendFormattedFinalMessage(chatId, bufferedContent);
            removeSession(chatId);
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
        long currentMsg = session.currentMessageId.get();
        long effectiveMessageId = currentMsg >= 0 ? currentMsg : messageId;

        // B6: Final scrub — also flush any remaining think-block state
        // finalText already scrubbed above (before silence marker check)
        String scrubbed = finalText;

        // Check fresh-final: if streaming exceeded timeout, delete old message
        // and send a new one
        long startTime = session.streamStartTime;
        // Hermes parity: freshFinalTimeoutMs <= 0 means DISABLED (config.py:804).
        // Java was enabling it for any stream when timeout was 0 (elapsed > 0).
        boolean freshFinal = freshFinalTimeoutMs > 0
            && startTime != 0
            && (System.currentTimeMillis() - startTime) > freshFinalTimeoutMs;

        if (freshFinal) {
            log.debug("Fresh-final for chat {} (stream exceeded {}ms), deleting old msg {} and sending new",
                chatId, freshFinalTimeoutMs, effectiveMessageId);
            // P1: Try rich message delivery first
            if (richMessageSupport != null && richMessageSupport.shouldAttemptRich(scrubbed)) {
                Optional<Long> richMsgId = richMessageSupport.sendRichMessage(chatId, scrubbed, null, null);
                if (richMsgId.isPresent()) {
                    telegramClient.deleteMessage(chatId, effectiveMessageId);
                    removeSession(chatId);
                    return true;
                }
            }
            // Delete old message and send new one (formatted — Hermes parity)
            telegramClient.deleteMessage(chatId, effectiveMessageId);
            Optional<Long> newMsgId = sendFormattedFinalMessage(chatId, scrubbed);
            removeSession(chatId);
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
                removeSession(chatId);
                return true;
            }
            // Rich failed — fall through to MarkdownV2 edit
            log.debug("Rich message delivery failed for chat {}, falling back to MarkdownV2", chatId);
        }

        // Hermes: on finalize, apply MarkdownConverter formatting (raw during streaming, formatted final)
        String formatted = formatForTelegramDelegate(scrubbed);
        // B7: Final message — NOT silent (push notification enabled)
        boolean success;
        try {
            success = telegramClient.editMessageText(chatId, effectiveMessageId, formatted, parseMode, false);
        } catch (TelegramApiException e) {
            if (e.isRateLimit()) {
                // 429 on final edit — fall through to sendMessage fallback
                log.warn("Final edit 429 rate limited for chat {}, trying sendMessage fallback", chatId);
                success = false;
            } else if (e.isParseError()) {
                // 400 parse error on final edit — text may not be properly escaped;
                // fall back to plain text edit to avoid visible backslashes.
                log.warn("Final edit parse error for chat {}, trying plain text edit", chatId);
                success = telegramClient.editMessageText(chatId, effectiveMessageId, scrubbed, null, false);
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
                removeSession(chatId);
                log.debug("Finalize fallback succeeded for chat {}, new messageId={}", chatId, fallbackMsgId.get());
                return true;
            }
        }

        removeSession(chatId);
        if (success) {
            log.debug("Finalized stream for chat {}, messageId={}", chatId, effectiveMessageId);
        } else {
            log.warn("Failed to finalize stream for chat {}, messageId={}", chatId, effectiveMessageId);
        }
        return success;
    }

    /**
     * Clears throttle state for a chat (e.g. after an error or session reset).
     *
     * @param chatId target chat id
     */
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
        long currentInterval = getEffectiveInterval(session);
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
        if (accumulatedText == null || accumulatedText.isBlank()) {
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
            String formatted = formatForTelegramDelegate(scrubbed);
            sendFormattedMessage(chatId, formatted);
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

        // Use session.currentMessageId (may differ from messageId parameter after
        // a previous segment break created a new message). The caller's messageId
        // is stale once onSegmentBreak resets currentMessageId to -1.
        long effectiveMessageId = session.currentMessageId.get();
        if (effectiveMessageId < 0) {
            effectiveMessageId = messageId;
        }

        // Finalize the current message with MarkdownV2 formatting (Hermes parity).
        // Hermes sends segment breaks with finalize=True (MarkdownV2 formatted).
        String scrubbed = scrubThink(session, accumulatedText);
        String formatted = formatForTelegramDelegate(scrubbed);
        try {
            telegramClient.editMessageText(chatId, effectiveMessageId, formatted, "MarkdownV2", false);
        } catch (TelegramApiException e) {
            if (!e.isRateLimit()) {
                throw e;
            }
            log.debug("Segment break edit 429 rate limited for chat {}, skipping", chatId);
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

    // ─── B5: Adaptive rate limiting ──────────────────────────────

    private long getEffectiveInterval(StreamSession session) {
        long interval = session.editInterval.get();
        if (interval == 0L) {
            return minIntervalMs;
        }
        return interval;
    }

    private void increaseInterval(StreamSession session) {
        long current = session.editInterval.get();
        if (current == 0L) {
            current = minIntervalMs;
        }
        long newInterval = Math.min((long) (current * FLOOD_MULTIPLIER), MAX_INTERVAL_MS);
        if (newInterval != current) {
            log.info("Increasing edit interval from {}ms to {}ms due to flood", current, newInterval);
            session.editInterval.set(newInterval);
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
    private boolean handleEditFailure(long chatId, long messageId, String formatted, StreamSession session,
                                      int errorCode) {
        if (errorCode == 400) {
            // Message too long — don't increment flood strikes, just truncate and retry.
            // BUG FIX (audit H14): the truncation target must respect the Telegram
            // editMessageText limit (4096 UTF-16 units), NOT streamingMaxChars —
            // the configured split threshold (32768) is a rich-message limit and
            // exceeds what editMessageText accepts, so truncating to it was a no-op
            // and the edit froze.
            log.debug("Edit failed with 400 (message too long) for chat {}, truncating and retrying", chatId);
            int safeLen = Math.min(streamingMaxChars > 0 ? streamingMaxChars : 4000, 4000);
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
                session.lastEditTime = System.currentTimeMillis();
                session.floodStrikes.set(0);
            }
            return retried;
        }

        // 429 or other failure — increment flood strikes
        int strikes = session.floodStrikes.incrementAndGet();
        log.debug("Edit failure (errorCode={}) for chat {}, flood strikes: {}", errorCode, chatId, strikes);

        // Increase interval on flood
        increaseInterval(session);

        if (strikes >= FLOOD_WARN_THRESHOLD) {
            log.warn("Flood strike threshold ({}) reached for chat {}, current interval: {}ms",
                strikes, chatId, getEffectiveInterval(session));
        }

        if (strikes >= MAX_FLOOD_STRIKES) {
            log.warn("Max flood strikes ({}) exceeded for chat {}, disabling streaming edits — buffering until final",
                MAX_FLOOD_STRIKES, chatId);
            session.streamingDisabled = true;
            // P2-16: Initialize the flood fallback buffer with the current content.
            // The buffer is sent via sendFormattedMessage (parseMode=MarkdownV2) on finalize,
            // so apply formatForTelegram here to escape special chars correctly.
            session.floodFallbackBuffer.append(formatForTelegramDelegate(formatted));
        }
        return false;
    }

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
        return telegramClient.sendMessage(chatId, text, null, null, threadId, disableNotification);
    }

    /**
     * Send a plain final message (no parse_mode). Used by draft finalize and
     * flood fallback where the text has not been MarkdownV2-escaped.
     */
    public Optional<Long> sendPlainMessage(long chatId, String text) {
        // BUG FIX (audit H14): split oversized finals — draft finalize / fresh-final /
        // flood fallback send raw text that can exceed the Telegram 4096 limit.
        if (text != null && text.length() > MessageSplitter.TELEGRAM_MAX_LENGTH) {
            List<String> chunks = MessageSplitter.split(text);
            Optional<Long> last = Optional.empty();
            for (String chunk : chunks) {
                if (chunk.isBlank()) continue;
                last = telegramClient.sendMessage(chatId, chunk, null, null, null, false);
            }
            return last;
        }
        return telegramClient.sendMessage(chatId, text, null, null, null, false);
    }

    /**
     * Send a formatted final message with MarkdownV2 conversion applied.
     * Used by draft finalize and fresh-final paths — raw markdown from the model
     * is converted to Telegram MarkdownV2 before sending (Hermes parity:
     * format_message is always applied before delivery).
     */
    public Optional<Long> sendFormattedFinalMessage(long chatId, String text) {
        String formatted = formatForTelegramDelegate(text);
        if (formatted.length() > MessageSplitter.TELEGRAM_MAX_LENGTH) {
            List<String> chunks = MessageSplitter.splitAndFormat(text, parseMode);
            Optional<Long> last = Optional.empty();
            for (String chunk : chunks) {
                if (chunk.isBlank()) continue;
                last = telegramClient.sendMessage(chatId, chunk, parseMode, null, null, false);
            }
            return last;
        }
        return telegramClient.sendMessage(chatId, formatted, parseMode, null, null, false);
    }

    /**
     * Send a formatted final message with parse_mode enabled.
     * Used by finalizeStream fallback paths where MarkdownV2 formatting is needed.
     * BUG FIX (audit H14): text above the Telegram limit is split into chunks —
     * previously a single oversized sendMessage failed with 400 and the final
     * content was lost entirely.
     */
    public Optional<Long> sendFormattedMessage(long chatId, String text) {
        if (text != null && text.length() > MessageSplitter.TELEGRAM_MAX_LENGTH) {
            List<String> chunks = MessageSplitter.splitAndFormat(text, parseMode);
            Optional<Long> last = Optional.empty();
            for (String chunk : chunks) {
                if (chunk.isBlank()) continue;
                last = telegramClient.sendMessage(chatId, chunk, parseMode, null, null);
            }
            return last;
        }
        return telegramClient.sendMessage(chatId, text, parseMode, null, null);
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