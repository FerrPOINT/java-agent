package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramAdapterTest {

    @Test
    void platformIsTelegram() {
        TelegramAdapter adapter = new TelegramAdapter(new AgentProperties(), new TelegramBotApiClient("token", RestClient.create()));
        assertThat(adapter.platform()).isEqualTo(Platform.TELEGRAM);
    }

    @Test
    void sendReturnsFailureWhenNotConnected() throws ExecutionException, InterruptedException {
        AgentProperties properties = new AgentProperties();
        TelegramBotApiClient client = new TelegramBotApiClient("token", RestClient.create());
        TelegramAdapter adapter = new TelegramAdapter(properties, client);
        SendResult result = adapter.send(source("12345"), "hello").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Not connected");
    }

    @Test
    void sendReturnsFailureWhenChatIdMissing() throws ExecutionException, InterruptedException {
        AgentProperties properties = new AgentProperties();
        TelegramBotApiClient client = new TelegramBotApiClient("token", RestClient.create());
        TelegramAdapter adapter = new TelegramAdapter(properties, client);
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        SendResult result = adapter.send(source(""), "hello").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No chat_id");
    }

    @Test
    void sendReturnsFailureWhenChatIdNotNumeric() throws ExecutionException, InterruptedException {
        AgentProperties properties = new AgentProperties();
        TelegramBotApiClient client = new TelegramBotApiClient("token", RestClient.create());
        TelegramAdapter adapter = new TelegramAdapter(properties, client);
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        SendResult result = adapter.send(source("not-a-number"), "hello").get();
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No chat_id");
    }

    @Test
    void sendReturnsSuccessWithMessageId() throws ExecutionException, InterruptedException {
        AgentProperties properties = new AgentProperties();
        TelegramBotApiClient client = new TelegramBotApiClient("token", RestClient.create()) {
            @Override
            public Optional<String> sendMessage(long chatId, String text) {
                return Optional.of("42");
            }
        };
        TelegramAdapter adapter = new TelegramAdapter(properties, client);
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        SendResult result = adapter.send(source("12345"), "hello").get();
        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("42");
    }

    @Test
    void sendTypingReturnsFailureWithoutChatId() throws ExecutionException, InterruptedException {
        AgentProperties properties = new AgentProperties();
        TelegramBotApiClient client = new TelegramBotApiClient("token", RestClient.create());
        TelegramAdapter adapter = new TelegramAdapter(properties, client);
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).get();
        SendResult result = adapter.sendTyping(source("")).get();
        assertThat(result.success()).isFalse();
    }

    private SessionSource source(String chatId) {
        return new SessionSource(Platform.TELEGRAM, chatId, "user1", "user", "User");
    }
}
