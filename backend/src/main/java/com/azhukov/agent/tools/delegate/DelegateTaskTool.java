package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@AgentTool(
    name = "delegate_task",
    description = "Spawn a focused sub-agent to work on a sub-task. Use for parallel work or isolated reasoning. Max recursion depth is 3.",
    toolset = "delegation"
)
@Component
public class DelegateTaskTool implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(DelegateTaskTool.class);
    private static final int MAX_DEPTH = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    protected DelegateTaskTool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public DelegateTaskTool() {
        this(createDefaultHttpClient());
    }

    protected HttpClient createHttpClient() {
        return createDefaultHttpClient();
    }

    private static HttpClient createDefaultHttpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(120))
            .build();
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        DelegateArgs args = ToolHandler.parseJson(arguments, DelegateArgs.class);
        if (args.goal() == null || args.goal().isBlank()) {
            return ToolResult.fail("goal is required");
        }

        String depth = session.metadata().getOrDefault("delegation_depth", "0");
        int currentDepth = parseInt(depth, 0);
        if (currentDepth >= MAX_DEPTH) {
            return ToolResult.fail("Maximum delegation depth (" + MAX_DEPTH + ") reached");
        }

        try {
            String result = runSubAgent(args.goal(), session, currentDepth + 1, args.timeoutSeconds());
            return ToolResult.ok(result);
        } catch (Exception e) {
            log.warn("delegate_task failed: {}", e.getMessage(), e);
            return ToolResult.fail("Delegation failed: " + e.getMessage());
        }
    }

    private String runSubAgent(String goal, Session parentSession, int depth, int timeoutSeconds) throws Exception {
        int timeout = timeoutSeconds > 0 ? timeoutSeconds : 1800;
        String baseUrl = System.getProperty("agent.server.base-url", "http://localhost:8090");
        String body = MAPPER.writeValueAsString(new ChatRequest(goal, parentSession.userId(), depth));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v1/agent/delegate"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeout))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return "Sub-agent HTTP " + response.statusCode() + ": " + response.body();
        }
        JsonNode node = MAPPER.readTree(response.body());
        JsonNode content = node.get("content");
        return content != null ? content.asText() : response.body();
    }

    private static int parseInt(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public record DelegateArgs(
        @ToolParam(description = "concise task description for the sub-agent") String goal,
        @ToolParam(description = "role: leaf (default) or orchestrator", required = false) String role,
        @ToolParam(description = "timeout in seconds", required = false) int timeoutSeconds
    ) {}

    public record ChatRequest(String message, String userId, int delegationDepth) {}
}
