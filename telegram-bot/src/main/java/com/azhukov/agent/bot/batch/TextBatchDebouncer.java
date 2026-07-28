package com.azhukov.agent.bot.batch;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Buffers rapid text messages per chat, merging them with newline separators,
 * and dispatches after a quiet period.
 * <p>
 * Adaptive delay: 180ms for short messages (≤320 chars), 500ms for medium (≤1024),
 * 1200ms for long (near 4096 split).
 */
@Component
@Slf4j
public class TextBatchDebouncer {

    private final BotProperties properties;
    private final ScheduledExecutorService scheduler;
    private final Map<Long, BatchEntry> buffers = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final List<java.util.function.Consumer<UpdateEvent>> dispatchers = new CopyOnWriteArrayList<>();

    public TextBatchDebouncer(BotProperties properties) {
        this.properties = properties;
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "text-batch-debouncer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Offer a text event to the debouncer. If this is the first message in a quiet
     * period, it starts a timer. Subsequent messages reset the timer.
     * <p>
     * Returns true if the event was buffered (caller should NOT process it yet),
     * false if the event should be processed immediately (debouncing disabled or
     * event is not TEXT type).
     */
    public boolean offer(UpdateEvent event) {
        if (event.type() != UpdateEvent.Type.TEXT) {
            return false;
        }
        long chatId = event.chatId();

        BatchEntry entry = buffers.computeIfAbsent(chatId, k -> {
            BatchEntry e = new BatchEntry();
            e.setFirstEvent(event);
            return e;
        });
        synchronized (entry) {
            // Ensure firstEvent is set if entry was already created
            if (entry.firstEvent() == null) {
                entry.setFirstEvent(event);
            }
            entry.add(event.text());
            int totalLen = entry.totalLength();
            long delay = computeDelay(totalLen);

            // Cancel existing timer
            ScheduledFuture<?> existing = timers.remove(chatId);
            if (existing != null) {
                existing.cancel(false);
            }

            // Schedule dispatch
            ScheduledFuture<?> future = scheduler.schedule(() -> dispatch(chatId), delay, TimeUnit.MILLISECONDS);
            timers.put(chatId, future);
        }
        return true;
    }

    /**
     * Register a dispatcher consumer that receives merged events when the quiet period ends.
     */
    public void onDispatch(java.util.function.Consumer<UpdateEvent> dispatcher) {
        dispatchers.add(dispatcher);
    }

    /**
     * Compute adaptive delay based on accumulated text length.
     */
    long computeDelay(int totalLength) {
        if (totalLength <= 320) {
            return properties.getTextBatch().getFastDelayMs();
        } else if (totalLength <= 1024) {
            return properties.getTextBatch().getDelayMs();
        } else {
            return properties.getTextBatch().getSplitDelayMs();
        }
    }

    private void dispatch(long chatId) {
        BatchEntry entry = buffers.remove(chatId);
        timers.remove(chatId);
        if (entry == null) return;

        String mergedText = entry.mergedText();
        if (mergedText == null || mergedText.isBlank()) return;

        UpdateEvent first = entry.firstEvent();
        if (first == null) return;

        UpdateEvent merged = new UpdateEvent(
            first.updateId(), first.type(), first.chatId(), first.userId(),
            first.username(), mergedText, first.caption(), first.fileId(),
            first.fileType(), first.callbackQueryId(), first.callbackData(),
            first.replyToText(), first.isCommand(), first.commandName(),
            first.commandArgs(), first.messageId(), first.mediaGroupId()
        );

        log.debug("Dispatching batched text for chat {}: {} chars", chatId, mergedText.length());
        for (var d : dispatchers) {
            d.accept(merged);
        }
    }

    /**
     * Flush all pending buffers immediately (for shutdown or testing).
     */
    public void flushAll() {
        for (Long chatId : new java.util.ArrayList<>(buffers.keySet())) {
            ScheduledFuture<?> f = timers.remove(chatId);
            if (f != null) f.cancel(false);
            dispatch(chatId);
        }
    }

    /** Test helper: check if a chat has a pending buffer. */
    boolean hasPending(long chatId) {
        return buffers.containsKey(chatId);
    }

    /** Test helper: get pending text for a chat. */
    String getPendingText(long chatId) {
        BatchEntry entry = buffers.get(chatId);
        return entry != null ? entry.mergedText() : null;
    }

    // ─── Internal ──────────────────────────────────────────────────

    private static class BatchEntry {
        private final List<String> texts = new CopyOnWriteArrayList<>();
        private UpdateEvent firstEvent;

        void add(String text) {
            if (texts.isEmpty() && firstEvent != null) {
                // Keep first event's metadata, but we'll override text
            }
            texts.add(text);
        }

        void setFirstEvent(UpdateEvent event) {
            this.firstEvent = event;
        }

        UpdateEvent firstEvent() {
            return firstEvent;
        }

        int totalLength() {
            return texts.stream().mapToInt(String::length).sum();
        }

        String mergedText() {
            return String.join("\n", texts);
        }
    }
}