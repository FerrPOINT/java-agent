package com.azhukov.agent.bot.polling;

import com.azhukov.agent.bot.client.TelegramApiException;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * M23/M24 regression: terminal auth errors stop polling instead of infinite
 * reconnect; 409 is surfaced as a typed exception, not a global flag.
 */
@ExtendWith(MockitoExtension.class)
class LongPollingServiceAuthErrorsTest {

    @Mock
    TelegramClient telegramClient;
    @Mock
    BotProperties properties;
    @Mock
    BotProperties.Polling polling;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong lastUpdateId = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        when(properties.getPolling()).thenReturn(polling);
        when(polling.getTimeoutSeconds()).thenReturn(0);
        when(polling.getLimit()).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        running.set(false);
    }

    private LongPollingService service() throws Exception {
        LongPollingService svc = new LongPollingService(telegramClient, properties, null, null);
        java.lang.reflect.Field f = LongPollingService.class.getDeclaredField("running");
        f.setAccessible(true);
        f.set(svc, running);
        java.lang.reflect.Field u = LongPollingService.class.getDeclaredField("lastUpdateId");
        u.setAccessible(true);
        u.set(svc, lastUpdateId);
        return svc;
    }

    @Test
    void unauthorized401StopsPolling() throws Exception {
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenThrow(new TelegramApiException(401, "Unauthorized", -1));
        LongPollingService svc = service();
        var result = svc.fetchUpdates();
        assertThat(result).isNull();
        assertThat(running.get()).as("401 must stop the poll loop").isFalse();
    }

    @Test
    void notFound404StopsPolling() throws Exception {
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenThrow(new TelegramApiException(404, "Not Found", -1));
        LongPollingService svc = service();
        var result = svc.fetchUpdates();
        assertThat(result).isNull();
        assertThat(running.get()).as("404 must stop the poll loop").isFalse();
    }

    @Test
    void conflict409GoesToConflictHandlerNotTerminal() throws Exception {
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenThrow(new TelegramApiException(409, "Conflict: terminated by other getUpdates request", -1));
        when(polling.getConflictMaxRetries()).thenReturn(2);
        LongPollingService svc = service();
        var result = svc.fetchUpdates();
        assertThat(result).isNotNull().isEmpty();
        assertThat(running.get()).as("409 must back off, not stop").isTrue();
    }

    @Test
    void transient500KeepsPollLoopAlive() throws Exception {
        when(telegramClient.getUpdates(anyLong(), anyInt(), anyInt()))
            .thenThrow(new TelegramApiException(500, "Internal Server Error", -1));
        LongPollingService svc = service();
        var result = svc.fetchUpdates();
        assertThat(result).isNull();
        assertThat(running.get()).as("500 is transient — keep polling").isTrue();
    }
}
