package com.azhukov.agent.tools.tracker;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.ProfileEnvStore;
import com.azhukov.agent.service.ProfileExecutionConfiguration;
import com.azhukov.agent.service.ProfileServiceAccountVerifier;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.tools.workflow.ProjectWorkflowTool;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

@AgentTool(
    name = TrackerTool.NAME,
    description = "Use the Tracker assignment in this Fleet run. Read context, lifecycle, current requirements "
        + "or discussion. Follow discussion next_after and snapshot together. Start the assigned stage with start, "
        + "which uses a stable key. Use start_receipt to read the original receipt if the outcome is unknown.",
    toolset = TrackerTool.NAME
)
@Component
public class TrackerTool implements ToolHandler {
    public static final String NAME = "tracker";
    private static final Set<String> SCOPES = Set.of("task-tracker:read", "task-tracker:write");
    private static final int RESPONSE_LIMIT = 2 * 1024 * 1024;

    private final ProfileEnvStore profileEnv;
    private final ObjectMapper json;
    private final ProfileServiceAccountVerifier serviceAccounts;
    private final URI origin;
    private final HttpClient http = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public TrackerTool(ProfileEnvStore profileEnv, ObjectMapper json, ProfileServiceAccountVerifier serviceAccounts,
            @Value("${agent.tracker.base-url:}") String baseUrl) {
        this.profileEnv = profileEnv;
        this.json = json;
        this.serviceAccounts = serviceAccounts;
        this.origin = baseUrl.isBlank() ? null : URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (origin != null && (!ProfileServiceAccountVerifier.trustedHttpEndpoint(origin)
            || !"/".equals(origin.getRawPath()))) {
            throw new IllegalArgumentException("Invalid Tracker origin");
        }
    }

