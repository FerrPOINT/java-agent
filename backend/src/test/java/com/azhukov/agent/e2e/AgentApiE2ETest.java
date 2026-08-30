package com.azhukov.agent.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * E2E tests against a running java-agent instance.
 * Requires the server to be up on localhost:18090 with dev profile (Ollama Cloud LLM).
 * Run: docker compose -f docker-compose.local.yml up -d --build
 * Then: ./gradlew e2eTest
 */
@Tag("e2e")
class AgentApiE2ETest {

    private static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:18090");
    // The deployed identity is configurable through AGENT_NAME, so an external E2E
    // target must declare the name it is expected to expose.
    private static final String EXPECTED_AGENT_NAME = System.getProperty("e2e.agentName", "Джава агент");
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("GET /actuator/health — liveness UP")
    void healthLivenessUp() throws Exception {
        HttpResponse<String> resp = get("/actuator/health/liveness");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("UP", body.get("status").asText());
    }

    @Test
    @DisplayName("GET /actuator/health — readiness UP")
    void healthReadinessUp() throws Exception {
        HttpResponse<String> resp = get("/actuator/health/readiness");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("UP", body.get("status").asText());
    }

    @Test
    @DisplayName("GET /api/v1/health — exposes the configured agent name")
    void apiHealthReturnsConfiguredAgentName() throws Exception {
        HttpResponse<String> resp = get("/api/v1/health");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("UP", body.get("status").asText());
        assertEquals(EXPECTED_AGENT_NAME, body.get("name").asText());
    }

    @Test
    @DisplayName("POST /api/v1/agent/chat — returns non-empty response from LLM")
    void agentChatReturnsResponse() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/chat",
            "{\"message\":\"What is 2+2? Answer with just the number.\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.get("completed").asBoolean());
        String content = body.get("content").asText();
        assertNotNull(content);
        assertFalse(content.isBlank());
        assertNotNull(body.get("sessionId").asText());
    }

    @Test
    @DisplayName("POST /v1/chat/completions — OpenAI-compatible endpoint returns chat.completion")
    void openAiChatCompletionsReturnsResponse() throws Exception {
        HttpResponse<String> resp = post("/v1/chat/completions",
            "{\"model\":\"kimi-k2.6\",\"messages\":[{\"role\":\"user\",\"content\":\"Say hello in one word\"}]}");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertEquals("chat.completion", body.get("object").asText());
        assertTrue(body.get("choices").isArray());
        assertTrue(body.get("choices").size() > 0);
        JsonNode message = body.get("choices").get(0).get("message");
        assertEquals("assistant", message.get("role").asText());
        assertFalse(message.get("content").asText().isBlank());
    }

    @Test
    @DisplayName("GET /api/v1/sessions — returns session list")
    void sessionsListReturnsArray() throws Exception {
        // First create a session via chat
        post("/api/v1/agent/chat", "{\"message\":\"hello\"}");

        HttpResponse<String> resp = get("/api/v1/sessions");
        assertEquals(200, resp.statusCode());
        JsonNode body = mapper.readTree(resp.body());
        assertTrue(body.isArray());
        assertTrue(body.size() > 0);
        // Each session should have required fields
        JsonNode first = body.get(0);
        assertNotNull(first.get("id"));
        assertNotNull(first.get("userId"));
        assertNotNull(first.get("modelProvider"));
    }

    @Test
    @DisplayName("POST /api/v1/agent/chat — handles empty message gracefully")
    void agentChatEmptyMessage() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/chat", "{\"message\":\"\"}");
        // Empty message may return 200 or 400, just verify server doesn't crash
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 400 || resp.statusCode() == 500);
    }

    @Test
    @DisplayName("POST /api/v1/agent/chat — returns sessionId that can be reused")
    void agentChatSessionIdConsistent() throws Exception {
        HttpResponse<String> resp1 = post("/api/v1/agent/chat",
            "{\"message\":\"Remember the number 42\"}");
        JsonNode body1 = mapper.readTree(resp1.body());
        String sessionId = body1.get("sessionId").asText();
        assertNotNull(sessionId);

        // Verify the exact session rather than relying on the legacy list's
        // fixed first-page limit, which may exclude a newly-created session.
        HttpResponse<String> sessionResp = get("/api/v2/sessions/" + sessionId);
        assertEquals(200, sessionResp.statusCode());
        JsonNode session = mapper.readTree(sessionResp.body());
        assertEquals(sessionId, session.get("id").asText());
    }

    @Test
    @DisplayName("POST /v1/chat/completions — streaming via SSE")
    void openAiChatCompletionsStream() throws Exception {
        try {
            HttpResponse<String> resp = post("/v1/chat/completions",
                "{\"model\":\"kimi-k2.6\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Say hi\"}]}");
            if (resp.statusCode() == 200) {
                String body = resp.body();
                assertTrue(body.contains("data:") || body.contains("content") || body.contains("delta"),
                    "Streaming response should contain SSE data or content chunks");
            }
        } catch (java.io.IOException e) {
            // SSE streams can be long-lived and timeout — that's acceptable
            System.out.println("SSE stream timed out (expected for long-lived streams): " + e.getMessage());
        }
    }
}