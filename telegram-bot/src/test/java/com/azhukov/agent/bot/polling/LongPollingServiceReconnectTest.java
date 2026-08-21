package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0 regression: a transient getUpdates failure (TelegramClient swallows the
 * network exception and returns Optional.empty()) used to kill the poll
 * thread silently — pollLoop returned with a false comment "fetchUpdates
 * already triggered reconnect logic" while nothing ever rescheduled it.
 * The bot then stayed "active" but deaf until manual restart.
 *
 * <p>Fix: pollLoop schedules reconnectWatcher.scheduleReconnect(...) before
 * returning. These tests pin that contract.
 */
class LongPollingServiceReconnectTest {

    private TelegramClient telegramClient;
    private BotProperties properties;
    private ReconnectWatcher reconnectWatcher;
    private LongPollingService service;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        properties = new BotProperties();
        properties.setToken("test-token-reconnect-" + System.nanoTime());
        properties.setReplaceOnStart(true);
        properties.getPolling().setTimeoutSeconds(0);
        properties.getPolling().setLimit(10);

        reconnectWatcher = new ReconnectWatcher();
    }

    @AfterEach
    void tearDown() {
        reconnectWatcher.stop();
        try { service.stop(); } catch (Exception ignored) {}
    }

    private static Map<String, Object> update(long id, String text) {
        return Map.of(
            "update_id", id,
            "message", Map.of(
                "message_id", id + 10,
                "date", System.currentTimeMillis() / 1000,
                "chat", Map.of("id", 100L, "type", "private"),
                "from", Map.of("id", 200L, "is_bot", false, "first_name", "Test"),
                "text", text
            )
        );
    }

    @Test
    @Timeout(30)
    void transientNetworkErrorReschedulesPollLoopInsteadOfDying() throws Exception {
        // First poll: transient network error (empty Optional, NOT a conflict)
        // Second poll (after reconnect backoff): a real update arrives
        AtomicInteger fetchCount = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                int n = fetchCount.incrementAndGet();
                if (n == 1) {
                    // Network error shape: TelegramClient.getUpdates catches
                    // the exception and returns Optional.empty()
                    return Optional.empty();
                }
                return Optional.of(List.of(update(1, "hello after reconnect")));
            });
        when(telegramClient.isLastCallConflict()).thenReturn(false);

        service = new LongPollingService(
            telegramClient, properties,
            event -> delivered.countDown(),
            reconnectWatcher
        );

        service.start();

        // ReconnectWatcher initial backoff is 5s — the rescheduled pollLoop
        // must pick up the update without any manual restart.
        assertThat(delivered.await(20, TimeUnit.SECONDS))
            .as("pollLoop must be rescheduled after transient failure and deliver the update")
            .isTrue();
        assertThat(fetchCount.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Timeout(30)
    void repeatedNetworkErrorsKeepRetryingWithBackoff() throws Exception {
        // Every fetch fails transiently; after several failures the poll loop
        // must still be alive and retrying (not dead).
        AtomicInteger fetchCount = new AtomicInteger();
        // Backoff ladder: attempt 1 immediate, 2 at +5s, 3 at +12.5s (5+7.5),
        // 4 at +23.75s. Requiring 3 attempts proves the ladder keeps
        // rescheduling without waiting through the full exponential growth.
        CountDownLatch attempts = new CountDownLatch(3);
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                fetchCount.incrementAndGet();
                attempts.countDown();
                return Optional.empty();
            });
        when(telegramClient.isLastCallConflict()).thenReturn(false);

        service = new LongPollingService(
            telegramClient, properties,
            event -> {},
            reconnectWatcher
        );

        service.start();

        assertThat(attempts.await(20, TimeUnit.SECONDS))
            .as("poll loop must keep retrying after repeated transient failures")
            .isTrue();
    }
}
