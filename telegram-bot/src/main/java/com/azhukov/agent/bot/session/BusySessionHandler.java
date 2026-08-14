package com.azhukov.agent.bot.session;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks busy sessions per chatId and handles concurrent message arrival
 * in either "queue", "interrupt", or "steer" mode
 * (per {@link BotProperties#getBusyInputMode()}).
 *
 * <p>In <b>queue</b> mode, messages arriving while a chat is busy are buffered
 * and can be drained later via {@link #drainQueue(long)}.
 *
 * <p>In <b>interrupt</b> mode, calling {@link #interrupt(long)} cancels the
 * current turn by setting the interrupt flag, and queued messages are still
 * available for draining.
 *
 * <p>In <b>steer</b> mode, messages arriving while a chat is busy are sent to
 * the backend steer API for mid-run injection. The steer text is not queued
 * locally — it's forwarded to the backend immediately.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusySessionHandler {

    private final BotProperties properties;

    /** Chat IDs currently processing a turn. */
    private final ConcurrentHashMap<Long, AtomicBoolean> busyChats = new ConcurrentHashMap<>();

    /** Queued messages per chat, populated in "queue" mode. Thread-safe. */
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<UpdateEvent>> queues = new ConcurrentHashMap<>();

    /** Interrupt flags per chat (used in "interrupt" mode). */
    private final ConcurrentHashMap<Long, AtomicBoolean> interruptFlags = new ConcurrentHashMap<>();

    /** Last busy-ack timestamp per chat (epoch millis) for 30-second debounce. */
    private final ConcurrentHashMap<Long, Long> busyAckTimestamps = new ConcurrentHashMap<>();

    // M4: busyAckHintShown is intentionally a per-install (singleton) flag, not per-chat.
    // This bean is a Spring singleton, so the onboarding hint is shown once across all
    // chats for the lifetime of the process. This matches the design: the hint explains
    // the busy-input-mode feature which is a global setting, not per-chat state.
    private volatile boolean busyAckHintShown = false;

    /** Busy-ack debounce window in milliseconds. */
    private static final long BUSY_ACK_COOLDOWN_MS = 30_000L;

    /** Whether the given chat is currently processing a turn. */
    public boolean isBusy(long chatId) {
        AtomicBoolean flag = busyChats.get(chatId);
        return flag != null && flag.get();
    }

    /** Mark the given chat as busy. */
    public void markBusy(long chatId) {
        busyChats.computeIfAbsent(chatId, k -> new AtomicBoolean()).set(true);
        interruptFlags.computeIfAbsent(chatId, k -> new AtomicBoolean()).set(false);
        log.debug("Marked chat {} as busy", chatId);
    }

    /** Mark the chat as free (not busy). */
    public void markFree(long chatId) {
        AtomicBoolean flag = busyChats.get(chatId);
        if (flag != null) {
            flag.set(false);
        }
        interruptFlags.computeIfAbsent(chatId, k -> new AtomicBoolean()).set(false);
        busyAckTimestamps.remove(chatId);
        log.debug("Marked chat {} as free", chatId);
    }

    /**
     * Queue a message for the given chat (used in "queue" mode when the chat is busy).
     *
     * @param chatId the Telegram chat ID
     * @param event  the update event to queue
     */
    public void queueMessage(long chatId, UpdateEvent event) {
        if (event == null) return;
        queues.computeIfAbsent(chatId, k -> new ConcurrentLinkedQueue<>()).add(event);
        log.debug("Queued message for chat {} (queue size: {})", chatId,
            queues.get(chatId).size());
    }

    /**
     * Drain and return all queued messages for the given chat.
     *
     * @param chatId the Telegram chat ID
     * @return list of queued events (empty if none)
     */
    public List<UpdateEvent> drainQueue(long chatId) {
        List<UpdateEvent> result = new ArrayList<>();
        queues.compute(chatId, (k, q) -> {
            if (q != null) {
                result.addAll(q);
            }
            return null;
        });
        return List.copyOf(result);
    }

    /**
     * Check whether there are queued messages for the given chat.
     *
     * @param chatId the Telegram chat ID
     * @return true if there are queued messages
     */
    public boolean hasQueued(long chatId) {
        ConcurrentLinkedQueue<UpdateEvent> queued = queues.get(chatId);
        return queued != null && !queued.isEmpty();
    }

    /**
     * Interrupt the current turn for the given chat (used in "interrupt" mode).
     * Sets the interrupt flag which can be checked via {@link #isInterrupted(long)}.
     *
     * @param chatId the Telegram chat ID
     */
    public void interrupt(long chatId) {
        interruptFlags.computeIfAbsent(chatId, k -> new AtomicBoolean()).set(true);
        log.debug("Interrupt requested for chat {}", chatId);
    }

    /**
     * Check whether the current turn for the given chat has been interrupted.
     *
     * @param chatId the Telegram chat ID
     * @return true if an interrupt has been requested
     */
    public boolean isInterrupted(long chatId) {
        AtomicBoolean flag = interruptFlags.get(chatId);
        return flag != null && flag.get();
    }

    /**
     * Returns the configured busy mode (legacy: "queue" or "interrupt").
     *
     * @return the busy mode string
     */
    public String getBusyMode() {
        return properties.getBusyMode();
    }

    /**
     * Returns the effective busy-input mode, resolving between
     * {@code busyInputMode} (new) and {@code busyMode} (legacy).
     * If {@code busyInputMode} is set to "steer", returns "steer".
     * Otherwise falls back to {@code busyMode} for backward compatibility.
     *
     * @return "steer", "queue", or "interrupt"
     */
    public String getEffectiveBusyInputMode() {
        String mode = properties.getBusyInputMode();
        if (mode != null && !mode.isBlank()) {
            String lower = mode.toLowerCase().strip();
            if (lower.equals("steer") || lower.equals("queue") || lower.equals("interrupt")) {
                return lower;
            }
        }
        // Fall back to legacy busyMode
        String legacy = properties.getBusyMode();
        if (legacy != null && !legacy.isBlank()) {
            return legacy.toLowerCase().strip();
        }
        return "interrupt";
    }

    /**
     * Check if busy-ack messages are enabled.
     *
     * @return true if busy-ack should be sent
     */
    public boolean isBusyAckEnabled() {
        return properties.isBusyAckEnabled();
    }

    /**
     * Check if a busy-ack should be sent for this chat, respecting the
     * 30-second debounce window. If the ack is allowed, the timestamp
     * is updated atomically.
     *
     * @param chatId the Telegram chat ID
     * @return true if the ack should be sent (within debounce window)
     */
    public boolean shouldSendBusyAck(long chatId) {
        long now = System.currentTimeMillis();
        boolean[] shouldSend = {true};
        busyAckTimestamps.compute(chatId, (k, lastAck) -> {
            if (lastAck != null && (now - lastAck) < BUSY_ACK_COOLDOWN_MS) {
                shouldSend[0] = false;
                return lastAck;
            }
            return now;
        });
        return shouldSend[0];
    }

    /**
     * Check if the onboarding hint has been shown and mark it as shown.
     *
     * @return true if this is the first time (hint should be shown)
     */
    public boolean shouldShowOnboardingHint() {
        if (!busyAckHintShown) {
            busyAckHintShown = true;
            return true;
        }
        return false;
    }

    /**
     * Clear the busy-ack timestamp for a chat (e.g. when the chat becomes free).
     *
     * @param chatId the Telegram chat ID
     */
    public void clearBusyAckTimestamp(long chatId) {
        busyAckTimestamps.remove(chatId);
    }
}