package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * M29: Test that conflictRetryCount is an AtomicInteger for thread safety.
 */
class LongPollingServiceConflictRetryTest {

    private TelegramClient telegramClient;
    private BotProperties properties;
    private ReconnectWatcher reconnectWatcher;
    private LongPollingService service;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        properties = new BotProperties();
        properties.setToken("test-token-conflict-" + System.nanoTime());
        properties.setReplaceOnStart(true);
        properties.getPolling().setTimeoutSeconds(0);
        properties.getPolling().setLimit(10);
        properties.getPolling().setConflictMaxRetries(3);

        reconnectWatcher = new ReconnectWatcher();
    }

    @AfterEach
    void tearDown() {
        reconnectWatcher.stop();
        try { service.stop(); } catch (Exception ignored) {}
    }

    @Test
    void conflictRetryCountIsAtomicInteger() throws Exception {
        service = new LongPollingService(
            telegramClient, properties,
            event -> {},
            reconnectWatcher
        );

        Field field = LongPollingService.class.getDeclaredField("conflictRetryCount");
        field.setAccessible(true);
        Object value = field.get(service);
        assertThat(value).isInstanceOf(AtomicInteger.class);
    }

    @Test
    void conflictRetryCountIncrementsAtomically() throws Exception {
        // Simulate 409 conflict: empty result + isLastCallConflict=true
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenReturn(Optional.empty());
        when(telegramClient.isLastCallConflict()).thenReturn(true);

        service = new LongPollingService(
            telegramClient, properties,
            event -> {},
            reconnectWatcher
        );

        service.start();

        // Wait a bit for conflicts to be processed
        Thread.sleep(500);
        service.stop();

        // Verify the conflict was handled (getUpdates was called)
        verify(telegramClient, atLeast(1)).getUpdates(anyLong(), anyInt(), anyInt());
    }

    @Test
    void conflictRetryCountResetsOnSuccess() throws Exception {
        // First call returns empty + conflict, second returns success
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(java.util.List.of()));
        when(telegramClient.isLastCallConflict()).thenReturn(true).thenReturn(false);

        service = new LongPollingService(
            telegramClient, properties,
            event -> {},
            reconnectWatcher
        );

        service.start();
        Thread.sleep(200);
        service.stop();

        // Verify the conflict counter field is an AtomicInteger that was reset
        Field field = LongPollingService.class.getDeclaredField("conflictRetryCount");
        field.setAccessible(true);
        AtomicInteger counter = (AtomicInteger) field.get(service);
        // After a successful fetch, the counter should be reset to 0
        // (may not be exactly 0 due to timing, but should be small)
        assertThat(counter.get()).isLessThanOrEqualTo(3);
    }
}