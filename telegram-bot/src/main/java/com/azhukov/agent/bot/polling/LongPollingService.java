package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.BotLockManager;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    // NOTE: the separate single-thread poll executor was dead code — pollLoop
    // has always run on ReconnectWatcher's worker thread (start() submits it
    // there and scheduleReconnect() re-submits to the same thread), so a
    // dedicated executor only created confusion.
    private final ExecutorService processPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "telegram-update-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastUpdateId = new AtomicLong(0);

    // P0: Gateway lock — prevents concurrent instances with the same bot token
    private BotLockManager lockManager;

    // B3: 409 conflict tracking — M29: AtomicInteger for thread safety
    private final AtomicInteger conflictRetryCount = new AtomicInteger(0);
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
                    // P0 fix: fetchUpdates returns null on transient network
                    // errors — TelegramClient swallows the exception and
                    // returns Optional.empty(). The poll thread must NEVER die
                    // silently: reschedule with backoff (parity with the Hermes
                    // gateway reconnect loop). handleConflict() null (max
                    // conflicts exceeded) sets running=false first, so the
                    // guard below skips the reschedule in that case.
                    if (running.get()) {
                        log.warn("Poll loop exiting after fetch failure — scheduling reconnect with backoff");
                        reconnectWatcher.scheduleReconnect(this::pollLoop);
                    }
                    return;
                }
                // Audit M22: reset backoff after successful fetch so that
                // a temporary network error doesn't leave the bot at max delay.
                // M22 fix: reset on ANY successful round-trip (including the
                // normal empty long-poll timeout), not only when updates
                // arrived — otherwise the backoff never decays back to 5s
                // until the next real message.
                reconnectWatcher.resetBackoff();
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
                // Same P0 fix as the fetchUpdates-null path: never let the
                // poll thread die without scheduling its own replacement.
                if (running.get()) {
                    reconnectWatcher.scheduleReconnect(this::pollLoop);
                }
                return;
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
                log.debug("getUpdates returned empty (no new updates)");
                return null;
            }
            // Successful fetch — reset conflict counter
            conflictRetryCount.set(0);
            List<Map<String, Object>> updates = result.get();
            if (!updates.isEmpty()) {
                log.info("Received {} update(s) from Telegram (offset={})", updates.size(), offset);
            }
            return updates;
        } catch (Exception e) {
            if (e instanceof InterruptedException ie) throw ie;
            // Check for 409 conflict via direct cast (no reflection)
            if (e instanceof TelegramApiException tae && tae.getErrorCode() == 409) {
                return handleConflict();
            }
            // M23 fix: 401/404 from Telegram mean the bot token is revoked or
            // the bot was deleted — reconnect-with-backoff can never recover.
            // Stop polling and surface the error instead of retrying forever.
            if (e instanceof TelegramApiException tae
                && (tae.getErrorCode() == 401 || tae.getErrorCode() == 404)) {
                log.error("Telegram API returned {} — bot token is invalid/revoked. Stopping polling "
                    + "(fix the token and restart the service).", tae.getErrorCode());
                running.set(false);
                return null;
            }
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
        int currentCount = conflictRetryCount.incrementAndGet();
        log.warn("Another polling instance detected (HTTP 409), backing off. Attempt {}/{}",
            currentCount, maxRetries);

        if (currentCount > maxRetries) {
            log.error("Max conflict retries ({}) exceeded for HTTP 409. Stopping polling to avoid infinite loop.",
                maxRetries);
            running.set(false);
            return null;
        }

        // Exponential backoff: 15s, 30s, 55s, 55s, 55s...
        int backoffIndex = Math.min(currentCount - 1, CONFLICT_BACKOFF_MS.length - 1);
        long backoffMs = CONFLICT_BACKOFF_MS[backoffIndex];
        log.info("Backing off for {}ms before retrying getUpdates", backoffMs);

        Thread.sleep(backoffMs);
        // Return empty list (not null) to keep poll loop running and retry getUpdates
        return List.of();
    }

    // triggerReconnect() removed with the P0 polling fix: it had zero callers
    // (the comment "fetchUpdates already triggered reconnect logic" was false —
    // nothing ever called this method, so the poll thread died silently on the
    // first transient getUpdates network error). Reconnection now goes through
    // reconnectWatcher.scheduleReconnect directly from pollLoop.
}