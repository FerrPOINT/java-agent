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

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "photo-batch-debouncer");
        t.setDaemon(true);
        return t;
    });
    private record GroupKey(long chatId, long userId, long threadId, String mediaGroupId) {}
    private final Map<GroupKey, PhotoGroup> groups = new ConcurrentHashMap<>();
    private final List<java.util.function.Consumer<UpdateEvent>> dispatchers = new CopyOnWriteArrayList<>();

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

        var key = new GroupKey(event.chatId(), event.userId(), event.messageThreadId(), mediaGroupId);
        groups.compute(key, (ignored, existing) -> {
            PhotoGroup group = existing == null ? new PhotoGroup() : existing;
            group.events.add(event);
            if (group.timer != null) group.timer.cancel(false);
            Object ticket = group.ticket = new Object();
            group.timer = scheduler.schedule(() -> dispatch(key, ticket), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            return group;
        });

        return true;
    }

    /**
     * Register a dispatcher consumer that receives merged photo events.
     */
    public void onDispatch(java.util.function.Consumer<UpdateEvent> dispatcher) {
        dispatchers.add(dispatcher);
    }

    private void dispatch(GroupKey key, Object ticket) {
        var claimed = new java.util.concurrent.atomic.AtomicReference<PhotoGroup>();
        groups.computeIfPresent(key, (ignored, group) -> {
            if (group.ticket != ticket) return group;
            claimed.set(group);
            return null;
        });
        deliver(key, claimed.get());
    }

    private void deliver(GroupKey key, PhotoGroup group) {
        if (group == null || group.events.isEmpty()) return;

        UpdateEvent first = group.events.getFirst();
        // Collect all file IDs from the group
        List<String> fileIds = group.events.stream()
            .map(UpdateEvent::fileId)
            .filter(Objects::nonNull)
            .toList();

        // Merge captions like Hermes: exact duplicate captions are collapsed,
        // while substring matches remain distinct user input.
        String mergedCaption = group.events.stream()
            .map(UpdateEvent::caption)
            .filter(Objects::nonNull)
            .map(String::strip)
            .filter(caption -> !caption.isBlank())
            .distinct()
            .collect(java.util.stream.Collectors.joining("\n\n"));
        if (mergedCaption.isEmpty()) mergedCaption = null;

        // Create merged event with first file_id (or join them)
        String primaryFileId = fileIds.isEmpty() ? first.fileId() : fileIds.getFirst();
        String mergedFileIds = fileIds.size() > 1 ? String.join(",", fileIds) : primaryFileId;

        UpdateEvent merged = new UpdateEvent(
            first.updateId(), first.type(), first.chatId(), first.userId(),
            first.username(), first.firstName(), first.languageCode(),
            first.text(), mergedCaption, mergedFileIds,
            first.fileType(), first.callbackQueryId(), first.callbackData(),
            first.replyToText(), first.isCommand(), first.commandName(),
            first.commandArgs(), first.messageId(), first.mediaGroupId(),
            first.messageThreadId(), first.forwardedFrom()
        );

        log.debug("Dispatching photo group {}: {} photos", key.mediaGroupId(), group.events.size());
        for (var d : dispatchers) {
            d.accept(merged);
        }
    }

    /** Flush all pending groups immediately (for shutdown or testing). */
    public void flushAll() {
        for (GroupKey key : new ArrayList<>(groups.keySet())) {
            var claimed = new java.util.concurrent.atomic.AtomicReference<PhotoGroup>();
            groups.computeIfPresent(key, (ignored, group) -> {
                if (group.timer != null) group.timer.cancel(false);
                claimed.set(group);
                return null;
            });
            deliver(key, claimed.get());
        }
    }

    /** Test helper: check if a group has pending photos. */
    boolean hasPending(String mediaGroupId) {
        return groups.keySet().stream().anyMatch(key -> key.mediaGroupId().equals(mediaGroupId));
    }

    /** Test helper: get number of pending photos in a group. */
    int pendingCount(String mediaGroupId) {
        return groups.entrySet().stream().filter(entry -> entry.getKey().mediaGroupId().equals(mediaGroupId))
            .mapToInt(entry -> entry.getValue().events.size()).sum();
    }

    // ─── Internal ──────────────────────────────────────────────────

    /** Tracks buffered album updates and the scheduled flush for one Telegram media group. */
    private static class PhotoGroup {
        final List<UpdateEvent> events = new CopyOnWriteArrayList<>();
        ScheduledFuture<?> timer;
        Object ticket;
    }
}
