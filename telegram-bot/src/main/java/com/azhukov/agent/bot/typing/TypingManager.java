package com.azhukov.agent.bot.typing;

import com.azhukov.agent.bot.client.TelegramClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
@Slf4j
public class TypingManager {

    private final TelegramClient telegramClient;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler;
    private final Map<Long, ScheduledFuture<?>> activeTyping = new ConcurrentHashMap<>();

    public TypingManager(TelegramClient telegramClient, com.azhukov.agent.bot.config.BotProperties properties) {
        this.telegramClient = telegramClient;
        this.intervalMs = properties.getTypingRefreshInterval().toMillis();
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "typing-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    public void startTyping(long chatId) {
        if (activeTyping.containsKey(chatId)) return;
        telegramClient.sendTyping(chatId);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                telegramClient.sendTyping(chatId);
            } catch (Exception e) {
                log.debug("Typing refresh failed for chat {}: {}", chatId, e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        activeTyping.put(chatId, future);
        log.debug("Started typing for chat {}", chatId);
    }

    public void stopTyping(long chatId) {
        ScheduledFuture<?> future = activeTyping.remove(chatId);
        if (future != null) {
            future.cancel(false);
            log.debug("Stopped typing for chat {}", chatId);
        }
    }

    public boolean isTyping(long chatId) {
        return activeTyping.containsKey(chatId);
    }

    public void stopAll() {
        activeTyping.values().forEach(f -> f.cancel(false));
        activeTyping.clear();
    }
}
