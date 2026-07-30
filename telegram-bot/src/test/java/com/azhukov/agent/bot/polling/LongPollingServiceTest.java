package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class LongPollingServiceTest {

    private TelegramClient telegramClient;
    private BotProperties properties;
    private ReconnectWatcher reconnectWatcher;
    private LongPollingService service;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        properties = new BotProperties();
        properties.setToken("test-token");
        properties.getPolling().setTimeoutSeconds(0);
        properties.getPolling().setLimit(10);

        reconnectWatcher = new ReconnectWatcher();
    }

    @AfterEach
    void tearDown() {
        reconnectWatcher.stop();
        // Ensure service is stopped to release any lock file
        try { service.stop(); } catch (Exception ignored) {}
    }

    @Test
    void updateProcessedOnSeparateThread() throws Exception {
        // Prepare a single update
        Map<String, Object> update = Map.of(
            "update_id", 1,
            "message", Map.of(
                "message_id", 10,
                "date", System.currentTimeMillis() / 1000,
                "chat", Map.of("id", 100L, "type", "private"),
                "from", Map.of("id", 200L, "is_bot", false, "first_name", "Test"),
                "text", "hello"
            )
        );

        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenReturn(Optional.of(List.of(update)))
            .thenReturn(Optional.empty());

        String mainThreadName = Thread.currentThread().getName();
        CountDownLatch latch = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> handlerThreadName = new java.util.concurrent.atomic.AtomicReference<>("");

        service = new LongPollingService(
            telegramClient, properties,
            event -> {
                handlerThreadName.set(Thread.currentThread().getName());
                latch.countDown();
            },
            reconnectWatcher
        );

        service.start();
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        service.stop();

        // Handler should NOT run on the main test thread
        assertThat(handlerThreadName.get()).isNotEqualTo(mainThreadName);
        assertThat(handlerThreadName.get()).startsWith("telegram-update-");
    }

    @Test
    void slowProcessingDoesNotBlockNextPoll() throws Exception {
        // Two updates in sequence
        Map<String, Object> update1 = Map.of(
            "update_id", 1,
            "message", Map.of(
                "message_id", 10,
                "date", System.currentTimeMillis() / 1000,
                "chat", Map.of("id", 100L, "type", "private"),
                "from", Map.of("id", 200L, "is_bot", false, "first_name", "Test"),
                "text", "first"
            )
        );
        Map<String, Object> update2 = Map.of(
            "update_id", 2,
            "message", Map.of(
                "message_id", 11,
                "date", System.currentTimeMillis() / 1000,
                "chat", Map.of("id", 100L, "type", "private"),
                "from", Map.of("id", 200L, "is_bot", false, "first_name", "Test"),
                "text", "second"
            )
        );

        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenReturn(Optional.of(List.of(update1)))
            .thenReturn(Optional.of(List.of(update2)))
            .thenReturn(Optional.empty());

        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstCanProceed = new CountDownLatch(1);
        CountDownLatch bothDone = new CountDownLatch(2);

        service = new LongPollingService(
            telegramClient, properties,
            event -> {
                if (count.get() == 0) {
                    firstStarted.countDown();
                    try {
                        firstCanProceed.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                count.incrementAndGet();
                bothDone.countDown();
            },
            reconnectWatcher
        );

        service.start();

        // Wait for first handler to start
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();

        // Release the slow handler
        firstCanProceed.countDown();

        // Both should complete
        assertThat(bothDone.await(10, TimeUnit.SECONDS)).isTrue();
        service.stop();

        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    void poolShutdownOnStop() throws Exception {
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenReturn(Optional.empty());

        service = new LongPollingService(
            telegramClient, properties,
            event -> {},
            reconnectWatcher
        );

        service.start();
        service.stop();

        // Give some time for shutdown
        Thread.sleep(100);
        // Verify service is not running
        assertThat(service.isRunning()).isFalse();
    }
}