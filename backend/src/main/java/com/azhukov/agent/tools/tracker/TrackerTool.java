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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

@AgentTool(
    name = TrackerTool.NAME,
    description = "Use the Tracker assignment in this Fleet run. Read context, lifecycle, current requirements "
        + "or discussion. Follow discussion next_after and snapshot together. Start the assigned stage with start, "
        + "which uses a stable key. Use comment for a stage comment, propose_result for a delivery result, "
        + "or complete_draft for PM completion after an accepted Workflow report. Use the corresponding "
        + "receipt action and original key after an unknown write outcome.",
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
            Mutation mutation = Mutation.forAction(args.action());
            if (mutation == null && (args.operationKey() != null || args.document() != null)) {
                throw new IllegalArgumentException();
            }
            if (!"discussion".equals(args.action()) && (args.after() != null || args.snapshot() != null)) {
                throw new IllegalArgumentException();
            }
            if ("start_receipt".equals(args.action())) {
                return startReceipt(read(route + "stage-starts/operations/" + key, token), scope, key, token);
            }
            String operationKey = args.operationKey();
            if (mutation == Mutation.PROPOSE_RESULT) {
                String expected = "delivery-result:" + scope.assignmentId();
                if (operationKey != null && !expected.equals(operationKey)) {
                    throw new IllegalArgumentException();
                }
                operationKey = expected;
            }
            if (mutation != null) {
                requireOperationKey(operationKey);
                if (args.action().endsWith("_receipt")) {
                    if (args.document() != null) {
                        throw new IllegalArgumentException();
                    }
                    return readReceipt(mutation, route, token, scope, operationKey,
                        session.getMetadata(ProjectWorkflowTool.TASK_METADATA));
                }
                requireDocument(mutation, args.document());
            }
            var context = read(route + "context", token);
            if (!scope.matchesContext(context, session.getMetadata(ProjectWorkflowTool.TASK_METADATA))) {
                return ToolResult.fail("Tracker returned a different task or current assignment");
            }
            if (mutation != null) {
                return write(mutation, route, token, scope, operationKey, args.document(), context, session);
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
            return ToolResult.fail("Tracker operation interrupted. Read the original receipt before retrying");
        } catch (IOException e) {
            return ToolResult.fail("Tracker connection failed. Read the original receipt before retrying");
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
        ObjectNode fence = "Draft".equals(context.path("stage").asText())
            ? machineFence(scope).put("kind", "pm")
            : deliveryFence(route, token, scope).put("kind", "delivery");
        ObjectNode body = json.createObjectNode().put("idempotency_key", key);
        body.set("fence", fence);
        return startReceipt(post(collection, token, body), scope, key, token);
    }

    private ObjectNode machineFence(TrackerRunScope scope) {
        return json.createObjectNode().put("assignment_id", scope.assignmentId()).put("execution_id", scope.executionId())
            .put("agent_id", scope.agentId()).put("assignment_version", scope.assignmentVersion());
    }

    private boolean matchesMachineFence(TrackerRunScope scope, JsonNode fence) {
        return scope.assignmentId().equals(fence.path("assignment_id").asText())
            && scope.executionId().equals(fence.path("execution_id").asText())
            && scope.agentId().equals(fence.path("agent_id").asText())
            && fence.path("assignment_version").isIntegralNumber()
            && fence.path("assignment_version").canConvertToLong()
            && scope.assignmentVersion() == fence.path("assignment_version").longValue();
    }

    private ObjectNode deliveryFence(String route, String token, TrackerRunScope scope)
            throws IOException, InterruptedException {
        JsonNode receipt = lifecycle(route, token, scope).path("receipt");
        JsonNode assignment = receipt.path("assignment");
        JsonNode lease = receipt.path("lease");
        String leaseId = lease.path("lease_id").asText();
        if (!TrackerRunScope.canonicalId(leaseId) || !leaseId.equals(assignment.path("lease_id").asText())
            || !scope.machineSubject().equals(lease.path("holder_subject").asText())) {
            throw new IllegalStateException();
        }
        return json.createObjectNode().put("assignment_id", scope.assignmentId()).put("execution_id", scope.executionId())
            .put("lease_id", leaseId).put("fencing_token", positive(assignment.path("fencing_token")))
            .put("expected_lease_version", positive(lease.path("version")));
    }

    private ToolResult write(Mutation operation, String route, String token, TrackerRunScope scope,
                             String key, JsonNode document, JsonNode context, Session session)
            throws IOException, InterruptedException {
        ObjectNode body = ((ObjectNode) document).deepCopy();
        String stage = context.path("stage").asText();
        boolean draft = "Draft".equals(stage);
        if ((operation == Mutation.COMPLETE_DRAFT && !draft) || (operation == Mutation.PROPOSE_RESULT && draft)
            || !"InProgress".equals(context.path("status").asText())) {
            return ToolResult.fail("Tracker operation requires the current InProgress stage");
        }
        if (operation == Mutation.COMPLETE_DRAFT) {
            String hash = context.path("content_hash").asText();
            if (!sha256(hash)) {
                throw new IllegalStateException("Current requirements hash is absent");
            }
            String namespace = profileEnv.readAll(session.profile()).get("PROJECT_WORKFLOW_NAMESPACE_ID");
            if (namespace == null || !namespace.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalStateException("Profile Workflow namespace is absent");
            }
            body.put("interaction_protocol", "configured-cli-v1")
                .put("namespace_id", Long.parseLong(namespace))
                .put("requirement_revision", positive(context.path("requirement_revision")))
                .put("content_hash", hash);
        }
        body.put("idempotency_key", key);
        body.set("fence", operation == Mutation.PROPOSE_RESULT
            ? deliveryFence(route, token, scope) : machineFence(scope));
        JsonNode receipt = post(route + operation.collection, token, body);
        if (operation == Mutation.COMMENT
            && !hashText(body.path("body").asText()).equals(receipt.path("body_sha256").asText())) {
            return ToolResult.fail("Tracker comment receipt differs from the submitted text");
        }
        return mutationReceipt(operation, receipt, scope, key,
            session.getMetadata(ProjectWorkflowTool.TASK_METADATA), token);
    }

    private ToolResult readReceipt(Mutation operation, String route, String token, TrackerRunScope scope,
                                   String key, String workflowTask) throws IOException, InterruptedException {
        JsonNode value = read(route + operation.collection + "/operations/" + key, token);
        if (operation == Mutation.COMPLETE_DRAFT) {
            if (!key.equals(value.path("idempotency_key").asText()) || !sha256(value.path("request_sha256").asText())) {
                return ToolResult.fail("Tracker returned a different completion operation");
            }
            value = value.path("result");
        }
        return mutationReceipt(operation, value, scope, key, workflowTask, token);
    }

    private ToolResult mutationReceipt(Mutation operation, JsonNode value, TrackerRunScope scope, String key,
                                       String workflowTask, String token) throws IOException {
        if (!scope.taskId().equals(value.path("task_id").asText())) {
            return ToolResult.fail("Tracker returned a different task receipt");
        }
        boolean matching = switch (operation) {
            case COMMENT -> scope.machineSubject().equals(value.path("author_subject").asText())
                && scope.agentId().equals(value.path("agent_id").asText())
                && matchesMachineFence(scope, value.path("fence"))
                && TrackerRunScope.canonicalId(value.path("comment_id").asText()) && sha256(value.path("body_sha256").asText());
            case PROPOSE_RESULT -> scope.assignmentId().equals(value.path("assignment_id").asText())
                && scope.executionId().equals(value.path("execution_id").asText())
                && scope.agentId().equals(value.path("agent_id").asText())
                && scope.machineSubject().equals(value.path("producer_subject").asText())
                && TrackerRunScope.canonicalId(value.path("proposal_id").asText()) && sha256(value.path("output_sha256").asText());
            case COMPLETE_DRAFT -> scope.projectId().equals(value.path("project_id").asText())
                && scope.trackerInstanceId().equals(value.path("tracker_instance_id").asText())
                && matchesMachineFence(scope, value.path("fence"))
                && scope.machineSubject().equals(value.path("workflow_receipt").path("machine_subject").asText())
                && workflowTask.equals(value.path("workflow_receipt").path("task").asText())
                && "configured-cli-v1".equals(value.path("interaction_protocol").asText())
                && "Draft".equals(value.path("stage").asText())
                && "Completed".equals(value.path("status").asText())
                && key.equals(value.path("idempotency_key").asText())
                && TrackerRunScope.canonicalId(value.path("completion_id").asText());
        };
        return matching ? result(value, token) : ToolResult.fail("Tracker receipt differs from the assigned operation");
    }

    private static void requireDocument(Mutation operation, JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException();
        }
        boolean valid = switch (operation) {
            case COMMENT -> value.size() == 1 && value.path("body").isTextual() && !value.path("body").asText().isBlank();
            case PROPOSE_RESULT -> value.size() == 2 && value.path("workflow_receipt").isObject() && value.path("result").isObject();
            case COMPLETE_DRAFT -> value.size() == 3
                && TrackerRunScope.canonicalId(value.path("workflow_execution_ref").asText())
                && validOperationKey(value.path("workflow_operation_key").asText())
                && value.path("workflow_result_version").isIntegralNumber()
                && value.path("workflow_result_version").canConvertToLong()
                && value.path("workflow_result_version").longValue() >= 2;
        };
        if (!valid) {
            throw new IllegalArgumentException();
        }
    }

    private static boolean validOperationKey(String key) {
        return key != null && key.matches("[A-Za-z0-9][A-Za-z0-9_:-]{0,127}");
    }

    private static void requireOperationKey(String key) {
        if (!validOperationKey(key)) {
            throw new IllegalArgumentException();
        }
    }

    private static boolean sha256(String value) {
        return value.matches("[a-f0-9]{64}");
    }

    private static String hashText(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
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

    private JsonNode post(String path, String token, ObjectNode body) throws IOException, InterruptedException {
        return send(request(path, token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build());
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

    private enum Mutation {
        COMMENT("discussion"), PROPOSE_RESULT("delivery-proposals"), COMPLETE_DRAFT("pm-draft-completion");

        private final String collection;

        Mutation(String collection) {
            this.collection = collection;
        }

        private static Mutation forAction(String action) {
            return switch (action) {
                case "comment", "comment_receipt" -> COMMENT;
                case "propose_result", "result_receipt" -> PROPOSE_RESULT;
                case "complete_draft", "completion_receipt" -> COMPLETE_DRAFT;
                default -> null;
            };
        }
    }

    public record TrackerArgs(
        @ToolParam(description = "Assigned Tracker operation",
            enumValues = {"context", "lifecycle", "requirements", "discussion", "start", "start_receipt",
                "comment", "comment_receipt", "propose_result", "result_receipt", "complete_draft", "completion_receipt"})
        String action,
        @ToolParam(description = "Discussion next_after UUID for the next page", required = false)
        String after,
        @JsonProperty("snapshot")
        @ToolParam(description = "Original discussion snapshot UUID on later pages", required = false)
        String snapshot,
        @JsonProperty("operation_key")
        @ToolParam(description = "Original key for comment or PM completion. Result uses its assignment key.",
            required = false)
        String operationKey,
        @ToolParam(description = "Tracker document. comment: body. propose_result: workflow_receipt and result. "
            + "complete_draft: workflow_execution_ref, workflow_operation_key and workflow_result_version.",
            required = false)
        JsonNode document
    ) {}
}
