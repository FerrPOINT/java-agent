package com.azhukov.agent.gateway.telegram;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * L37 test: verify that TelegramLongPollingService does not log the first 8 chars
 * of the bot token. Instead, it should log only the last 4 chars (suffix).
 */
class TelegramLongPollingServiceTokenLogTest {

    @Test
    void startLogsOnlyLastFourCharsOfToken() throws Exception {
        assertStartLog("1234567890:ABCdefGHIjklMNO", "lMNO");
    }

    @Test
    void startWithShortTokenDoesNotThrow() throws Exception {
        assertStartLog("ABCD", "ABCD");
    }

    private void assertStartLog(String token, String expectedSuffix) throws Exception {
        var props = new AgentProperties();
        props.getGateway().getTelegram().setBotToken(token);
        HttpClient transport = mock(HttpClient.class);
        // The worker exits immediately if scheduled. It cannot perform DNS/HTTP
        // or enter a retry delay, regardless of the race between start and stop.
        when(transport.<String>send(any(), any()))
            .thenThrow(new InterruptedException("isolated unit transport"));
        GatewayRoutingService routing = new GatewayRoutingService(List.of(), evt -> {});
        var service = new TelegramLongPollingService(props, routing, new ObjectMapper(), transport);
        Logger logger = (Logger) LoggerFactory.getLogger(TelegramLongPollingService.class);
        Level previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            service.start();
            assertThat(service.isRunning()).isTrue();
            List<String> messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Telegram long polling started"))
                .toList();
            assertThat(messages).containsExactly(
                "Telegram long polling started with token suffix=..." + expectedSuffix
                    + " and timeout=" + props.getGateway().getTelegram().getTimeoutSeconds() + "s");
            if (token.length() > 4) {
                assertThat(messages.getFirst()).doesNotContain(token, token.substring(0, 8));
            }
        } finally {
            service.stop();
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }
}
