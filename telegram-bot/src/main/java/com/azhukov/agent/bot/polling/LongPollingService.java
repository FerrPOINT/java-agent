package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.lock.BotLockManager;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "bot.mode", havingValue = "polling")
@Slf4j
@RequiredArgsConstructor
public class LongPollingService {

    private final TelegramClient telegramClient;
    private final BotProperties properties;
    private final Consumer<UpdateEvent> updateHandler;
    private final ReconnectWatcher reconnectWatcher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telegram-polling");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService processPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "telegram-update-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastUpdateId = new AtomicLong(0);

    // P0: Gateway lock — prevents concurrent instances with the same bot token
    private BotLockManager lockManager;

    // B3: 409 conflict tracking
    private int conflictRetryCount = 0;
    private static final long[] CONFLICT_BACKOFF_MS = {15_000, 30_000, 55_000, 55_000, 55_000};

    public boolean isRunning() {
        return running.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            log.warn("Long-polling not started: bot token is empty");
            return;
        }
        // P0: Acquire gateway lock to prevent token collision
        lockManager = new BotLockManager(properties.getToken(), properties.isReplaceOnStart());
        try {
            lockManager.acquire();
        } catch (BotLockManager.LockAcquisitionException e) {
            log.error("Gateway lock acquisition failed: {}", e.getMessage());
            return;
        } catch (Exception e) {
            log.warn("Gateway lock acquisition error (proceeding without lock): {}", e.getMessage());
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
        processPool.shutdown();
        try {
            if (!processPool.awaitTermination(5, TimeUnit.SECONDS)) {
                processPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            processPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // P0: Release gateway lock
        if (lockManager != null) {
            lockManager.release();
            lockManager = null;
        }
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
                        processPool.submit(() -> {
                            try {
                                updateHandler.accept(event);
                            } catch (Exception e) {
                                log.error("Error processing update: {}", e.getMessage(), e);
                            }
                        });
                    } catch (Exception e) {
                        log.error("Error parsing update: {}", e.getMessage(), e);
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
                // Check if this was a 409 conflict
                if (telegramClient.isLastCallConflict()) {
                    return handleConflict();
                }
                log.warn("getUpdates returned empty — possible network error");
                return null;
            }
            // Successful fetch — reset conflict counter
            conflictRetryCount = 0;
            return result.get();
        } catch (Exception e) {
            if (e instanceof InterruptedException ie) throw ie;
            log.warn("getUpdates failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * B3: Handle HTTP 409 conflict (another polling instance is active).
     * Applies exponential backoff: 15s, 30s, 55s, 55s, 55s (max configured retries).
     * After max retries: logs error and stops polling (does not crash).
     *
     * @return null to signal the poll loop to exit (reconnect or stop)
     * @throws InterruptedException if the backoff sleep is interrupted
     */
    private List<Map<String, Object>> handleConflict() throws InterruptedException {
        int maxRetries = properties.getPolling().getConflictMaxRetries();
        conflictRetryCount++;
        log.warn("Another polling instance detected (HTTP 409), backing off. Attempt {}/{}",
            conflictRetryCount, maxRetries);

        if (conflictRetryCount > maxRetries) {
            log.error("Max conflict retries ({}) exceeded for HTTP 409. Stopping polling to avoid infinite loop.",
                maxRetries);
            running.set(false);
            return null;
        }

        // Exponential backoff: 15s, 30s, 55s, 55s, 55s...
        int backoffIndex = Math.min(conflictRetryCount - 1, CONFLICT_BACKOFF_MS.length - 1);
        long backoffMs = CONFLICT_BACKOFF_MS[backoffIndex];
        log.info("Backing off for {}ms before retrying getUpdates", backoffMs);

        Thread.sleep(backoffMs);
        return null; // signal poll loop to return (will be re-launched by reconnectWatcher)
    }

    void triggerReconnect() {
        if (!running.get()) return;
        log.info("Triggering reconnect for long-polling...");
        reconnectWatcher.scheduleReconnect(this::pollLoop);
    }
}