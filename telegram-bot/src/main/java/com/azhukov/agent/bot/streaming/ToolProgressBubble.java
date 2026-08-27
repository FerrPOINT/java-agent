package com.azhukov.agent.bot.streaming;

import com.azhukov.agent.bot.client.TelegramClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hermes parity (gateway/run.py send_progress_messages + display_config
 * tool_progress_grouping="accumulate"): tool progress renders as ONE
 * accumulating Telegram bubble per turn, not one message per tool.
 *
 * <ul>
 *   <li>First tool line → a NEW silent message; the id is kept.</li>
 *   <li>Subsequent lines → editMessageText of that same message with all
 *       lines joined by newlines — throttled to one edit per
 *       {@code PROGRESS_EDIT_INTERVAL_MS} (Hermes _PROGRESS_EDIT_INTERVAL=1.5s).
 *       A throttled edit is NOT dropped: Hermes waits out the interval and
 *       loops back to send one batched edit, so a delayed flush is scheduled
 *       here too ({@code dirty} + delayedExecutor).</li>
 *   <li>Consecutive identical lines collapse to {@code line (×N)} (Hermes
 *       __dedup__ queue message).</li>
 *   <li>A content segment break CLOSES the bubble: the next tool opens a
 *       fresh bubble below the content (Hermes __reset__ marker, issue
 *       #7885 linearization regression).</li>
 *   <li>Overflow (Hermes _roll_progress_overflow_if_needed): bubbles are
 *       partitioned by the platform char limit (4096 − 64 headroom); the
 *       current bubble is edited down to the first group and a NEW bubble is
 *       sent for the tail. NO lines are ever dropped and there is no
 *       invented line-count cap.</li>
 *   <li>No-op edit guard: an edit whose text equals the last rendered text
 *       is skipped, so Telegram's "message is not modified" cannot
 *       misclassify as a dead bubble.</li>
 * </ul>
 */
public class ToolProgressBubble {

    private static final Logger log = LoggerFactory.getLogger(ToolProgressBubble.class);

    /** Hermes _PROGRESS_EDIT_INTERVAL = 1.5s minimum between edits. */
    static final long PROGRESS_EDIT_INTERVAL_MS = 1500L;
    /** Hermes _PROGRESS_TEXT_LIMIT = MAX_MESSAGE_LENGTH − 64 headroom. */
    static final int MAX_CHARS = 4096 - 64;
    /** Hard Telegram send cap — safety truncate only. */
    static final int HARD_CHAR_CAP = 4096;

    private final TelegramClient telegramClient;
    private final boolean silent;
    /** Injectable clock (millis) so tests can advance past the edit throttle. */
    private final java.util.function.LongSupplier clock;

    private Long bubbleMessageId = null;
    private final List<String> lines = new ArrayList<>();
    private long lastEditAt = 0L;
    /** True when lines changed but the throttled edit has not rendered yet. */
    private boolean dirty = false;
    private String lastRenderedText = "";
    private boolean flushScheduled = false;
    private long chatIdCache = 0L;

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
        chatIdCache = chatId;
        // Hermes dedup: consecutive identical messages collapse with a counter
        if (!lines.isEmpty() && line.equals(stripCounter(lines.get(lines.size() - 1)))) {
            int count = counterOf(lines.get(lines.size() - 1)) + 1;
            lines.set(lines.size() - 1, line + " (×" + count + ")");
        } else {
            lines.add(line);
        }
        render(chatId, false);
    }

    /**
     * Content segment landed in chat — close the current bubble so the next
     * tool opens a fresh one below the content (Hermes __reset__ marker).
     */
    public void closeBubble() {
        // Hermes drain path flushes pending text before resetting: give a
        // throttled-but-unrendered line its final edit before the bubble id
        // is discarded.
        if (dirty && bubbleMessageId != null && !lines.isEmpty() && chatIdCache != 0L) {
            render(chatIdCache, true);
        }
        bubbleMessageId = null;
        lines.clear();
        lastRenderedText = "";
        dirty = false;
    }

    /** Render current lines: send first bubble, then throttled edits. */
    private void render(long chatId, boolean force) {
        long now = clock.getAsLong();
        String text = String.join("\n", lines);
        try {
            if (bubbleMessageId == null) {
                Optional<Long> id = telegramClient.sendMessage(chatId, hardTruncate(text), null,
                    null, null, silent);
                if (id.isPresent()) {
                    bubbleMessageId = id.get();
                    dirty = false;
                    lastRenderedText = text;
                    log.info("Tool progress bubble OPENED for chat {} (msgId={}, text='{}')",
                        chatId, id.get(), firstLine(text));
                } else {
                    log.warn("Tool progress bubble sendMessage returned no message id for chat {} — retrying on next tool", chatId);
                }
                lastEditAt = now;
                return;
            }
            // Hermes waits out the throttle then sends ONE batched edit;
            // never drop the pending text — schedule a delayed flush.
            if (!force && now - lastEditAt < PROGRESS_EDIT_INTERVAL_MS) {
                dirty = true;
                scheduleFlush(chatId);
                return;
            }
            dirty = false;
            editOrRoll(chatId, text);
            lastEditAt = now;
        } catch (Exception e) {
            log.debug("Tool progress bubble render failed for chat {}: {}", chatId, e.getMessage());
            lastEditAt = now;
        }
    }

    /**
     * Hermes edit path: partition lines into platform-sized groups. Single
     * group → one edit. Overflow → edit the current bubble down to the first
     * group and send a NEW bubble for the tail group (no lines dropped).
     */
    private void editOrRoll(long chatId, String fullText) {
        List<List<String>> groups = splitGroups();
        String currentText = String.join("\n", groups.get(0));
        if (currentText.equals(lastRenderedText)) {
            // No-op edit guard — "message is not modified" must not kill the bubble.
        } else {
            boolean ok = telegramClient.editMessageText(chatId, bubbleMessageId,
                hardTruncate(currentText), null, silent);
            log.info("Tool progress bubble EDIT for chat {} (msgId={}, ok={}, lines={}, text='{}')",
                chatId, bubbleMessageId, ok, lines.size(), firstLine(currentText));
            if (!ok) {
                // Permanent edit failure (message gone): start a fresh bubble.
                bubbleMessageId = null;
                lastRenderedText = "";
                return;
            }
            lastRenderedText = currentText;
        }
        if (groups.size() > 1) {
            // Overflow roll: the tail becomes a new bubble below.
            String tailText = String.join("\n", groups.get(groups.size() - 1));
            Optional<Long> id = telegramClient.sendMessage(chatId, hardTruncate(tailText),
                null, null, null, silent);
            if (id.isPresent()) {
                bubbleMessageId = id.get();
                lastRenderedText = tailText;
                log.info("Tool progress bubble ROLLED for chat {} (new msgId={}, groups={})",
                    chatId, id.get(), groups.size());
            }
        }
    }

    /** Hermes _split_progress_groups: partition by char limit, keep every line. */
    private List<List<String>> splitGroups() {
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            String candidate = current.isEmpty() ? line
                : String.join("\n", current) + "\n" + line;
            if (!current.isEmpty() && candidate.length() > MAX_CHARS) {
                groups.add(current);
                current = new ArrayList<>();
                current.add(line);
            } else {
                current.add(line);
            }
        }
        if (!current.isEmpty()) groups.add(current);
        if (groups.isEmpty()) groups.add(new ArrayList<>(lines));
        return groups;
    }

    /** Hermes throttle semantics: wait out the interval, then one batched edit. */
    private void scheduleFlush(long chatId) {
        if (flushScheduled) return;
        flushScheduled = true;
        long delay = Math.max(1L, PROGRESS_EDIT_INTERVAL_MS - (clock.getAsLong() - lastEditAt));
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            flushScheduled = false;
            if (!dirty || bubbleMessageId == null || lines.isEmpty()) return;
            render(chatId, true);
        });
    }

    /** Test hook: flush a throttled render immediately. */
    void flushNow(long chatId) {
        if (dirty || bubbleMessageId == null || lines.isEmpty()) return;
        render(chatId, true);
    }

    private static String firstLine(String text) {
        if (text == null || text.isEmpty()) return "";
        int nl = text.indexOf('\n');
        String first = nl > 0 ? text.substring(0, nl) : text;
        return first.length() > 80 ? first.substring(0, 80) : first;
    }

    private static String hardTruncate(String text) {
        return text.length() > HARD_CHAR_CAP ? text.substring(0, HARD_CHAR_CAP - 20) + "\n…" : text;
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