    public boolean configured(String profile) {
        try {
            return origin != null && credential(profile) != null;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    public String verifyIdentity(String profile, String expectedSubject) {
        try {
            String token = credential(profile);
            if (origin == null || token == null || !expectedSubject.equals(serviceAccounts.verify(token, SCOPES))) {
                throw new IllegalStateException("Tracker identity is unavailable");
            }
            return origin.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Tracker identity is unavailable");
        }
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        if (origin == null || session == null || "default".equals(session.profile())
            || !session.userId().equals("fleet-profile:" + session.profile())
            || ProfileExecutionConfiguration.current(session.profile()) == null
            || session.getMetadata(TrackerRunScope.METADATA) == null
            || !ProjectWorkflowTool.validKey(session.getMetadata(ProjectWorkflowTool.TASK_METADATA))) {
            return ToolResult.fail("Tracker requires an assignment bound to this Java profile");
        }
        try {
            var scope = decode(TrackerRunScope.class, session.getMetadata(TrackerRunScope.METADATA));
            var args = decode(TrackerArgs.class, arguments);
            String token = credential(session.profile());
            if (token == null || !scope.machineSubject().equals(serviceAccounts.verify(token, SCOPES))) {
                return ToolResult.fail("Tracker credential does not belong to this assignment");
            }
            String route = "api/v1/issues/" + scope.taskId() + "/sdlc/";
            String key = "java-stage-start:" + scope.executionId();
            if (!"discussion".equals(args.action()) && (args.after() != null || args.snapshot() != null)) {
                throw new IllegalArgumentException();
            }
            if ("start_receipt".equals(args.action())) {
                return startReceipt(read(route + "stage-starts/operations/" + key, token), scope, key, token);
            }
            var context = read(route + "context", token);
            if (!scope.matchesContext(context, session.getMetadata(ProjectWorkflowTool.TASK_METADATA))) {
                return ToolResult.fail("Tracker returned a different task or current assignment");
            }
            return switch (args.action()) {
                case "context" -> result(context, token);
                case "lifecycle" -> result(lifecycle(route, token, scope), token);
                case "requirements" -> result(read(route + "requirements", token), token);
                case "discussion" -> discussion(route, token, scope, args);
                case "start" -> start(route, token, scope, context, key);
                default -> ToolResult.fail("Unsupported Tracker operation");
            };
        } catch (TrackerRefused e) {
            return ToolResult.fail("Tracker refused the operation (HTTP " + e.status + ")");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return ToolResult.fail("Invalid Tracker arguments or response");
        } catch (IllegalStateException e) {
            return ToolResult.fail("Tracker identity or assignment is unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("Tracker operation interrupted. Read the original start receipt before retrying");
        } catch (IOException e) {
            return ToolResult.fail("Tracker connection failed. Read the original start receipt before retrying");
        }
    }

    private ToolResult discussion(String route, String token, TrackerRunScope scope, TrackerArgs args)
            throws IOException, InterruptedException {
        String query = "discussion?limit=25";
        if (args.after() != null || args.snapshot() != null) {
            if (!TrackerRunScope.canonicalId(args.after()) || !TrackerRunScope.canonicalId(args.snapshot())) {
                throw new IllegalArgumentException();
            }
            query += "&after=" + args.after() + "&snapshot=" + args.snapshot();
        }
        JsonNode page = read(route + query, token);
        if (!scope.taskId().equals(page.path("task_id").asText())
            || !scope.taskId().equals(page.path("source_task_id").asText())
            || !scope.projectId().equals(page.path("project_id").asText())) {
            return ToolResult.fail("Tracker returned a different discussion");
        }
        return result(page, token);
    }

    private ToolResult start(String route, String token, TrackerRunScope scope, JsonNode context, String key)
            throws IOException, InterruptedException {
        String collection = route + "stage-starts";
        try {
            return startReceipt(read(collection + "/operations/" + key, token), scope, key, token);
        } catch (TrackerRefused e) {
            if (e.status != 404) {
                throw e;
            }
        }
        String status = context.path("status").asText();
        if (!"Ready".equals(status) && !"InProgress".equals(status)) {
            return ToolResult.fail("Tracker stage cannot be started");
        }
        ObjectNode fence = json.createObjectNode().put("assignment_id", scope.assignmentId())
            .put("execution_id", scope.executionId());
        if ("Draft".equals(context.path("stage").asText())) {
            fence.put("kind", "pm").put("agent_id", scope.agentId()).put("assignment_version", scope.assignmentVersion());
        } else {
            JsonNode receipt = lifecycle(route, token, scope).path("receipt");
            JsonNode assignment = receipt.path("assignment");
            JsonNode lease = receipt.path("lease");
            String leaseId = lease.path("lease_id").asText();
            if (!TrackerRunScope.canonicalId(leaseId) || !leaseId.equals(assignment.path("lease_id").asText())
                || !scope.machineSubject().equals(lease.path("holder_subject").asText())) {
                throw new IllegalStateException();
            }
            fence.put("kind", "delivery").put("lease_id", leaseId)
                .put("fencing_token", positive(assignment.path("fencing_token")))
                .put("expected_lease_version", positive(lease.path("version")));
        }
        ObjectNode body = json.createObjectNode().put("idempotency_key", key);
        body.set("fence", fence);
        JsonNode response = send(request(collection, token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build());
        return startReceipt(response, scope, key, token);
    }

    private JsonNode lifecycle(String route, String token, TrackerRunScope scope) throws IOException, InterruptedException {
        JsonNode lifecycle = read(route + "lifecycle", token);
        JsonNode receipt = lifecycle.path("receipt");
        if (!scope.taskId().equals(receipt.path("task").path("task_id").asText())
            || !scope.matchesAssignment(receipt.path("assignment"))) {
            throw new IllegalStateException();
        }
        return lifecycle;
    }

    private ToolResult startReceipt(JsonNode value, TrackerRunScope scope, String key, String token) throws IOException {
        if (!scope.taskId().equals(value.path("task_id").asText())
            || !scope.executionId().equals(value.path("execution_id").asText())
            || !key.equals(value.path("idempotency_key").asText())
            || !"InProgress".equals(value.path("status").asText())) {
            return ToolResult.fail("Tracker returned a different start receipt");
        }
        return result(value, token);
    }

    private ToolResult result(JsonNode value, String token) throws IOException {
        String content = json.writeValueAsString(value);
        return content.contains(token)
            ? ToolResult.fail("Tracker response contains a protected credential") : ToolResult.ok(content);
    }

    private <T> T decode(Class<T> type, String body) throws IOException {
        T value = json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        if (value == null || (value instanceof TrackerArgs args && args.action() == null)) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private static long positive(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1) {
            throw new IllegalStateException();
        }
        return node.longValue();
    }

    private String credential(String profile) throws IOException {
        String token = profileEnv.readAll(profile).get("TASKTRACKER_TOKEN");
        return token != null && token.matches("(?:pdlc_pat_|sdlc_pat_)[a-zA-Z0-9_-]{1,498}") ? token : null;
    }

    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json").header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache, no-store").header("Authorization", "Bearer " + token);
    }

    private JsonNode read(String path, String token) throws IOException, InterruptedException {
        return send(request(path, token).GET().build());
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        var response = http.send(request,
            HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), RESPONSE_LIMIT));
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new TrackerRefused(response.statusCode());
        }
        if (!"identity".equals(response.headers().firstValue("Content-Encoding").orElse("identity"))) {
            throw new IOException("Unexpected Tracker content encoding");
        }
        var value = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(response.body());
        if (value == null || !value.isObject()) {
            throw new IOException("Invalid Tracker response");
        }
        return value;
    }

    private static class TrackerRefused extends IOException {
        private final int status;

        TrackerRefused(int status) {
            this.status = status;
        }
    }

    public record TrackerArgs(
        @ToolParam(description = "Assigned Tracker operation",
            enumValues = {"context", "lifecycle", "requirements", "discussion", "start", "start_receipt"})
        String action,
        @ToolParam(description = "Discussion next_after UUID for the next page", required = false)
        String after,
        @JsonProperty("snapshot")
        @ToolParam(description = "Original discussion snapshot UUID on later pages", required = false)
        String snapshot
    ) {}
}
