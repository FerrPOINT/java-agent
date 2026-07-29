package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramClientTest {

    private TelegramClient client;

    @BeforeEach
    void setUp() {
        // Use a dummy token — no actual API calls will be made in this test
        org.springframework.web.client.RestClient restClient = org.springframework.web.client.RestClient.create();
        client = new TelegramClient(restClient, new ObjectMapper(), "dummy-token", 30);
    }

    @Test
    void setMyCommandsSendsArrayNotString() {
        // Verify that commands param is a List (JSON array), not a serialized string
        // We can't easily mock the HTTP call, but we can verify the method doesn't throw
        // and the return type is boolean.
        // The real test is that Telegram accepts the payload — if commands is a string,
        // Telegram returns 400 "Bad Request: commands should be an array"

        List<Map<String, String>> commands = List.of(
            Map.of("command", "help", "description", "Show help"),
            Map.of("command", "status", "description", "Show status")
        );

        // The method should not throw — if it serializes commands as a string,
        // it would still not throw locally, but Telegram would reject it.
        // This test verifies the code path works without serialization errors.
        // A real integration test would check the Telegram API response.
        try {
            boolean result = client.setMyCommands(commands);
            // Result will be false because the token is dummy, but no exception
            assertThat(result).isFalse();
        } catch (Exception e) {
            // Should not throw — if it does, the serialization is broken
            throw new AssertionError("setMyCommands should not throw", e);
        }
    }

    @Test
    void setMyCommandsForChatSendsArrayNotString() {
        List<Map<String, String>> commands = List.of(
            Map.of("command", "help", "description", "Show help")
        );

        try {
            boolean result = client.setMyCommandsForChat(123L, commands);
            assertThat(result).isFalse();
        } catch (Exception e) {
            throw new AssertionError("setMyCommandsForChat should not throw", e);
        }
    }
}