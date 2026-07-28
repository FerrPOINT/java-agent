package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "bot.mode", havingValue = "polling")
@Slf4j
public class LongPollingService {

    private final TelegramClient telegramClient;
    private final BotProperties properties;
    private final Consumer<UpdateEvent> updateHandler;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastUpdateId = new AtomicLong(0);
    private final ReconnectWatcher reconnectWatcher;

    public LongPollingService(TelegramClient telegramClient,
                              BotProperties properties,
                              Consumer<UpdateEvent> updateHandler,
                              ReconnectWatcher reconnectWatcher) {
        this.telegramClient = telegramClient;
        this.properties = properties;
        this.updateHandler = updateHandler;
        this.reconnectWatcher = reconnectWatcher;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "telegram-polling");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isRunning() {
        return running.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            log.warn("Long-polling not started: bot token is empty");
            return;
        }
        // Delete stale webhook to avoid silent update loss
        telegramClient.deleteWebhook();
        running.set(true);
        reconnectWatcher.start(this::pollLoop);
        log.info("Telegram long-polling started (timeout={}s, limit={})",
            properties.getPolling().getTimeoutSeconds(),
            properties.getPolling().getLimit());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        reconnectWatcher.stop();
        executor.shutdownNow();
        log.info("Telegram long-polling stopped");
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                var updates = fetchUpdates();
                if (updates == null) {
                    // fetchUpdates already triggered reconnect logic
                    return;
                }
                for (Map<String, Object> update : updates) {
                    try {
                        UpdateEvent event = UpdateEvent.from(update);
                        lastUpdateId.updateAndGet(current -> Math.max(current, event.updateId()));
                        updateHandler.accept(event);
                    } catch (Exception e) {
                        log.error("Error processing update: {}", e.getMessage(), e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Polling loop error: {}", e.getMessage());
                return; // reconnectWatcher will re-launch
            }
        }
    }

    List<Map<String, Object>> fetchUpdates() throws InterruptedException {
        long offset = lastUpdateId.get() + 1;
        int timeout = properties.getPolling().getTimeoutSeconds();
        int limit = properties.getPolling().getLimit();

        try {
            var result = telegramClient.getUpdates(offset, limit, timeout);
            if (result.isEmpty()) {
                log.warn("getUpdates returned empty — possible network error");
                return null;
            }
            return result.get();
        } catch (Exception e) {
            if (e instanceof InterruptedException ie) throw ie;
            log.warn("getUpdates failed: {}", e.getMessage());
            return null;
        }
    }

    void triggerReconnect() {
        if (!running.get()) return;
        log.info("Triggering reconnect for long-polling...");
        reconnectWatcher.scheduleReconnect(this::pollLoop);
    }
}