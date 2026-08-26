package com.azhukov.agent.bot.streaming;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * c6: Per-chat streaming state, replacing the 17 parallel {@code ConcurrentHashMap}s
 * that previously lived in {@link StreamEditor}.
 *
 * <p>All mutable per-chat streaming state is grouped into a single mutable holder
 * keyed by {@code chatId} in a {@code ConcurrentHashMap<Long, StreamSession>}.
 * Methods on {@link StreamEditor} now take a {@link StreamSession} parameter
 * (looked up once by the caller) instead of repeatedly consulting separate maps.
 *
 * <p>Concurrency notes:
 * <ul>
 *   <li>The outer {@code ConcurrentHashMap<Long, StreamSession>} provides thread-safe
 *       <em>lookup</em> of the session object.</li>
 *   <li>Within a session, the streaming lifecycle for a given chat is single-writer
 *       (the streaming thread for that chat). Individual fields use atomic types
 *       ({@link AtomicLong}, {@code volatile}/{@code AtomicInteger}-style reads)
 *       where they are read cross-thread — notably the edit interval, current
 *       message id, and the heartbeat future (which is cancelled from the
 *       heartbeat executor thread).</li>
 *   <li>Primitive fields ({@code long}, {@code int}, {@code boolean}) are read and
 *       written by the streaming thread; cross-thread reads (e.g. heartbeat) use
 *       the atomic wrappers where required.</li>
 * </ul>
 */
public class StreamSession {

    // ─── P2.S6: Forum-topic routing ──────────────────────────────
    /** Telegram forum thread the stream was started in (0 = no thread routing). */
    public volatile long messageThreadId = 0L;

    // ─── B5: Adaptive rate limiting ───────────────────────────────
    /** Per-chat adaptive edit interval in ms; starts at minIntervalMs, grows on 429. */
    public final AtomicLong editInterval = new AtomicLong(0);
    /** Timestamp (ms) of the last editMessageText call. 0 / unset means "never edited". */
    public volatile long lastEditTime = 0L;
    /** Consecutive flood (429) strikes; streaming disables after {@code MAX_FLOOD_STRIKES}. */
    public final AtomicInteger floodStrikes = new AtomicInteger(0);
    /** True once streaming edits are disabled due to flood limits. */
    public volatile boolean streamingDisabled = false;

    // ─── P2-16: Flood fallback buffer ─────────────────────────────
    /** Buffered formatted content while streaming edits are disabled (flood fallback). */
    public final StringBuffer floodFallbackBuffer = new StringBuffer();

    // ─── P2-16: Redundant edit skip ───────────────────────────────
    /** Last text (with cursor) actually sent to Telegram, to skip no-op edits. */
    public volatile String lastSentText = null;

    // ─── B6: Think-block scrubber (stateful, per-chat) ───────────
    public volatile ThinkTagFilter.ThinkScrubber thinkScrubber = null;

    // ─── Heartbeat / fresh-final ─────────────────────────────────
    /** Wall-clock (ms) when the stream for this chat started. */
    public volatile long streamStartTime = 0L;
    /** Wall-clock (ms) of the last received token. */
    public volatile long lastTokenTime = 0L;
    /** Scheduled heartbeat task for this chat (cancelled on finalize/cleanup). */
    public volatile ScheduledFuture<?> heartbeatFuture = null;
    /** Message created for a draft-stream heartbeat; subsequent heartbeats edit it in place. */
    public final AtomicLong heartbeatMessageId = new AtomicLong(-1L);

    // ─── Buffer threshold tracking ───────────────────────────────
    /** Chars accumulated since last edit (unused by current logic but kept for completeness). */
    public volatile int charsSinceLastEdit = 0;

    // ─── Tool name tracking ──────────────────────────────────────
    /** Current tool name (for heartbeat display). */
    public volatile String currentToolName = null;

    // ─── Split during streaming ─────────────────────────────────
    /** Current streaming message id; may differ from the caller's messageId after a split. */
    public final AtomicLong currentMessageId = new AtomicLong(-1L);

    // ─── S5: Native draft streaming state ────────────────────────
    /** True when draft streaming is active for this chat (else edit-based). */
    public volatile boolean useDraftStreaming = false;
    /** Monotonic draft id for this chat; bumped on segment break. */
    public volatile int draftId = 0;
    /** Draft failure count; after 2, fall back to edit-based. */
    public final AtomicInteger draftFailures = new AtomicInteger(0);
    /** Chat type hint ("dm", "group", "supergroup", "forum", ...). Set when stream starts. */
    public volatile String chatType = "dm";

    /**
     * Reset the per-chat streaming state for a new stream.
     * Resets the mutable fields to their "fresh stream" defaults. The atomic holders
     * ({@link #editInterval}, {@link #currentMessageId}) are also reset. The heartbeat
     * future is left untouched — callers cancel it explicitly via
     * {@link StreamEditor#stopHeartbeat(StreamSession)}.
     */
    public void resetForNewStream() {
        editInterval.set(0L);
        lastEditTime = 0L;
        floodStrikes.set(0);
        streamingDisabled = false;
        floodFallbackBuffer.setLength(0);
        lastSentText = null;
        thinkScrubber = null;
        currentToolName = null;
        currentMessageId.set(-1L);
        heartbeatMessageId.set(-1L);
        useDraftStreaming = false;
        draftId = 0;
        draftFailures.set(0);
        // chatType is intentionally NOT reset here — callers set it explicitly.
    }

    /**
     * Full cleanup: clear all per-chat state. Used by {@link StreamEditor#clearStream}
     * and {@link StreamEditor#cleanupStream}. Resets everything including chatType.
     */
    public void clear() {
        resetForNewStream();
        streamStartTime = 0L;
        lastTokenTime = 0L;
        chatType = "dm";
    }

    /**
     * Initialize the think scrubber for this chat if not already present.
     * @return the scrubber (never null after this call)
     */
    public ThinkTagFilter.ThinkScrubber ensureThinkScrubber() {
        if (thinkScrubber == null) {
            thinkScrubber = new ThinkTagFilter.ThinkScrubber();
        }
        return thinkScrubber;
    }
}