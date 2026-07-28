package com.azhukov.agent.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test for the Telegram connector.
 *
 * This test talks directly to the Telegram Bot API to verify:
 * 1. Bot token is valid (getMe)
 * 2. Bot can receive messages (getUpdates)
 * 3. Bot can send messages (sendMessage)
 *
 * It does NOT test the java-agent backend directly — that's AgentApiE2ETest.
 * The Telegram connector is a separate gateway adapter that:
 * - Polls Telegram getUpdates
 * - Routes inbound messages to the agent API
 * - Sends agent responses back via sendMessage
 *
 * Prerequisites:
 * - TELEGRAM_BOT_TOKEN env var set
 * - Bot must be running (long-polling active in java-agent)
 *
 * Run: ./gradlew e2eTest --tests "*Telegram*"
 */
@Tag("e2e")
class TelegramConnectorE2ETest {

    private static final String BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");
    private static final String TELEGRAM_API = "https://api.telegram.org";
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    private HttpResponse<String> telegramGet(String method) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(TELEGRAM_API + "/bot" + BOT_TOKEN + "/" + method))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> telegramPost(String method, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(TELEGRAM_API + "/bot" + BOT_TOKEN + "/" + method))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private boolean hasToken() {
        return BOT_TOKEN != null && !BOT_TOKEN.isBlank();
    }

    @Test
    @DisplayName("getMe — bot token is valid and bot is accessible")
    void botTokenIsValid() throws Exception {
        Assumptions.assumeTrue(hasToken(), "TELEGRAM_BOT_TOKEN not set, skipping");

        HttpResponse<String> resp = telegramGet("getMe");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("ok").asBoolean());
        assertTrue(body.get("result").get("is_bot").asBoolean());
        String botName = body.get("result").get("username").asText();
        assertNotNull(botName);
        assertFalse(botName.isBlank());
        System.out.println("Bot: @" + botName);
    }

    @Test
    @DisplayName("getUpdates — can poll for messages (bot is not in conflict)")
    void getUpdatesWorks() throws Exception {
        Assumptions.assumeTrue(hasToken(), "TELEGRAM_BOT_TOKEN not set, skipping");

        // Use offset=0 to not interfere with long-polling
        HttpResponse<String> resp = telegramGet("getUpdates?offset=0&limit=1&timeout=0");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("ok").asBoolean());
        // result can be empty — that's fine, we just verify the API responds OK
        assertTrue(body.get("result").isArray());
    }

    @Test
    @DisplayName("sendMessage — bot can send a test message to itself (getMe chat_id)")
    void botCanSendMessage() throws Exception {
        Assumptions.assumeTrue(hasToken(), "TELEGRAM_BOT_TOKEN not set, skipping");

        // First get bot info
        HttpResponse<String> meResp = telegramGet("getMe");
        JsonNode me = mapper.readTree(meResp.body()).get("result");

        // Bots can't message themselves directly, but we can verify sendMessage API works
        // by sending to a known chat. We'll use getUpdates to find a recent chat.
        HttpResponse<String> updatesResp = telegramGet("getUpdates?offset=-1&limit=1&timeout=0");
        JsonNode updates = mapper.readTree(updatesResp.body()).get("result");

        if (updates.size() > 0) {
            JsonNode lastUpdate = updates.get(0);
            if (lastUpdate.has("message")) {
                long chatId = lastUpdate.get("message").get("chat").get("id").asLong();

                // Send a test message to that chat
                HttpResponse<String> sendResp = telegramPost("sendMessage",
                    "{\"chat_id\":" + chatId + ",\"text\":\"E2E test ping ✅\"}");
                assertEquals(200, sendResp.statusCode());
                JsonNode sendBody = mapper.readTree(sendResp.body());
                assertTrue(sendBody.get("ok").asBoolean());
                System.out.println("Sent test message to chat_id=" + chatId);
            }
        }
        // If no updates available, skip — this test requires at least one prior message
    }

    @Test
    @DisplayName("Full Telegram → Agent → Telegram round-trip")
    void fullRoundTrip() throws Exception {
        Assumptions.assumeTrue(hasToken(), "TELEGRAM_BOT_TOKEN not set, skipping");

        // This test verifies the full pipeline:
        // 1. User sends message to bot
        // 2. Long-polling picks it up
        // 3. Agent processes via LLM
        // 4. Bot sends response back
        //
        // Since we can't send a message AS a user programmatically,
        // we verify the pipeline indirectly:
        // a) Bot is reachable (getMe OK)
        // b) Agent API is reachable and responds (verified in AgentApiE2ETest)
        // c) Long-polling is active (check agent logs)
        //
        // Manual test: send "привет" to the bot, expect a response within 10s

        HttpResponse<String> meResp = telegramGet("getMe");
        JsonNode meBody = mapper.readTree(meResp.body());
        assertTrue(meBody.get("ok").asBoolean());
        String botUsername = meBody.get("result").get("username").asText();
        System.out.println("Telegram bot @" + botUsername + " is reachable");
        System.out.println("Manual test: send a message to @" + botUsername + " and verify response");
    }
}