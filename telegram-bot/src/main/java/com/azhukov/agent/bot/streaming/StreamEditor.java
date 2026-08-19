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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
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
            session.thinkScrubber = new ThinkScrubber();
            log.debug("Streaming transport is 'off' for chat {}, no initial message", chatId);
            return Optional.empty();
        }

        // S5: Store chat type and resolve draft streaming
        session.chatType = chatType != null ? chatType.toLowerCase() : "dm";
        boolean useDraft = resolveDraftStreaming(session);
        session.useDraftStreaming = useDraft;
        session.draftFailures = 0;

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
                String formatted = formatForTelegram(scrubbed);
                if (!formatted.isEmpty() && formatted.length() >= 4) {
                    boolean draftOk = sendDraftFrame(chatId, formatted, session);
                    if (!draftOk) {
                        // Draft failed on first attempt — fall back to edit-based
                        log.info("First draft frame failed for chat {}, falling back to edit-based", chatId);
                        session.useDraftStreaming = false;
                        // Continue with regular startStream path below
                    } else {
                        // Draft streaming active — no message id (drafts have no message_id)
                        session.thinkScrubber = new ThinkScrubber();
                        return Optional.empty();
                    }
                } else {
                    // Not enough text yet — draft streaming will start on first editStream
                    session.thinkScrubber = new ThinkScrubber();
                    return Optional.empty();
                }
            } else {
                // No initial text — draft streaming will start on first editStream
                session.thinkScrubber = new ThinkScrubber();
                return Optional.empty();
            }
        }

        // B5: Reset flood state for the new stream
        session.resetForNewStream();
        // M27: fresh scrubber for the new stream
        session.thinkScrubber = new ThinkScrubber();

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
            String formatted = formatForTelegram(scrubbed);
            session.floodFallbackBuffer.setLength(0);
            session.floodFallbackBuffer.append(formatted);
            return false;
        }

        // S5: Native draft streaming — route mid-stream frames through sendDraft.
        if (session.useDraftStreaming && !session.streamingDisabled) {
            // Check failure threshold — after 2 failures, fall back to edit-based
            if (session.draftFailures >= 1) {
                log.info("Draft streaming disabled for chat {} after {} failures, falling back to edit-based", chatId, session.draftFailures);
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
            String formattedFallback = formatForTelegram(scrubbedFallback);
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
                    session.floodStrikes = 0;
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
        String formatted = scrubbed;

        // Partial silence marker holdback: don't render a chunk that ends with an incomplete
        // marker like "NO" / "NO_R" / "[SILE" / "**". Wait for the next chunk.
        if (endsWithPartialSilenceMarker(formatted)) {
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
                success = handleEditFailure(chatId, effectiveMessageId, formatted, session);
            } else {
                throw e;
            }
        }

        if (success) {
            session.lastEditTime = now;
            // P2-16: Track last sent text for redundant edit skip
            session.lastSentText = withCursor;
            // Hermes: on success, only reset flood strikes — interval stays at backoff level
            session.floodStrikes = 0;
        } else {
            // B5: Edit failed — check error code. handleEditFailure may
            // do a truncated retry for 400 errors; if the retry succeeds,
            // treat the overall editStream as successful.
            success = handleEditFailure(chatId, effectiveMessageId, formatted, session);
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

        // Code fence balancing: if the split point falls inside an open ``` block,
        // close the fence at the end of the first chunk and reopen it at the start
        // of the remainder (Hermes balance_fences_across_chunks).
        String firstPart = withCursor.substring(0, Math.min(firstLen, withCursor.length()));
        String remainder = withCursor.length() > firstLen ? withCursor.substring(firstLen) : "";

        // Count ``` (triple backtick) occurrences in firstPart
        // If odd, we're inside an unclosed code block at the split point
        int fenceCount = countCodeFences(firstPart);
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
                handleEditFailure(chatId, messageId, firstPart, session);
                return false;
            } else {
                throw e;
            }
        }

        if (success) {
            session.lastEditTime = System.currentTimeMillis();
            session.floodStrikes = 0;
        } else {
            handleEditFailure(chatId, messageId, firstPart, session);
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
        // Stop heartbeat
        stopHeartbeat(session);

        // Silence marker suppression (Hermes parity: _is_intentional_silence_response).
        // If the final text is a silence marker, retract the streaming message instead
        // of showing NO_REPLY/[SILENT]/*** to the user.
        if (isSilenceMarker(finalText)) {
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
            String scrubbed = scrubThinkFinal(session, finalText);

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

            // Send the final text as a regular message (commits the draft)
            String formatted = scrubbed;
            Optional<Long> finalMsgId = sendPlainMessage(chatId, formatted);
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
        StringBuilder buffer = session.floodFallbackBuffer;
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
            // Send the buffered content as a new message (plain text — no MarkdownV2 escaping)
            Optional<Long> newMsgId = sendPlainMessage(chatId, bufferedContent);
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
        String scrubbed = scrubThinkFinal(session, finalText);

        // Check fresh-final: if streaming exceeded timeout, delete old message
        // and send a new one
        long startTime = session.streamStartTime;
        boolean freshFinal = startTime != 0
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
            // Delete old message and send new one (plain text — streaming output is raw)
            telegramClient.deleteMessage(chatId, effectiveMessageId);
            String formatted = scrubbed;
            Optional<Long> newMsgId = sendPlainMessage(chatId, formatted);
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
            session.draftFailures = session.draftFailures + 1;
            log.debug("Draft frame failed for chat {} (failures={})", chatId, session.draftFailures);
            if (session.draftFailures >= 1) {
                log.info("Disabling draft streaming for chat {} after {} failures, falling back to edit-based",
                    chatId, session.draftFailures);
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
        if (!intervalElapsed && !thresholdReached) {
            log.trace("Throttled draft frame for chat {} ({}ms since last, interval={})",
                chatId, last != 0 ? now - last : 0, currentInterval);
            return false;
        }

        // Scrub think blocks and format
        String scrubbed = scrubThink(session, text);
        String formatted = formatForTelegram(scrubbed);

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
            session.floodStrikes = 0;
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
            String formatted = formatForTelegram(scrubbed);
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
        String formatted = formatForTelegram(scrubbed);
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
        if (startTime == 0 || lastToken == 0 || msgId < 0) {
            // M5: If draft streaming is active but no messageId, still can't edit — skip
            if (!draftActive) {
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
    private boolean handleEditFailure(long chatId, long messageId, String formatted, StreamSession session) {
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
                session.lastEditTime = System.currentTimeMillis();
                session.floodStrikes = 0;
            }
            return retried;
        }

        // 429 or other failure — increment flood strikes
        int strikes = session.floodStrikes = session.floodStrikes + 1;
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
            session.floodFallbackBuffer.append(formatForTelegram(formatted));
        }
        return false;
    }

    // ─── B6: Think-block filtering ───────────────────────────────

    /**
     * Stateful scrubber for {@code Ӥ blocks.
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
        // Hermes uses: <REASONING_SCRATCHPAD>, Ӥ, <reasoning>, <THINKING>, <thinking>, <thought>
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
         * Stateful: if a {@code Ӥ tag opens but no closing tag is seen,
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

            // If we have a pending partial opening tag from the previous chunk,
            // prepend it to this chunk so the tag is reassembled correctly (Hermes parity).
            // Hermes stores partial tags in _think_buffer and prepends to the next chunk.
            if (pendingTag.length() > 0) {
                input = pendingTag.toString() + input;
                pendingTag.setLength(0);
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
        StreamSession session = sessionFor(chatId);
        return scrubThink(session, text);
    }

    String scrubThink(StreamSession session, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        ThinkScrubber scrubber = session.thinkScrubber;
        if (scrubber == null) {
            scrubber = new ThinkScrubber();
            session.thinkScrubber = scrubber;
        }
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
        StreamSession session = sessionFor(chatId);
        return scrubThinkFinal(session, text);
    }

    String scrubThinkFinal(StreamSession session, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        ThinkScrubber scrubber = session.thinkScrubber;
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

    public static String stripThinkTagsRegex(String content) {
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
        // Send with notification control: if forceNotification is true, send with notification
        // (disableNotification=false). If streamingSilent is true and forceNotification is false,
        // send silently (disableNotification=true). Hermes sends preview/progress with notify=false.
        boolean disableNotification = forceNotification ? false : streamingSilent;
        return telegramClient.sendMessage(chatId, text, null, null, null, disableNotification);
    }

    /**
     * Send a plain final message (no parse_mode). Used by draft finalize and
     * flood fallback where the text has not been MarkdownV2-escaped.
     */
    public Optional<Long> sendPlainMessage(long chatId, String text) {
        return telegramClient.sendMessage(chatId, text, null, null, null);
    }

    /**
     * Send a formatted final message with parse_mode enabled.
     * Used by finalizeStream fallback paths where MarkdownV2 formatting is needed.
     */
    public Optional<Long> sendFormattedMessage(long chatId, String text) {
        return telegramClient.sendMessage(chatId, text, parseMode, null, null);
    }

    // ─── Formatting ──────────────────────────────────────────────

    /**
     * Count the number of ``` (triple backtick) code fence markers in text.
     */
    private int countCodeFences(String text) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf("```", idx)) >= 0) {
            count++;
            idx += 3;
        }
        return count;
    }

    /**
     * Check if text ends with a partial silence marker prefix. If we render these,
     * the user sees "NO" / "NO_R" / "NO_RE" flash before the complete marker is scrubbed.
     * Hermes holdback: wait for the next chunk if text ends with a silence-marker prefix.
     */
    private boolean endsWithPartialSilenceMarker(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.trim();
        return t.endsWith("NO") || t.endsWith("NO_") || t.endsWith("NO_R") || t.endsWith("NO_RE")
            || t.endsWith("NO_REPL") || t.endsWith("NO_REPLY")
            || t.endsWith("[") || t.endsWith("[S") || t.endsWith("[SI") || t.endsWith("[SIL")
            || t.endsWith("[SILE") || t.endsWith("[SILEN") || t.endsWith("[SILENT")
            || t.endsWith("***") || t.endsWith("**");
    }

    /**
     * Check if text is an intentional silence marker (Hermes parity: _is_intentional_silence_response).
     * These markers indicate the model chose not to respond — the streaming message should be
     * retracted rather than showing the marker to the user.
     */
    private boolean isSilenceMarker(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        return "NO_REPLY".equals(trimmed)
            || "[SILENT]".equals(trimmed)
            || "***".equals(trimmed)
            || trimmed.startsWith("NO_REPLY")
            && trimmed.length() <= 20; // catch NO_REPLY with trailing whitespace/punctuation
    }

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