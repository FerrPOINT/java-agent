package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hermes parity (gateway/run.py progress-drain loop + display_config
 * tool_progress_grouping="accumulate"): tool progress renders as ONE
 * accumulating Telegram bubble per turn, not one message per tool.
 *
 * <ul>
 *   <li>First tool line → a NEW silent message; the id is kept.</li>
 *   <li>Subsequent lines → editMessageText of that same message with all
 *       lines joined by newlines — throttled to one edit per
 *       {@code PROGRESS_EDIT_INTERVAL_MS} (Hermes _PROGRESS_EDIT_INTERVAL=1.5s)
 *       to stay under Telegram flood control.</li>
 *   <li>Consecutive identical lines collapse to {@code line (×N)} (Hermes
 *       __dedup__ queue message).</li>
 *   <li>A content segment break CLOSES the bubble: the next tool opens a
 *       fresh bubble below the content (Hermes __reset__ marker, issue
 *       #7885 linearization regression).</li>
 *   <li>Overflow: a bubble never grows past ~30 lines / 3500 chars — it is
 *       rolled into a new bubble (Hermes _roll_progress_overflow).</li>
 * </ul>
 *
 * <p>This is what kills the per-tool message spam that was causing real
 * Telegram 429s: N tool calls cost ONE sendMessage + N throttled edits
 * instead of N sendMessages.</p>
 */
public class ToolProgressBubble {

    private static final Logger log = LoggerFactory.getLogger(ToolProgressBubble.class);

    /** Hermes _PROGRESS_EDIT_INTERVAL = 1.5s minimum between edits. */
    static final long PROGRESS_EDIT_INTERVAL_MS = 1500L;
    /** Hermes progress bubble soft limits (message must stay <4096 chars). */
    static final int MAX_LINES = 30;
    static final int MAX_CHARS = 3500;

    private final TelegramClient telegramClient;
    private final boolean silent;
    /** Injectable clock (millis) so tests can advance past the edit throttle. */
    private final java.util.function.LongSupplier clock;

    private Long bubbleMessageId = null;
    private final List<String> lines = new ArrayList<>();
    private long lastEditAt = 0L;

    public ToolProgressBubble(TelegramClient telegramClient, boolean silent) {
        this(telegramClient, silent, System::currentTimeMillis);
    }

    ToolProgressBubble(TelegramClient telegramClient, boolean silent,
                       java.util.function.LongSupplier clock) {
        this.telegramClient = telegramClient;
        this.silent = silent;
        this.clock = clock;
    }

    /** Append one tool line to the bubble (dedup + throttle + overflow aware). */
    public void appendLine(long chatId, String line) {
        if (line == null || line.isBlank()) return;
        // Hermes dedup: consecutive identical messages collapse with a counter
        if (!lines.isEmpty() && line.equals(stripCounter(lines.get(lines.size() - 1)))) {
            int count = counterOf(lines.get(lines.size() - 1)) + 1;
            lines.set(lines.size() - 1, line + " (×" + count + ")");
        } else {
            lines.add(line);
        }
        render(chatId);
    }

    /**
     * Content segment landed in chat — close the current bubble so the next
     * tool opens a fresh one below the content (Hermes __reset__ marker).
     */
    public void closeBubble() {
        bubbleMessageId = null;
        lines.clear();
    }

    private void render(long chatId) {
        long now = clock.getAsLong();
        String text = String.join("\n", lines);
        boolean overflow = lines.size() >= MAX_LINES || text.length() >= MAX_CHARS;
        try {
            if (bubbleMessageId == null) {
                // First line of a bubble: send a new silent message
                Optional<Long> id = telegramClient.sendMessage(chatId, truncate(text), null,
                    null, null, silent);
                id.ifPresent(this::setBubbleId);
                lastEditAt = now;
                return;
            }
            // Throttle edits to one per interval (Hermes _PROGRESS_EDIT_INTERVAL)
            if (now - lastEditAt < PROGRESS_EDIT_INTERVAL_MS && !overflow) return;
            boolean ok = telegramClient.editMessageText(chatId, bubbleMessageId, truncate(text), null, silent);
            lastEditAt = now;
            if (!ok) {
                // message deleted or not editable — start a fresh bubble
                bubbleMessageId = null;
            } else if (overflow) {
                // Hermes _roll_progress_overflow: keep only recent lines in a new bubble
                roll();
            }
        } catch (Exception e) {
            log.debug("Tool progress bubble render failed for chat {}: {}", chatId, e.getMessage());
            lastEditAt = now;
        }
    }

    private void roll() {
        List<String> tail = new ArrayList<>(lines.subList(Math.max(0, lines.size() - 3), lines.size()));
        lines.clear();
        lines.addAll(tail);
        bubbleMessageId = null; // next append sends a fresh bubble with the tail
    }

    private void setBubbleId(Long id) {
        this.bubbleMessageId = id;
    }

    private static String truncate(String text) {
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS - 20) + "\n…" : text;
    }

    private static String stripCounter(String line) {
        int i = line.lastIndexOf(" (×");
        return i > 0 && line.endsWith(")") ? line.substring(0, i) : line;
    }

    private static int counterOf(String line) {
        int i = line.lastIndexOf(" (×");
        if (i > 0 && line.endsWith(")")) {
            try {
                return Integer.parseInt(line.substring(i + 3, line.length() - 1));
            } catch (NumberFormatException ignored) { }
        }
        return 1;
    }
}
