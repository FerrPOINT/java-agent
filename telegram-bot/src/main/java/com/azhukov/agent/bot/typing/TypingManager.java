package com.azhukov.agent.bot.typing;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

@Service
@Slf4j
@RequiredArgsConstructor
public class TypingManager {

    private final TelegramClient telegramClient;
    private final BotProperties properties;
    private long intervalMs;
    private ScheduledExecutorService scheduler;

    @PostConstruct
    void init() {
        intervalMs = properties.getTypingRefreshInterval().toMillis();
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "typing-refresh");
            t.setDaemon(true);
            return t;
        });
    }
    private final Map<Long, ScheduledFuture<?>> activeTyping = new ConcurrentHashMap<>();
    // Per-chat forum thread id for routing typing indicators to the correct topic
    private final Map<Long, Integer> chatThreadIds = new ConcurrentHashMap<>();

    /**
     * Start sending periodic typing indicators to a chat.
     * When {@code messageThreadId} is non-null, typing indicators are routed
     * to the correct forum topic thread (matching Hermes behavior).
     *
     * @param chatId target chat id
     * @param messageThreadId optional forum thread id (null = no thread routing)
     */
    public void startTyping(long chatId, Integer messageThreadId) {
        // Store the thread id for this chat's typing session
        if (messageThreadId != null) {
            chatThreadIds.put(chatId, messageThreadId);
        } else {
            chatThreadIds.remove(chatId);
        }
        // Atomic: putIfAbsent prevents duplicate tasks from concurrent calls.
        // Send typing immediately — the first sendTyping gives instant feedback
        // even if another thread wins the putIfAbsent race (the extra send is harmless).
        Integer threadId = chatThreadIds.get(chatId);
        telegramClient.sendTyping(chatId, threadId);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                Integer tid = chatThreadIds.get(chatId);
                telegramClient.sendTyping(chatId, tid);
            } catch (Exception e) {
                log.debug("Typing refresh failed for chat {}: {}", chatId, e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> existing = activeTyping.putIfAbsent(chatId, future);
        if (existing != null) {
            // Another thread already started typing — cancel the duplicate
            future.cancel(false);
        }
        log.debug("Started typing for chat {} (threadId={})", chatId, threadId);
    }

    /**
     * Start sending periodic typing indicators to a chat without thread routing.
     * Equivalent to {@link #startTyping(long, Integer)} with null thread id.
     *
     * @param chatId target chat id
     */
    public void startTyping(long chatId) {
        startTyping(chatId, null);
    }

    public void stopTyping(long chatId) {
        ScheduledFuture<?> future = activeTyping.remove(chatId);
        if (future != null) {
            future.cancel(false);
            log.debug("Stopped typing for chat {}", chatId);
        }
        chatThreadIds.remove(chatId);
        pausedChats.remove(chatId);
    }

    // P1-9: Approval gate pause/resume
    private final Map<Long, Boolean> pausedChats = new ConcurrentHashMap<>();
    private final ReentrantLock pauseLock = new ReentrantLock();

    /**
     * Pause typing indicator for a chat (e.g. when an approval gate is active).
     * The typing refresh task is cancelled but the chat remains in the
     * active set so that {@link #resumeTyping(long)} can restart it.
     *
     * @param chatId target chat id
     */
    public void pauseTyping(long chatId) {
        pauseLock.lock();
        try {
            ScheduledFuture<?> future = activeTyping.get(chatId);
            if (future != null) {
                future.cancel(false);
                // Re-register a no-op future so putIfAbsent in resumeTyping works correctly
                pausedChats.put(chatId, true);
                log.debug("Paused typing for chat {}", chatId);
            }
        } finally {
            pauseLock.unlock();
        }
    }

    /**
     * Resume typing indicator for a chat after it was paused (e.g. after
     * an approval gate is resolved). If typing was not active or not paused,
     * this is a no-op.
     *
     * @param chatId target chat id
     */
    public void resumeTyping(long chatId) {
        // H7: Prepare data inside the lock, make HTTP call outside to avoid
        // virtual thread pinning during the Telegram API network call.
        boolean wasPaused;
        ScheduledFuture<?> oldFuture;
        Integer threadId;
        pauseLock.lock();
        try {
            if (pausedChats.remove(chatId) == null) {
                // Was not paused — nothing to do
                return;
            }
            // Cancel the old (cancelled) future
            oldFuture = activeTyping.remove(chatId);
            threadId = chatThreadIds.get(chatId);
            wasPaused = true;
        } finally {
            pauseLock.unlock();
        }
        if (!wasPaused) {
            return;
        }
        if (oldFuture != null) {
            oldFuture.cancel(false);
        }
        // Start a fresh periodic typing task — HTTP call outside the lock
        telegramClient.sendTyping(chatId, threadId);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                Integer tid = chatThreadIds.get(chatId);
                telegramClient.sendTyping(chatId, tid);
            } catch (Exception e) {
                log.debug("Typing refresh failed for chat {}: {}", chatId, e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        // M9: Use putIfAbsent to avoid overwriting an active typing task that may have
        // been started by another thread between the remove and the put. Cancel the new
        // future if an existing one was registered.
        ScheduledFuture<?> existing = activeTyping.putIfAbsent(chatId, future);
        if (existing != null) {
            future.cancel(false);
        }
        log.debug("Resumed typing for chat {} (threadId={})", chatId, threadId);
    }

    /**
     * Send one final typing action immediately before stopping. Keeps the indicator
     * alive up until the moment the final message is about to be delivered.
     */
    public void flushTyping(long chatId) {
        if (!activeTyping.containsKey(chatId)) return;
        if (pausedChats.containsKey(chatId)) return; // Don't flush if paused
        try {
            Integer threadId = chatThreadIds.get(chatId);
            telegramClient.sendTyping(chatId, threadId);
        } catch (Exception e) {
            log.debug("Final typing refresh failed for chat {}: {}", chatId, e.getMessage());
        }
    }

    public boolean isTyping(long chatId) {
        return activeTyping.containsKey(chatId) && !pausedChats.containsKey(chatId);
    }

    /**
     * Returns true if typing is currently paused for the given chat
     * (e.g. waiting for an approval gate to be resolved).
     *
     * @param chatId target chat id
     * @return true if typing is paused
     */
    public boolean isPaused(long chatId) {
        return pausedChats.containsKey(chatId);
    }

    public void stopAll() {
        activeTyping.values().forEach(f -> f.cancel(false));
        activeTyping.clear();
        chatThreadIds.clear();
        pausedChats.clear();
    }

    @PreDestroy
    public void destroy() {
        stopAll();
        scheduler.shutdownNow();
        log.info("TypingManager shutdown complete");
    }
}