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


    public void startTyping(long chatId) {
        // Atomic: putIfAbsent prevents duplicate tasks from concurrent calls.
        // Send typing immediately — the first sendTyping gives instant feedback
        // even if another thread wins the putIfAbsent race (the extra send is harmless).
        telegramClient.sendTyping(chatId);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                telegramClient.sendTyping(chatId);
            } catch (Exception e) {
                log.debug("Typing refresh failed for chat {}: {}", chatId, e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> existing = activeTyping.putIfAbsent(chatId, future);
        if (existing != null) {
            // Another thread already started typing — cancel the duplicate
            future.cancel(false);
        }
        log.debug("Started typing for chat {}", chatId);
    }

    public void stopTyping(long chatId) {
        ScheduledFuture<?> future = activeTyping.remove(chatId);
        if (future != null) {
            future.cancel(false);
            log.debug("Stopped typing for chat {}", chatId);
        }
    }

    /**
     * Send one final typing action immediately before stopping. Keeps the indicator
     * alive up until the moment the final message is about to be delivered.
     */
    public void flushTyping(long chatId) {
        if (!activeTyping.containsKey(chatId)) return;
        try {
            telegramClient.sendTyping(chatId);
        } catch (Exception e) {
            log.debug("Final typing refresh failed for chat {}: {}", chatId, e.getMessage());
        }
    }

    public boolean isTyping(long chatId) {
        return activeTyping.containsKey(chatId);
    }

    public void stopAll() {
        activeTyping.values().forEach(f -> f.cancel(false));
        activeTyping.clear();
    }

    @PreDestroy
    public void destroy() {
        stopAll();
        scheduler.shutdownNow();
        log.info("TypingManager shutdown complete");
    }
}