package com.azhukov.agent.bot.batch;

import com.azhukov.agent.bot.polling.UpdateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * Buffers photos with the same media_group_id, merging them into a single event.
 * Debounces 500ms after the last photo in a group.
 */
@Component
@Slf4j
public class PhotoBatchDebouncer {

    private static final long DEBOUNCE_MS = 500;

    private final ScheduledExecutorService scheduler;
    private final Map<String, PhotoGroup> groups = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final List<java.util.function.Consumer<UpdateEvent>> dispatchers = new CopyOnWriteArrayList<>();

    public PhotoBatchDebouncer() {
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "photo-batch-debouncer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Offer a photo event to the debouncer. If the event has a mediaGroupId,
     * it's buffered with other photos in the same group and dispatched after
     * a 500ms quiet period.
     *
     * @return true if the event was buffered (caller should NOT process it yet),
     *         false if the event should be processed immediately (no mediaGroupId)
     */
    public boolean offer(UpdateEvent event) {
        String mediaGroupId = event.mediaGroupId();
        if (mediaGroupId == null || mediaGroupId.isBlank()) {
            return false; // Single photo, process immediately
        }

        PhotoGroup group = groups.computeIfAbsent(mediaGroupId, k -> new PhotoGroup());
        synchronized (group) {
            group.add(event);
        }

        // Reset debounce timer
        ScheduledFuture<?> existing = timers.remove(mediaGroupId);
        if (existing != null) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = scheduler.schedule(
            () -> dispatch(mediaGroupId), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        timers.put(mediaGroupId, future);

        return true;
    }

    /**
     * Register a dispatcher consumer that receives merged photo events.
     */
    public void onDispatch(java.util.function.Consumer<UpdateEvent> dispatcher) {
        dispatchers.add(dispatcher);
    }

    private void dispatch(String mediaGroupId) {
        PhotoGroup group = groups.remove(mediaGroupId);
        timers.remove(mediaGroupId);
        if (group == null || group.events.isEmpty()) return;

        UpdateEvent first = group.events.getFirst();
        // Collect all file IDs from the group
        List<String> fileIds = group.events.stream()
            .map(UpdateEvent::fileId)
            .filter(Objects::nonNull)
            .toList();

        // Merge captions
        String mergedCaption = group.events.stream()
            .map(UpdateEvent::caption)
            .filter(c -> c != null && !c.isBlank())
            .reduce((a, b) -> a + "\n" + b)
            .orElse(null);

        // Create merged event with first file_id (or join them)
        String primaryFileId = fileIds.isEmpty() ? first.fileId() : fileIds.getFirst();
        String mergedFileIds = fileIds.size() > 1 ? String.join(",", fileIds) : primaryFileId;

        UpdateEvent merged = new UpdateEvent(
            first.updateId(), first.type(), first.chatId(), first.userId(),
            first.username(), first.text(), mergedCaption, mergedFileIds,
            first.fileType(), first.callbackQueryId(), first.callbackData(),
            first.replyToText(), first.isCommand(), first.commandName(),
            first.commandArgs(), first.messageId(), first.mediaGroupId(),
            first.messageThreadId()
        );

        log.debug("Dispatching photo group {}: {} photos", mediaGroupId, group.events.size());
        for (var d : dispatchers) {
            d.accept(merged);
        }
    }

    /** Flush all pending groups immediately (for shutdown or testing). */
    public void flushAll() {
        for (String groupId : new ArrayList<>(groups.keySet())) {
            ScheduledFuture<?> f = timers.remove(groupId);
            if (f != null) f.cancel(false);
            dispatch(groupId);
        }
    }

    /** Test helper: check if a group has pending photos. */
    boolean hasPending(String mediaGroupId) {
        return groups.containsKey(mediaGroupId);
    }

    /** Test helper: get number of pending photos in a group. */
    int pendingCount(String mediaGroupId) {
        PhotoGroup group = groups.get(mediaGroupId);
        return group != null ? group.events.size() : 0;
    }

    // ─── Internal ──────────────────────────────────────────────────

    private static class PhotoGroup {
        final List<UpdateEvent> events = new CopyOnWriteArrayList<>();

        void add(UpdateEvent event) {
            events.add(event);
        }
    }
}