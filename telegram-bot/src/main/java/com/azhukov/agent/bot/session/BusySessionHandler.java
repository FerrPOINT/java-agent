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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks busy sessions per chatId and handles concurrent message arrival
 * in either "queue" or "interrupt" mode (per {@link BotProperties#getBusyMode()}).
 *
 * <p>In <b>queue</b> mode, messages arriving while a chat is busy are buffered
 * and can be drained later via {@link #drainQueue(long)}.
 *
 * <p>In <b>interrupt</b> mode, calling {@link #interrupt(long)} cancels the
 * current turn by setting the interrupt flag, and queued messages are still
 * available for draining.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusySessionHandler {

    private final BotProperties properties;

    /** Chat IDs currently processing a turn. */
    private final ConcurrentHashMap<Long, AtomicBoolean> busyChats = new ConcurrentHashMap<>();

    /** Queued messages per chat, populated in "queue" mode. */
    private final ConcurrentHashMap<Long, List<UpdateEvent>> queues = new ConcurrentHashMap<>();

    /** Interrupt flags per chat (used in "interrupt" mode). */
    private final ConcurrentHashMap<Long, AtomicBoolean> interruptFlags = new ConcurrentHashMap<>();

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

    /** Mark the given chat as free (not busy). */
    public void markFree(long chatId) {
        AtomicBoolean flag = busyChats.get(chatId);
        if (flag != null) {
            flag.set(false);
        }
        interruptFlags.computeIfAbsent(chatId, k -> new AtomicBoolean()).set(false);
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
        queues.computeIfAbsent(chatId, k -> new ArrayList<>()).add(event);
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
        List<UpdateEvent> queued = queues.remove(chatId);
        return queued != null ? List.copyOf(queued) : List.of();
    }

    /**
     * Check whether there are queued messages for the given chat.
     *
     * @param chatId the Telegram chat ID
     * @return true if there are queued messages
     */
    public boolean hasQueued(long chatId) {
        List<UpdateEvent> queued = queues.get(chatId);
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
     * Returns the configured busy mode ("queue" or "interrupt").
     *
     * @return the busy mode string
     */
    public String getBusyMode() {
        return properties.getBusyMode();
    }
}