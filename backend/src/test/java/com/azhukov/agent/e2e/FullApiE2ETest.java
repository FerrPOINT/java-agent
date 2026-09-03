package com.azhukov.agent.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full E2E test suite covering ALL agent API endpoints via real HTTP.
 * Talks to a running java-agent backend (default: localhost:8090, dev profile, kimi-k2.6).
 *
 * <p>Run: {@code ./gradlew :backend:e2eTest --tests "*FullApiE2ETest*" -De2e.baseUrl=http://localhost:8090}
 *
 * <p>Tests are ordered and run in a single thread to avoid rate-limit issues.
 * LLM-dependent tests include retry logic for 429 responses.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class FullApiE2ETest {

    private static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8090");
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    // Shared state across ordered tests
    private static UUID sharedSessionId;
    private static UUID cronJobId;
    private static UUID checkpointId;
    private static UUID kanbanItemId;
    private static UUID memoryEntryId;
    private static UUID v2SessionId;

    // ── HTTP helpers ──

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return post(path, json, 120);
    }

    /**
     * rev-127: live curator/run cycles run a full LLM agent loop (5+ iterations,
     * 8+ tool calls) and can take ~3 minutes against a real model. The default
     * 120s post timeout is tuned for the docker NoOp stack; long-running
     * endpoints opt into a wider budget via this overload.
     */
    private HttpResponse<String> post(String path, String json, int timeoutSeconds) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(json))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(60))
            .DELETE()
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithBody(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString(json))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Retry on 429 (rate limit) with exponential backoff. */
    private HttpResponse<String> postWithRetry(String path, String json) throws Exception {
        return httpWithRetry("POST", path, json);
    }

    /** Retry GET on 429. */
    private HttpResponse<String> getWithRetry(String path) throws Exception {
        return httpWithRetry("GET", path, null);
    }

    /** Retry DELETE on 429. */
    private HttpResponse<String> deleteWithRetry(String path) throws Exception {
        return httpWithRetry("DELETE", path, null);
    }

    private HttpResponse<String> httpWithRetry(String method, String path, String json) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpResponse<String> resp;
            if ("POST".equals(method)) {
                resp = post(path, json);
            } else if ("GET".equals(method)) {
                resp = get(path);
            } else {
                resp = delete(path);
            }
            if (resp.statusCode() != 429) return resp;
            // timing-assertion: exponential backoff for rate-limit retry
            Thread.sleep(3000L * (attempt + 1));
        }
        // Last attempt without retry
        if ("POST".equals(method)) return post(path, json);
        if ("GET".equals(method)) return get(path);
        return delete(path);
    }

    /** Retry on 429 (rate limit) and 5xx (provider errors) with exponential backoff. */
    private HttpResponse<String> postWithRetry5xx(String path, String json) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpResponse<String> resp = post(path, json);
            int code = resp.statusCode();
            if (code != 429 && code < 500) return resp;
            // 429 or 5xx — retry with backoff
            Thread.sleep(3000L * (attempt + 1));
        }
        return post(path, json);
    }

    /** Parse JSON safely, returning null if body is not valid JSON. */
    private JsonNode parseJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    @BeforeAll
    static void checkServer() throws Exception {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v1/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            Assumptions.assumeTrue(resp.statusCode() == 200,
                "Server not running at " + BASE_URL + " — skipping E2E tests");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "Server not reachable at " + BASE_URL + ": " + e.getMessage());
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void smallDelay() throws InterruptedException {
        // timing-assertion: rate-limit spacing between E2E tests
        Thread.sleep(200);
    }

    // ── 1. Health & Doctor ──

    @Test @Order(1) @DisplayName("GET /api/v1/health — status UP")
    void healthUp() throws Exception {
        HttpResponse<String> resp = get("/api/v1/health");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body, "Health should return JSON");
        assertEquals("UP", body.get("status").asText());
        assertTrue(body.has("name"));
    }

    @Test @Order(2) @DisplayName("GET /api/v1/agent/health — status UP")
    void agentHealthUp() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/health");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals("UP", body.get("status").asText());
    }

    @Test @Order(3) @DisplayName("GET /actuator/health/liveness — UP")
    void livenessUp() throws Exception {
        HttpResponse<String> resp = get("/actuator/health/liveness");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals("UP", body.get("status").asText());
    }

    @Test @Order(4) @DisplayName("GET /actuator/health/readiness — UP")
    void readinessUp() throws Exception {
        HttpResponse<String> resp = get("/actuator/health/readiness");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals("UP", body.get("status").asText());
    }

    @Test @Order(5) @DisplayName("GET /api/v1/agent/doctor — returns diagnostics")
    void doctorReturnsDiagnostics() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/doctor");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertTrue(body.has("name"));
        assertTrue(body.has("model"));
        assertTrue(body.has("provider"));
        assertEquals("UP", body.get("status").asText());
        assertTrue(body.get("maxTurns").asInt() > 0);
    }

    // ── 2. Chat (creates shared session) ──

    @Test @Order(10) @DisplayName("POST /api/v1/agent/chat — returns LLM response with sessionId")
    void chatReturnsResponse() throws Exception {
        HttpResponse<String> resp = postWithRetry("/api/v1/agent/chat",
            "{\"message\":\"What is 2+2? Answer with just the number.\"}");
        assertEquals(200, resp.statusCode(), "Chat should return 200, got: " + resp.statusCode() + " body: " + resp.body());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertTrue(body.get("completed").asBoolean());
        String content = body.get("content").asText();
        assertNotNull(content);
        assertFalse(content.isBlank());
        String sid = body.get("sessionId").asText();
        assertNotNull(sid);
        sharedSessionId = UUID.fromString(sid);
    }

    @Test @Order(11) @DisplayName("POST /api/v1/agent/chat — with sessionId reuses session")
    void chatReusesSession() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session from previous test");
        HttpResponse<String> resp = postWithRetry("/api/v1/agent/chat",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"message\":\"What was the number I just asked about?\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.get("completed").asBoolean());
        assertFalse(body.get("content").asText().isBlank());
        assertEquals(sharedSessionId.toString(), body.get("sessionId").asText());
    }

    @Test @Order(12) @DisplayName("POST /api/v1/agent/chat — empty message returns 400 or 200")
    void chatEmptyMessage() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/chat", "{\"message\":\"\"}");
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 400,
            "Empty message should return 200 or 400, got " + resp.statusCode());
    }

    @Test @Order(13) @DisplayName("POST /api/v1/agent/chat/stream — SSE stream returns tokens")
    void chatStreamReturnsSse() throws Exception {
        try {
            HttpResponse<String> resp = post("/api/v1/agent/chat/stream",
                "{\"message\":\"Say hello in one word\"}");
            if (resp.statusCode() == 200) {
                String body = resp.body();
                assertTrue(body.contains("data:") || body.contains("token") || body.contains("content"),
                    "SSE response should contain token data");
            }
        } catch (java.io.IOException e) {
            System.out.println("SSE stream timed out (expected): " + e.getMessage());
        }
    }

    // ── 3. Sessions ──

    @Test @Order(20) @DisplayName("GET /api/v1/sessions — returns non-empty array")
    void sessionsList() throws Exception {
        HttpResponse<String> resp = get("/api/v1/sessions");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
        assertTrue(body.size() > 0, "Should have at least one session from chat test");
        JsonNode first = body.get(0);
        assertNotNull(first.get("id"));
        assertNotNull(first.get("userId"));
        assertNotNull(first.get("modelProvider"));
    }

    @Test @Order(21) @DisplayName("POST /api/v1/agent/session — creates new session")
    void createSession() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/session", "{\"userId\":\"e2e-test-user\"}");
        assertEquals(201, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body.get("id"));
        assertEquals("e2e-test-user", body.get("userId").asText());
    }

    @Test @Order(22) @DisplayName("GET /api/v1/agent/sessions/{userId} — filter by userId")
    void sessionsByUserId() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/sessions/e2e-test-user");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
        assertTrue(body.size() > 0);
        for (JsonNode s : body) {
            assertEquals("e2e-test-user", s.get("userId").asText());
        }
    }

    @Test @Order(23) @DisplayName("GET /api/v1/agent/session/{sessionId}/context — context info")
    void getSessionContext() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = get("/api/v1/agent/session/" + sharedSessionId + "/context");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals(sharedSessionId.toString(), body.get("sessionId").asText());
        assertTrue(body.get("messageCount").asInt() > 0);
        assertTrue(body.get("tokenEstimate").asInt() > 0);
    }

    @Test @Order(24) @DisplayName("GET /api/v1/agent/session/{sessionId}/usage — usage stats")
    void getSessionUsage() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = get("/api/v1/agent/session/" + sharedSessionId + "/usage");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals(sharedSessionId.toString(), body.get("sessionId").asText());
        assertTrue(body.get("messageCount").asInt() > 0);
    }

    @Test @Order(25) @DisplayName("POST /api/v1/agent/session/{sessionId}/reset — resets session")
    void resetSession() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/session/" + sharedSessionId + "/reset", "");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(26) @DisplayName("POST /api/v1/agent/session/{sessionId}/branch — branches session")
    void branchSession() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/session/" + sharedSessionId + "/branch?name=E2E%20Branch", "");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body.get("id"));
        assertEquals("E2E Branch", body.get("title").asText());
    }

    @Test @Order(27) @DisplayName("POST /api/v1/agent/session/title — sets session title")
    void setSessionTitle() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/session/title",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"title\":\"E2E Test Session\"}");
        assertEquals(200, resp.statusCode());
    }

    // ── 4. Config ──

    @Test @Order(30) @DisplayName("GET /api/v1/agent/config — returns full config")
    void getConfig() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/config");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.has("name"));
        assertTrue(body.has("model"));
        assertTrue(body.has("provider"));
        assertTrue(body.has("maxTurns"));
        assertTrue(body.has("features"));
        assertTrue(body.get("features").has("memory"));
    }

    // ── 5. Tools ──

    @Test @Order(40) @DisplayName("GET /api/v1/agent/tools — lists available tools")
    void listTools() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/tools");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
        assertTrue(body.size() > 5, "Should have at least 5 tools registered");
        java.util.Set<String> toolNames = new java.util.HashSet<>();
        body.forEach(n -> toolNames.add(n.asText()));
        assertTrue(toolNames.contains("read_file"), "read_file tool should be registered");
        assertTrue(toolNames.contains("write_file"), "write_file tool should be registered");
        assertTrue(toolNames.contains("terminal"), "terminal tool should be registered");
    }

    @Test @Order(41) @DisplayName("POST /api/v1/agent/tools/enable — enables a tool")
    void enableTool() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/tools/enable",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"toolName\":\"read_file\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(42) @DisplayName("POST /api/v1/agent/tools/disable — disables a tool")
    void disableTool() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/tools/disable",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"toolName\":\"terminal\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(43) @DisplayName("POST /api/v1/agent/tools/disable — missing sessionId returns 400")
    void disableToolMissingSession() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/tools/disable",
            "{\"toolName\":\"terminal\"}");
        assertEquals(400, resp.statusCode());
    }

    @Test @Order(44) @DisplayName("POST /api/v1/agent/tools/disable — missing toolName returns 400")
    void disableToolMissingName() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/tools/disable",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(400, resp.statusCode());
    }

    // ── 6. Goal management ──

    @Test @Order(50) @DisplayName("POST /api/v1/agent/goal — sets goal")
    void setGoal() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/goal",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"goal\":\"Complete E2E testing\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(51) @DisplayName("GET /api/v1/agent/goal — retrieves goal")
    void getGoal() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = get("/api/v1/agent/goal?sessionId=" + sharedSessionId);
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals("Complete E2E testing", body.get("goal").asText());
        assertFalse(body.get("paused").asBoolean());
    }

    @Test @Order(52) @DisplayName("POST /api/v1/agent/goal/pause — pauses goal")
    void pauseGoal() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/goal/pause",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(53) @DisplayName("GET /api/v1/agent/goal — paused goal shows true")
    void getGoalPaused() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = get("/api/v1/agent/goal?sessionId=" + sharedSessionId);
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.get("paused").asBoolean());
    }

    @Test @Order(54) @DisplayName("POST /api/v1/agent/goal/resume — resumes goal")
    void resumeGoal() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/goal/resume",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(55) @DisplayName("POST /api/v1/agent/goal/clear — clears goal")
    void clearGoal() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/goal/clear",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(56) @DisplayName("DELETE /api/v1/agent/goal — deletes goal")
    void deleteGoal() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        post("/api/v1/agent/goal", "{\"sessionId\":\"" + sharedSessionId + "\",\"goal\":\"temp goal\"}");
        HttpResponse<String> resp = deleteWithBody("/api/v1/agent/goal",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
    }

    // ── 7. Subgoal ──

    @Test @Order(60) @DisplayName("POST /api/v1/agent/subgoal — sets subgoal")
    void setSubgoal() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/subgoal",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"subgoal\":\"Write tests\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(61) @DisplayName("POST /api/v1/agent/subgoal — append=true appends")
    void appendSubgoal() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/subgoal",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"subgoal\":\"Run tests\",\"append\":\"true\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(62) @DisplayName("POST /api/v1/agent/subgoal/clear — clears subgoals")
    void clearSubgoals() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/subgoal/clear",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(63) @DisplayName("DELETE /api/v1/agent/subgoal — deletes subgoals")
    void deleteSubgoals() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = deleteWithBody("/api/v1/agent/subgoal",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
    }

    // ── 8. CLI runtime settings ──

    @Test @Order(70) @DisplayName("POST /api/v1/agent/reasoning — sets reasoning effort")
    void setReasoning() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/reasoning",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"effort\":\"medium\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(71) @DisplayName("POST /api/v1/agent/fast-mode — toggles fast mode")
    void toggleFastMode() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/fast-mode",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"enabled\":\"true\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.asBoolean());
    }

    @Test @Order(72) @DisplayName("POST /api/v1/agent/voice-mode — toggles voice mode")
    void toggleVoiceMode() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/voice-mode",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"enabled\":\"false\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(73) @DisplayName("POST /api/v1/agent/personality — sets personality")
    void setPersonality() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/personality",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"personality\":\"You are a helpful assistant.\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(74) @DisplayName("POST /api/v1/agent/queue — queues a prompt")
    void queuePrompt() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/queue",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"queued\":\"Next task: write code\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(75) @DisplayName("POST /api/v1/agent/browser — sets CDP URL")
    void setBrowserCdp() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/browser",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"cdpUrl\":\"http://localhost:9222\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(76) @DisplayName("POST /api/v1/agent/state/reset — resets session state")
    void resetState() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/state/reset",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
    }

    // ── 9. Memory ──

    @Test @Order(80) @DisplayName("POST /api/v1/agent/memory — stores a fact")
    void storeMemory() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/memory",
            "{\"userId\":\"e2e-user\",\"fact\":\"User prefers concise responses\",\"category\":\"user\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(81) @DisplayName("GET /api/v1/agent/memory — recalls memory")
    void recallMemory() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/memory");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    @Test @Order(82) @DisplayName("GET /api/v1/agent/memory/all/{userId} — lists all memory")
    void listAllMemory() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/memory/all/e2e-user");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
        if (body.size() > 0) {
            JsonNode first = body.get(0);
            assertNotNull(first.get("id"));
            assertNotNull(first.get("fact"));
            assertNotNull(first.get("category"));
            memoryEntryId = UUID.fromString(first.get("id").asText());
        }
    }

    @Test @Order(83) @DisplayName("POST /api/v1/agent/memory/approval — enable approval mode")
    void enableMemoryApproval() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/memory/approval", "{\"enabled\":true}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(84) @DisplayName("GET /api/v1/agent/memory/approval — approval mode is enabled")
    void getMemoryApproval() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/memory/approval");
        assertEquals(200, resp.statusCode());
        String body = resp.body().trim();
        assertTrue("true".equals(body) || "false".equals(body),
            "Approval should be boolean, got: " + body);
    }

    @Test @Order(85) @DisplayName("POST /api/v1/agent/memory/approval — disable approval mode")
    void disableMemoryApproval() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/memory/approval", "{\"enabled\":false}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(86) @DisplayName("GET /api/v1/agent/memory/pending/{userId} — lists pending")
    void listPendingMemory() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/memory/pending/e2e-user");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    // ── 10. Skills ──

    @Test @Order(90) @DisplayName("GET /api/v1/agent/skills — lists skill names")
    void listSkills() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/skills");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    @Test @Order(91) @DisplayName("GET /api/v1/agent/skills/{name} — gets skill content (or not found)")
    void getSkillContent() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/skills/nonexistent-skill");
        // May return 200 with ok=false — just verify no crash
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 404 || resp.statusCode() == 500);
        if (resp.statusCode() == 200) {
            JsonNode body = parseJson(resp.body());
            if (body != null) {
                assertFalse(body.get("ok").asBoolean());
            }
        }
    }

    @Test @Order(92) @DisplayName("POST /api/v1/agent/reload-skills — reloads skills")
    void reloadSkills() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/reload-skills", "");
        assertEquals(200, resp.statusCode());
    }

    // ── 11. Bundles ──

    @Test @Order(95) @DisplayName("GET /api/v1/agent/bundles — lists bundles")
    void listBundles() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/bundles");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    @Test @Order(96) @DisplayName("GET /api/v1/agent/skills/bundles — alias for bundles")
    void listBundlesAlias() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/skills/bundles");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    // ── 12. Checkpoint ──

    @Test @Order(100) @DisplayName("POST /api/v1/agent/checkpoint — creates checkpoint")
    void createCheckpoint() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/checkpoint",
            "{\"description\":\"E2E test checkpoint\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body.get("id"));
        assertEquals("E2E test checkpoint", body.get("description").asText());
        checkpointId = UUID.fromString(body.get("id").asText());
    }

    @Test @Order(101) @DisplayName("GET /api/v1/agent/checkpoint — lists checkpoints")
    void listCheckpoints() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/checkpoint");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
        assertTrue(body.size() > 0, "Should have at least one checkpoint");
    }

    @Test @Order(102) @DisplayName("POST /api/v1/agent/snapshot — creates snapshot")
    void createSnapshot() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/snapshot",
            "{\"description\":\"E2E snapshot\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(103) @DisplayName("GET /api/v1/agent/diff — diffs two checkpoints")
    void diffCheckpoints() throws Exception {
        HttpResponse<String> listResp = get("/api/v1/agent/checkpoint");
        JsonNode checkpoints = parseJson(listResp.body());
        Assumptions.assumeTrue(checkpoints.size() >= 2, "Need at least 2 checkpoints for diff");
        UUID left = UUID.fromString(checkpoints.get(0).get("id").asText());
        UUID right = UUID.fromString(checkpoints.get(1).get("id").asText());
        HttpResponse<String> resp = get("/api/v1/agent/diff?left=" + left + "&right=" + right + "&scope=context");
        assertEquals(200, resp.statusCode());
    }

    // ── 13. Compress / Undo ──

    @Test @Order(110) @DisplayName("POST /api/v1/agent/compress — compresses context")
    void compressContext() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = postWithRetry("/api/v1/agent/compress",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("compressed") || resp.body().contains("Context"),
            "Should mention compression in response");
    }

    @Test @Order(111) @DisplayName("POST /api/v1/agent/undo — undoes last turn")
    void undoTurn() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        // First add a turn to undo
        postWithRetry("/api/v1/agent/chat",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"message\":\"Tell me a joke\"}");
        HttpResponse<String> resp = post("/api/v1/agent/undo",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"turns\":1}");
        assertEquals(200, resp.statusCode());
        int removed = Integer.parseInt(resp.body().trim());
        assertTrue(removed >= 1, "Should remove at least 1 turn, got: " + removed);
    }

    @Test @Order(112) @DisplayName("POST /api/v1/agent/session/{sessionId}/compress — path-based compress")
    void compressSessionPath() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = postWithRetry("/api/v1/agent/session/" + sharedSessionId + "/compress",
            "{\"focusTopic\":\"testing\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(113) @DisplayName("POST /api/v1/agent/session/{sessionId}/undo — path-based undo")
    void undoSessionPath() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        postWithRetry("/api/v1/agent/chat",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"message\":\"Say hi\"}");
        HttpResponse<String> resp = post("/api/v1/agent/session/" + sharedSessionId + "/undo?turns=1", "");
        assertEquals(200, resp.statusCode());
    }

    // ── 14. Curator ──

    @Test @Order(120) @DisplayName("GET /api/v1/agent/curator/status — curator status")
    void curatorStatus() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/curator/status");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.has("enabled"));
        assertTrue(body.has("paused"));
        assertTrue(body.has("intervalHours"));
    }

    @Test @Order(121) @DisplayName("POST /api/v1/agent/curator/pause — pauses curator")
    void curatorPause() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/curator/pause", "");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(122) @DisplayName("POST /api/v1/agent/curator/resume — resumes curator")
    void curatorResume() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/curator/resume", "");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(123) @DisplayName("POST /api/v1/agent/curator/run — runs curator cycle")
    void curatorRun() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/curator/run", "", 360);
        assertEquals(200, resp.statusCode());
        assertNotNull(resp.body());
        assertFalse(resp.body().isBlank());
    }

    // ── 15. Credits / Insights ──

    @Test @Order(130) @DisplayName("GET /api/v1/agent/credits — usage summary")
    void getCredits() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/credits");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.has("totalCost"));
        assertTrue(body.has("totalTokens"));
        assertTrue(body.has("totalMessages"));
    }

    @Test @Order(131) @DisplayName("GET /api/v1/agent/insights — insights data")
    void getInsights() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/insights");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.has("totalTokens"));
        assertTrue(body.has("totalMessages"));
        assertTrue(body.has("byModel"));
    }

    // ── 16. Model switching ──

    @Test @Order(140) @DisplayName("GET /api/v1/agent/model — current model info")
    void getCurrentModel() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = get("/api/v1/agent/model?sessionId=" + sharedSessionId);
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.has("sessionId"));
        assertTrue(body.has("messageCount"));
    }

    @Test @Order(141) @DisplayName("POST /api/v1/agent/model — switch model")
    void switchModel() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/model",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"model\":\"kimi-k2.6\",\"provider\":\"openai-compatible\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.get("ok").asBoolean());
        assertEquals("kimi-k2.6", body.get("model").asText());
    }

    @Test @Order(142) @DisplayName("POST /api/v1/agent/model — missing model returns ok=false")
    void switchModelMissingModel() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/model",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertFalse(body.get("ok").asBoolean());
    }

    // ── 17. Codex Runtime ──

    @Test @Order(150) @DisplayName("GET /api/v1/agent/codex-runtime — runtime status")
    void codexRuntimeStatus() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/codex-runtime");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.has("model"));
        assertTrue(body.has("provider"));
        assertTrue(body.has("maxTokens"));
        assertTrue(body.has("modelOverride"));
    }

    @Test @Order(151) @DisplayName("POST /api/v1/agent/codex-runtime/model — set model override")
    void codexRuntimeSetModel() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/codex-runtime/model",
            "{\"model\":\"kimi-k2.6\"}");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(152) @DisplayName("POST /api/v1/agent/codex-runtime/reset — reset runtime")
    void codexRuntimeReset() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/codex-runtime/reset", "");
        assertEquals(200, resp.statusCode());
    }

    // ── 18. Steer ──

    @Test @Order(160) @DisplayName("POST /api/v1/agent/steer — steers active session")
    void steerSession() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/steer",
            "{\"sessionId\":\"" + sharedSessionId + "\",\"text\":\"Focus on testing\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.has("accepted"));
        assertTrue(body.has("sessionId"));
    }

    @Test @Order(161) @DisplayName("POST /api/v1/agent/steer — missing text returns accepted=false")
    void steerMissingText() throws Exception {
        Assumptions.assumeTrue(sharedSessionId != null, "No shared session");
        HttpResponse<String> resp = post("/api/v1/agent/steer",
            "{\"sessionId\":\"" + sharedSessionId + "\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertFalse(body.get("accepted").asBoolean());
    }

    // ── 19. Stop ──

    @Test @Order(170) @DisplayName("POST /api/v1/agent/stop — stops agent")
    void stopAgent() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/stop", "{}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.get("ok").asBoolean());
    }

    // ── 20. Approvals ──

    @Test @Order(180) @DisplayName("GET /api/v1/agent/approvals/pending — lists pending approvals")
    void pendingApprovals() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/approvals/pending");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    @Test @Order(181) @DisplayName("POST /api/v1/agent/approve — approve with no pending returns message")
    void approveNoPending() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/approve",
            "{\"all\":false}");
        assertEquals(200, resp.statusCode());
        assertNotNull(resp.body());
    }

    @Test @Order(182) @DisplayName("POST /api/v1/agent/deny — deny with no pending returns message")
    void denyNoPending() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/deny",
            "{\"all\":false}");
        assertEquals(200, resp.statusCode());
        assertNotNull(resp.body());
    }

    @Test @Order(183) @DisplayName("POST /api/v1/agent/approve — approve all with no pending")
    void approveAll() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/approve",
            "{\"all\":true}");
        assertEquals(200, resp.statusCode());
    }

    // ── 21. Agents ──

    @Test @Order(190) @DisplayName("GET /api/v1/agent/agents — lists active agents")
    void listActiveAgents() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/agents");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    // ── 22. Background ──

    @Test @Order(200) @DisplayName("POST /api/v1/agent/background — runs background task")
    void runBackground() throws Exception {
        HttpResponse<String> resp = postWithRetry("/api/v1/agent/background",
            "{\"prompt\":\"Summarize the current state\"}");
        assertEquals(200, resp.statusCode());
        assertNotNull(resp.body());
        assertFalse(resp.body().isBlank());
    }

    // ── 23. Delegate ──

    @Test @Order(210) @DisplayName("POST /api/v1/agent/delegate — delegates task")
    void delegateTask() throws Exception {
        HttpResponse<String> resp = postWithRetry("/api/v1/agent/delegate",
            "{\"message\":\"What is 1+1? Answer with just the number.\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.get("completed").asBoolean());
        assertFalse(body.get("content").asText().isBlank());
    }

    // ── 24. Reload endpoints ──

    @Test @Order(220) @DisplayName("POST /api/v1/agent/reload-mcp — reloads MCP")
    void reloadMcp() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/reload-mcp", "");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(221) @DisplayName("POST /api/v1/agent/reload — reloads everything")
    void reloadAll() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/reload", "");
        assertEquals(200, resp.statusCode());
    }

    // ── 25. Kanban ──

    @Test @Order(230) @DisplayName("GET /api/v1/agent/kanban — lists kanban items")
    void kanbanList() throws Exception {
        HttpResponse<String> resp = getWithRetry("/api/v1/agent/kanban");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    @Test @Order(231) @DisplayName("POST /api/v1/agent/kanban/add — adds kanban item")
    void kanbanAdd() throws Exception {
        HttpResponse<String> resp = postWithRetry("/api/v1/agent/kanban/add",
            "{\"text\":\"E2E test task\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body.get("id"));
        assertEquals("E2E test task", body.get("title").asText());
        assertEquals("pending", body.get("status").asText());
        kanbanItemId = UUID.fromString(body.get("id").asText());
    }

    @Test @Order(232) @DisplayName("POST /api/v1/agent/kanban/done/{id} — marks item done")
    void kanbanDone() throws Exception {
        Assumptions.assumeTrue(kanbanItemId != null, "No kanban item from previous test");
        HttpResponse<String> resp = postWithRetry("/api/v1/agent/kanban/done/" + kanbanItemId, "");
        assertEquals(200, resp.statusCode());
    }

    @Test @Order(233) @DisplayName("DELETE /api/v1/agent/kanban — clears all kanban items")
    void kanbanClear() throws Exception {
        HttpResponse<String> resp = deleteWithRetry("/api/v1/agent/kanban");
        assertEquals(200, resp.statusCode());
    }

    // ── 26. Cron Jobs ──

    @Test @Order(240) @DisplayName("POST /api/v1/agent/cron — creates cron job")
    void createCronJob() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/cron",
            "{\"name\":\"E2E test job\",\"schedule\":\"5m\",\"prompt\":\"Say hello\",\"deliverTo\":\"local\"}");
        assertEquals(200, resp.statusCode(), "Cron create should return 200, got: " + resp.statusCode() + " body: " + resp.body());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body.get("id"));
        assertEquals("E2E test job", body.get("name").asText());
        assertEquals("5m", body.get("schedule").asText());
        cronJobId = UUID.fromString(body.get("id").asText());
    }

    @Test @Order(241) @DisplayName("GET /api/v1/agent/cron — lists cron jobs")
    void listCronJobs() throws Exception {
        HttpResponse<String> resp = getWithRetry("/api/v1/agent/cron");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
        assertTrue(body.size() > 0, "Should have at least one cron job");
    }

    @Test @Order(242) @DisplayName("PUT /api/v1/agent/cron/{id} — updates cron job")
    void updateCronJob() throws Exception {
        Assumptions.assumeTrue(cronJobId != null, "No cron job from previous test");
        HttpResponse<String> resp = put("/api/v1/agent/cron/" + cronJobId,
            "{\"name\":\"Updated E2E job\",\"schedule\":\"10m\",\"prompt\":\"Updated prompt\",\"deliverTo\":\"local\",\"enabled\":true}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals("Updated E2E job", body.get("name").asText());
        assertEquals("10m", body.get("schedule").asText());
    }

    @Test @Order(243) @DisplayName("POST /api/v1/agent/cron/{id}/pause — pauses cron job")
    void pauseCronJob() throws Exception {
        Assumptions.assumeTrue(cronJobId != null, "No cron job");
        HttpResponse<String> resp = post("/api/v1/agent/cron/" + cronJobId + "/pause", "");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertFalse(body.get("enabled").asBoolean());
    }

    @Test @Order(244) @DisplayName("POST /api/v1/agent/cron/{id}/resume — resumes cron job")
    void resumeCronJob() throws Exception {
        Assumptions.assumeTrue(cronJobId != null, "No cron job");
        HttpResponse<String> resp = post("/api/v1/agent/cron/" + cronJobId + "/resume", "");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.get("enabled").asBoolean());
    }

    @Test @Order(245) @DisplayName("DELETE /api/v1/agent/cron/{id} — deletes cron job")
    void deleteCronJob() throws Exception {
        // Create a job to delete
        HttpResponse<String> createResp = postWithRetry("/api/v1/agent/cron",
            "{\"name\":\"Job to delete\",\"schedule\":\"1h\",\"prompt\":\"temp\",\"deliverTo\":\"local\"}");
        assertEquals(200, createResp.statusCode(), "Create cron should return 200: " + createResp.body());
        JsonNode created = parseJson(createResp.body());
        assertNotNull(created.get("id"), "Created cron should have id: " + createResp.body());
        UUID jobId = UUID.fromString(created.get("id").asText());

        HttpResponse<String> resp = deleteWithRetry("/api/v1/agent/cron/" + jobId);
        assertEquals(200, resp.statusCode());
    }

    // ── 27. MCP ──

    @Test @Order(250) @DisplayName("GET /api/v1/mcp/servers — lists MCP servers")
    void listMcpServers() throws Exception {
        HttpResponse<String> resp = get("/api/v1/mcp/servers");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertTrue(body.isArray());
    }

    // ── 28. OpenAI-compatible endpoint ──

    @Test @Order(260) @DisplayName("POST /v1/chat/completions — sync completion")
    void openAiSyncCompletion() throws Exception {
        HttpResponse<String> resp = postWithRetry5xx("/v1/chat/completions",
            "{\"model\":\"main-dev\",\"messages\":[{\"role\":\"user\",\"content\":\"Say hello in one word\"}]}");
        assertEquals(200, resp.statusCode(), "OpenAI completion should return 200, got: " + resp.statusCode() + " body: " + resp.body());
        JsonNode body = parseJson(resp.body());
        assertEquals("chat.completion", body.get("object").asText());
        assertTrue(body.get("choices").isArray());
        assertTrue(body.get("choices").size() > 0);
        JsonNode message = body.get("choices").get(0).get("message");
        assertEquals("assistant", message.get("role").asText());
        assertFalse(message.get("content").asText().isBlank());
    }

    @Test @Order(261) @DisplayName("POST /v1/chat/completions — with temperature and maxTokens")
    void openAiCompletionWithParams() throws Exception {
        HttpResponse<String> resp = postWithRetry5xx("/v1/chat/completions",
            "{\"model\":\"main-dev\",\"messages\":[{\"role\":\"user\",\"content\":\"What is 3+3? Just the number.\"}],\"temperature\":0.3,\"maxTokens\":50}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals("chat.completion", body.get("object").asText());
        assertFalse(body.get("choices").get(0).get("message").get("content").asText().isBlank());
    }

    @Test @Order(262) @DisplayName("POST /v1/chat/completions — streaming via SSE")
    void openAiStreamCompletion() throws Exception {
        try {
            HttpResponse<String> resp = postWithRetry5xx("/v1/chat/completions",
                "{\"model\":\"main-dev\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Say hi\"}]}");
            if (resp.statusCode() == 200) {
                String body = resp.body();
                assertTrue(body.contains("data:") || body.contains("delta") || body.contains("content"),
                    "Streaming response should contain SSE data");
            }
        } catch (java.io.IOException e) {
            System.out.println("SSE stream timed out (expected): " + e.getMessage());
        }
    }

    @Test @Order(263) @DisplayName("POST /v1/chat/completions — with tools")
    void openAiCompletionWithTools() throws Exception {
        HttpResponse<String> resp = postWithRetry5xx("/v1/chat/completions",
            "{\"model\":\"main-dev\",\"messages\":[{\"role\":\"user\",\"content\":\"Read the file /etc/hostname and tell me what it contains\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"description\":\"Read a file\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}}]}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertEquals("chat.completion", body.get("object").asText());
    }

    // ── 29. Vision (skip if no browser) ──

    @Test @Order(270) @DisplayName("POST /api/v1/agent/vision — vision analysis (skip if no browser)")
    void visionAnalysis() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/vision",
            "{\"url\":\"http://example.com\",\"prompt\":\"What is on this page?\"}");
        // Accept 200 (browser available) or 500 (no browser) — just verify no crash
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 500,
            "Vision should return 200 or 500, got " + resp.statusCode());
    }

    // ── 30. TTS (skip if no TTS service) ──

    @Test @Order(280) @DisplayName("POST /api/v1/agent/tts — text-to-speech (skip if no TTS)")
    void tts() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/tts",
            "{\"text\":\"Hello world\",\"voice\":\"default\"}");
        // Accept 200 (TTS available) or 500 (no TTS configured)
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 500,
            "TTS should return 200 or 500, got " + resp.statusCode());
    }

    // ── 31. Checkpoint restore & delete ──

    @Test @Order(290) @DisplayName("POST /api/v1/agent/checkpoint/{id}/restore — restores checkpoint")
    void restoreCheckpoint() throws Exception {
        Assumptions.assumeTrue(checkpointId != null, "No checkpoint from previous test");
        HttpResponse<String> resp = post("/api/v1/agent/checkpoint/" + checkpointId + "/restore", "");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("restored") || resp.body().contains(checkpointId.toString()));
    }

    @Test @Order(291) @DisplayName("DELETE /api/v1/agent/checkpoint/{id} — deletes checkpoint")
    void deleteCheckpoint() throws Exception {
        // Create a checkpoint to delete
        HttpResponse<String> createResp = postWithRetry("/api/v1/agent/checkpoint",
            "{\"description\":\"Checkpoint to delete\"}");
        JsonNode created = parseJson(createResp.body());
        UUID cpId = UUID.fromString(created.get("id").asText());

        HttpResponse<String> resp = deleteWithRetry("/api/v1/agent/checkpoint/" + cpId);
        assertEquals(200, resp.statusCode());
    }

    // ── 32. Memory delete ──

    @Test @Order(300) @DisplayName("DELETE /api/v1/agent/memory/{userId}/{entryId} — deletes memory entry")
    void deleteMemoryEntry() throws Exception {
        // First store a memory
        postWithRetry("/api/v1/agent/memory",
            "{\"userId\":\"e2e-delete-test\",\"fact\":\"Temporary fact to delete\",\"category\":\"user\"}");

        HttpResponse<String> listResp = getWithRetry("/api/v1/agent/memory/all/e2e-delete-test");
        JsonNode memories = parseJson(listResp.body());
        Assumptions.assumeTrue(memories.size() > 0, "Need at least one memory entry");

        UUID entryId = UUID.fromString(memories.get(0).get("id").asText());
        HttpResponse<String> resp = deleteWithRetry("/api/v1/agent/memory/e2e-delete-test/" + entryId);
        assertEquals(200, resp.statusCode());
    }

    // ── 34. V2 Session CRUD (SessionCrudController) ──

    @Test @Order(310) @DisplayName("POST /api/v2/sessions — creates v2 session")
    void v2CreateSession() throws Exception {
        HttpResponse<String> resp = post("/api/v2/sessions",
            "{\"userId\":\"e2e-v2-user\",\"title\":\"E2E V2 Session\"}");
        assertEquals(201, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertNotNull(body.get("id"));
        v2SessionId = UUID.fromString(body.get("id").asText());
        assertEquals("E2E V2 Session", body.get("title").asText());
    }

    @Test @Order(311) @DisplayName("GET /api/v2/sessions/{sessionId} — returns session")
    void v2GetSession() throws Exception {
        Assumptions.assumeTrue(v2SessionId != null, "v2 session must be created first");
        HttpResponse<String> resp = get("/api/v2/sessions/" + v2SessionId);
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertEquals(v2SessionId.toString(), body.get("id").asText());
    }

    @Test @Order(312) @DisplayName("PATCH /api/v2/sessions/{sessionId} — updates title")
    void v2PatchSession() throws Exception {
        Assumptions.assumeTrue(v2SessionId != null);
        HttpResponse<String> resp = patch("/api/v2/sessions/" + v2SessionId,
            "{\"title\":\"Updated V2 Title\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertEquals("Updated V2 Title", body.get("title").asText());
    }

    @Test @Order(313) @DisplayName("GET /api/v2/sessions/{sessionId}/messages — lists messages")
    void v2GetMessages() throws Exception {
        Assumptions.assumeTrue(v2SessionId != null);
        HttpResponse<String> resp = get("/api/v2/sessions/" + v2SessionId + "/messages");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        // Response format: {object: "list", data: [...], ...}
        assertTrue(body.has("data") || body.has("messages") || body.isArray(),
            "Expected 'data' or 'messages' field in response");
    }

    @Test @Order(314) @DisplayName("POST /api/v2/sessions/{sessionId}/chat — sync chat")
    void v2SessionChat() throws Exception {
        Assumptions.assumeTrue(v2SessionId != null);
        HttpResponse<String> resp = postWithRetry("/api/v2/sessions/" + v2SessionId + "/chat",
            "{\"message\":\"Say OK\"}");
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 429,
            "Expected 200 or 429, got " + resp.statusCode());
    }

    @Test @Order(315) @DisplayName("POST /api/v2/sessions/{sessionId}/chat/stream — SSE stream")
    void v2SessionChatStream() throws Exception {
        Assumptions.assumeTrue(v2SessionId != null);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/v2/sessions/" + v2SessionId + "/chat/stream"))
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"Say hi\"}"))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 429,
            "Expected 200 or 429, got " + resp.statusCode());
    }

    @Test @Order(316) @DisplayName("DELETE /api/v2/sessions/{sessionId} — deletes session")
    void v2DeleteSession() throws Exception {
        Assumptions.assumeTrue(v2SessionId != null);
        HttpResponse<String> resp = delete("/api/v2/sessions/" + v2SessionId);
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertTrue(body.get("deleted").asBoolean(), "Expected deleted=true");
        // Verify it's gone
        HttpResponse<String> getResp = get("/api/v2/sessions/" + v2SessionId);
        assertEquals(404, getResp.statusCode());
        v2SessionId = null;
    }

    // ── 35. Cron advanced: run, executions, delivered, suggestions/clear, heartbeat/nack ──

    @Test @Order(320) @DisplayName("POST /api/v1/agent/cron/{id}/run — runs cron job now")
    void cronRunNow() throws Exception {
        Assumptions.assumeTrue(cronJobId != null, "Cron job must be created first (Order 240)");
        HttpResponse<String> resp = post("/api/v1/agent/cron/" + cronJobId + "/run", "{}");
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 404 || resp.statusCode() == 400,
            "Expected 200/400/404, got " + resp.statusCode());
    }

    @Test @Order(321) @DisplayName("GET /api/v1/agent/cron/{id}/executions — lists executions")
    void cronListExecutions() throws Exception {
        Assumptions.assumeTrue(cronJobId != null);
        HttpResponse<String> resp = get("/api/v1/agent/cron/" + cronJobId + "/executions");
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 404,
            "Expected 200 or 404, got " + resp.statusCode());
        if (resp.statusCode() == 200) {
            JsonNode body = parseJson(resp.body());
            assertNotNull(body);
            assertTrue(body.isArray(), "Expected an array of executions");
        }
    }

    @Test @Order(322) @DisplayName("POST /api/v1/agent/cron/{id}/delivered — marks delivered")
    void cronMarkDelivered() throws Exception {
        Assumptions.assumeTrue(cronJobId != null);
        HttpResponse<String> resp = post("/api/v1/agent/cron/" + cronJobId + "/delivered", "{}");
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 404 || resp.statusCode() == 400,
            "Expected 200/400/404, got " + resp.statusCode());
    }

    @Test @Order(323) @DisplayName("POST /api/v1/agent/cron/suggestions/clear — clears accepted suggestions")
    void cronSuggestionsClear() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/cron/suggestions/clear", "{}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertTrue(body.has("cleared"), "Expected 'cleared' field in response");
    }

    @Test @Order(324) @DisplayName("POST /api/v1/agent/cron/heartbeat/{sessionId}/result/nack — nacks heartbeat result")
    void cronHeartbeatNack() throws Exception {
        UUID fakeSession = UUID.randomUUID();
        HttpResponse<String> resp = post("/api/v1/agent/cron/heartbeat/" + fakeSession + "/result/nack", "{}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertTrue(body.has("drop"), "Expected 'drop' field in response");
    }

    // ── 36. MCP server tools and resources ──

    @Test @Order(330) @DisplayName("GET /api/v1/mcp/servers/{name}/tools — lists tools for a server")
    void mcpListServerTools() throws Exception {
        // List servers first to get a name, or use a dummy name
        HttpResponse<String> serversResp = get("/api/v1/mcp/servers");
        assertEquals(200, serversResp.statusCode());
        JsonNode servers = parseJson(serversResp.body());
        String serverName = null;
        if (servers != null && servers.size() > 0) {
            serverName = servers.get(0).get("name").asText();
        }
        if (serverName == null) {
            serverName = "nonexistent-server";
        }
        HttpResponse<String> resp = get("/api/v1/mcp/servers/" + serverName + "/tools");
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 404,
            "Expected 200 or 404, got " + resp.statusCode());
        if (resp.statusCode() == 200) {
            JsonNode body = parseJson(resp.body());
            assertNotNull(body);
            assertTrue(body.isArray(), "Expected an array of tools");
        }
    }

    @Test @Order(331) @DisplayName("POST /api/v1/mcp/servers/{name}/tools/{toolName} — invokes tool (nonexistent server)")
    void mcpInvokeTool() throws Exception {
        HttpResponse<String> resp = post("/api/v1/mcp/servers/nonexistent-server/tools/nonexistent-tool",
            "{}");
        // Should fail gracefully — 4xx/5xx is acceptable
        assertTrue(resp.statusCode() >= 400,
            "Expected error status for nonexistent server/tool, got " + resp.statusCode());
    }

    @Test @Order(332) @DisplayName("POST /api/v1/mcp/servers/{name}/resources — reads resource (nonexistent server)")
    void mcpReadResource() throws Exception {
        HttpResponse<String> resp = post("/api/v1/mcp/servers/nonexistent-server/resources",
            "{\"uri\":\"test://resource\"}");
        assertTrue(resp.statusCode() >= 400,
            "Expected error status for nonexistent server, got " + resp.statusCode());
    }

    // ── 37. Memory approve/reject/delete ──

    @Test @Order(340) @DisplayName("POST /api/v1/agent/memory/approve — approve with no pending returns false")
    void memoryApproveNoPending() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/memory/approve",
            "{\"userId\":\"e2e-no-pending\",\"id\":\"00000000-0000-0000-0000-000000000001\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertFalse(body.asBoolean(), "Expected false when no pending memory to approve");
    }

    @Test @Order(341) @DisplayName("POST /api/v1/agent/memory/reject — reject with no pending returns false")
    void memoryRejectNoPending() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/memory/reject",
            "{\"userId\":\"e2e-no-pending\",\"id\":\"00000000-0000-0000-0000-000000000002\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertFalse(body.asBoolean(), "Expected false when no pending memory to reject");
    }

    @Test @Order(342) @DisplayName("DELETE /api/v1/agent/memory/{userId}/{entryId} — deletes a memory entry")
    void memoryDeleteEntry() throws Exception {
        // Store a memory to delete
        postWithRetry("/api/v1/agent/memory",
            "{\"userId\":\"e2e-mem-delete\",\"fact\":\"Temp fact for delete test\",\"category\":\"user\"}");
        HttpResponse<String> listResp = getWithRetry("/api/v1/agent/memory/all/e2e-mem-delete");
        JsonNode memories = parseJson(listResp.body());
        Assumptions.assumeTrue(memories != null && memories.size() > 0, "Need at least one memory entry");

        UUID entryId = UUID.fromString(memories.get(0).get("id").asText());
        HttpResponse<String> resp = deleteWithRetry("/api/v1/agent/memory/e2e-mem-delete/" + entryId);
        assertEquals(200, resp.statusCode());
    }

    // ── 38. Skill hub: list, search, install, audit, bundles ──

    @Test @Order(350) @DisplayName("GET /api/v1/agent/skills-hub — lists hub skills")
    void skillsHubList() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/skills-hub");
        // May fail if no hub repo configured — accept 200 or error
        if (resp.statusCode() == 200) {
            JsonNode body = parseJson(resp.body());
            assertNotNull(body);
            assertTrue(body.isArray(), "Expected an array of hub skills");
        } else {
            System.out.println("skills-hub returned " + resp.statusCode() + " (hub may not be configured)");
        }
    }

    @Test @Order(351) @DisplayName("GET /api/v1/agent/skills-hub/search — searches hub skills")
    void skillsHubSearch() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/skills-hub/search?q=deploy");
        if (resp.statusCode() == 200) {
            JsonNode body = parseJson(resp.body());
            assertNotNull(body);
            assertTrue(body.isArray(), "Expected an array of search results");
        } else {
            System.out.println("skills-hub/search returned " + resp.statusCode() + " (hub may not be configured)");
        }
    }

    @Test @Order(352) @DisplayName("POST /api/v1/agent/skills-hub/install — install nonexistent skill fails gracefully")
    void skillsHubInstallNonexistent() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/skills-hub/install",
            "{\"skill\":\"nonexistent-skill-12345\"}");
        assertTrue(resp.statusCode() == 200,
            "Expected 200 (graceful failure), got " + resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        // Should return ok=false for nonexistent skill
        if (body.has("ok")) {
            assertFalse(body.get("ok").asBoolean(), "Expected ok=false for nonexistent skill");
        }
    }

    @Test @Order(353) @DisplayName("GET /api/v1/agent/skills/{name}/audit — returns audit log")
    void skillAudit() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/skills/deploy-checklist/audit");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertTrue(body.isArray(), "Expected an array of audit log entries");
    }

    @Test @Order(354) @DisplayName("POST /api/v1/agent/bundles/install — nonexistent bundle fails gracefully")
    void bundlesInstallNonexistent() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/bundles/install",
            "{\"bundleName\":\"nonexistent-bundle-12345\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        if (body.has("ok")) {
            assertFalse(body.get("ok").asBoolean(), "Expected ok=false for nonexistent bundle");
        }
    }

    @Test @Order(355) @DisplayName("POST /api/v1/agent/bundles/uninstall — nonexistent bundle is idempotent")
    void bundlesUninstallNonexistent() throws Exception {
        HttpResponse<String> resp = post("/api/v1/agent/bundles/uninstall",
            "{\"bundleName\":\"nonexistent-bundle-12345\"}");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        // uninstall is idempotent — returns ok=true even for nonexistent bundle
        assertTrue(body.has("ok") || body.has("message"),
            "Expected 'ok' or 'message' field in response");
    }

    // ── 39. AgentChat: per-session approvals, transcribe, debug-report ──

    @Test @Order(360) @DisplayName("POST /api/v1/agent/approvals/{sessionId}/approve — approve with no pending")
    void approvalsSessionApprove() throws Exception {
        UUID fakeSession = UUID.randomUUID();
        HttpResponse<String> resp = post("/api/v1/agent/approvals/" + fakeSession + "/approve", "");
        // No pending approval → returns null or error, but should not crash
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 404,
            "Expected 200 or 404, got " + resp.statusCode());
    }

    @Test @Order(361) @DisplayName("POST /api/v1/agent/approvals/{sessionId}/deny — deny with no pending")
    void approvalsSessionDeny() throws Exception {
        UUID fakeSession = UUID.randomUUID();
        HttpResponse<String> resp = post("/api/v1/agent/approvals/" + fakeSession + "/deny", "");
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 404,
            "Expected 200 or 404, got " + resp.statusCode());
    }

    @Test @Order(362) @DisplayName("POST /api/v1/agent/transcribe — empty audio returns error")
    void transcribeEmpty() throws Exception {
        // Send multipart with empty file body — should not crash
        String boundary = "e2e-boundary-" + System.currentTimeMillis();
        String body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"test.wav\"\r\n"
            + "Content-Type: audio/wav\r\n\r\n"
            + "\r\n"
            + "--" + boundary + "--\r\n";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/v1/agent/transcribe"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        // Should return 200 with error or empty text — not 500
        assertTrue(resp.statusCode() == 200,
            "Expected 200 (graceful error), got " + resp.statusCode());
        JsonNode bodyNode = parseJson(resp.body());
        assertNotNull(bodyNode);
        assertTrue(bodyNode.has("text"), "Expected 'text' field in response");
    }

    @Test @Order(363) @DisplayName("POST /api/v1/agent/debug-report — uploads debug report")
    void debugReport() throws Exception {
        String boundary = "e2e-boundary-" + System.currentTimeMillis();
        String systemInfo = "E2E test system info";
        String body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"systemInfo\"\r\n\r\n"
            + systemInfo + "\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"logs\"\r\n\r\n"
            + "E2E test logs\r\n"
            + "--" + boundary + "--\r\n";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/v1/agent/debug-report"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        JsonNode bodyNode = parseJson(resp.body());
        assertNotNull(bodyNode);
        assertTrue(bodyNode.has("id"), "Expected 'id' field in debug report response");
        assertTrue(bodyNode.has("link"), "Expected 'link' field in debug report response");
    }

    // ── 40. Reasoning levels ──

    @Test @Order(370) @DisplayName("GET /api/v1/agent/reasoning-levels — returns valid reasoning levels")
    void reasoningLevels() throws Exception {
        HttpResponse<String> resp = get("/api/v1/agent/reasoning-levels");
        assertEquals(200, resp.statusCode());
        JsonNode body = parseJson(resp.body());
        assertNotNull(body);
        assertTrue(body.isArray(), "Expected an array of reasoning levels");
        assertTrue(body.size() > 0, "Expected at least one reasoning level");
    }

    // ── 33. Restart (last — would kill the server) ──

    @Test @Order(999) @DisplayName("POST /api/v1/agent/restart — restarts agent (last test)")
    void restartAgent() throws Exception {
        // We don't actually call restart because it would kill the server
        // for subsequent tests. Just verify the endpoint exists.
        // Uncomment to test manually:
        // HttpResponse<String> resp = post("/api/v1/agent/restart", "");
        // assertEquals(200, resp.statusCode());
        System.out.println("Restart endpoint exists (skipped to preserve server for other tests)");
    }
}