package com.azhukov.agent.bot.batch;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 * Buffers rapid text messages per chat, merging them with newline separators,
 * and dispatches after a quiet period.
 * <p>
 * Adaptive delay: 180ms for short messages (≤320 chars), 500ms for medium (≤1024),
 * 1200ms for long (near 4096 split).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TextBatchDebouncer {

    private final BotProperties properties;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "text-batch-debouncer");
        t.setDaemon(true);
        return t;
    });
    private record BatchKey(long chatId, long userId, long threadId) {}
    private final Map<BatchKey, BatchEntry> buffers = new ConcurrentHashMap<>();
    private final List<java.util.function.Consumer<UpdateEvent>> dispatchers = new CopyOnWriteArrayList<>();

    /**
     * Optional predicate checked after the debounce timer fires, before dispatching.
     * If set and returns {@code false} for the given chatId, the batched event is
     * silently dropped (not dispatched). Allows checking if the chat is still
     * active/paused before sending.
     */
    private volatile Predicate<Long> shouldDispatch;

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
        var key = new BatchKey(event.chatId(), event.userId(), event.messageThreadId());

        buffers.compute(key, (ignored, existing) -> {
            BatchEntry entry = existing == null ? new BatchEntry() : existing;
            entry.add(event);
            if (entry.timer != null) entry.timer.cancel(false);
            Object ticket = entry.ticket = new Object();
            entry.timer = scheduler.schedule(() -> dispatch(key, ticket),
                computeDelay(entry.totalLength()), TimeUnit.MILLISECONDS);
            return entry;
        });
        return true;
    }

    /**
     * Register a dispatcher consumer that receives merged events when the quiet period ends.
     */
    public void onDispatch(java.util.function.Consumer<UpdateEvent> dispatcher) {
        dispatchers.add(dispatcher);
    }

    /**
     * Set a predicate that is checked after the debounce timer fires, before dispatching.
     * If the predicate returns {@code false} for a chatId, the batched event is dropped.
     * Pass {@code null} to disable re-validation (dispatch always).
     *
     * @param predicate chatId → should-dispatch check, or null to clear
     */
    public void setShouldDispatch(Predicate<Long> predicate) {
        this.shouldDispatch = predicate;
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

    private void dispatch(BatchKey key, Object ticket) {
        var claimed = new java.util.concurrent.atomic.AtomicReference<BatchEntry>();
        buffers.computeIfPresent(key, (ignored, entry) -> {
            if (entry.ticket != ticket) return entry;
            claimed.set(entry);
            return null;
        });
        deliver(key, claimed.get());
    }

    private void deliver(BatchKey key, BatchEntry entry) {
        if (entry == null) return;
        long chatId = key.chatId();

        // P34: Post-debounce re-validation — check if chat is still active/paused
        Predicate<Long> guard = shouldDispatch;
        if (guard != null && !guard.test(chatId)) {
            log.debug("Dropping batched text for chat {} — shouldDispatch returned false", chatId);
            return;
        }

        String mergedText = entry.mergedText();
        if (mergedText == null || mergedText.isBlank()) return;

        UpdateEvent first = entry.firstEvent();
        if (first == null) return;

        UpdateEvent merged = new UpdateEvent(
            first.updateId(), first.type(), first.chatId(), first.userId(),
            first.username(), first.firstName(), first.languageCode(),
            mergedText, first.caption(), first.fileId(),
            first.fileType(), first.callbackQueryId(), first.callbackData(),
            first.replyToText(), first.isCommand(), first.commandName(),
            first.commandArgs(), first.messageId(), first.mediaGroupId(),
            first.messageThreadId(), first.forwardedFrom()
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
        for (BatchKey key : new java.util.ArrayList<>(buffers.keySet())) {
            var claimed = new java.util.concurrent.atomic.AtomicReference<BatchEntry>();
            buffers.computeIfPresent(key, (ignored, entry) -> {
                if (entry.timer != null) entry.timer.cancel(false);
                claimed.set(entry);
                return null;
            });
            deliver(key, claimed.get());
        }
    }

    /** Test helper: check if a chat has a pending buffer. */
    boolean hasPending(long chatId) {
        return buffers.keySet().stream().anyMatch(key -> key.chatId() == chatId);
    }

    /** Test helper: get pending text for a chat. */
    String getPendingText(long chatId) {
        return buffers.entrySet().stream().filter(entry -> entry.getKey().chatId() == chatId)
            .map(entry -> entry.getValue().mergedText()).findFirst().orElse(null);
    }

    // ─── Internal ──────────────────────────────────────────────────

    /** Keeps pending text updates and their flush timer together for a single chat. */
    private static class BatchEntry {
        private final List<UpdateEvent> events = new java.util.ArrayList<>();
        private ScheduledFuture<?> timer;
        private Object ticket;

        void add(UpdateEvent event) {
            events.add(event);
        }

        UpdateEvent firstEvent() {
            return events.stream().min(java.util.Comparator.comparingLong(UpdateEvent::updateId)).orElse(null);
        }

        int totalLength() {
            return events.stream().map(UpdateEvent::text).mapToInt(String::length).sum();
        }

        String mergedText() {
            // Adaptive join: space for short messages, newline for long
            boolean allShort = events.stream().map(UpdateEvent::text).allMatch(t -> t.length() <= 320);
            String separator = allShort ? " " : "\n";
            return events.stream().sorted(java.util.Comparator.comparingLong(UpdateEvent::updateId))
                .map(UpdateEvent::text).collect(java.util.stream.Collectors.joining(separator));
        }
    }
}