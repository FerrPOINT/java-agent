package com.azhukov.agent.tools.workflow;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.ProfileEnvStore;
import com.azhukov.agent.service.ProfileServiceAccountVerifier;
import com.azhukov.agent.service.ProfileExecutionConfiguration;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

@AgentTool(
    name = ProjectWorkflowTool.NAME,
    description = "Use the Workflow assignment bound to this Fleet run. Step reads the current instructions or "
        + "submits a report with the same operation key on retry and the expected version from Step. "
        + "History reads the report history or the receipt for an operation key.",
    toolset = ProjectWorkflowTool.NAME
)
@Component
public class ProjectWorkflowTool implements ToolHandler {
    public static final String NAME = "project_workflow";
    public static final String TASK_METADATA = "fleet_workflow_task";
    public static final int REQUEST_TIMEOUT_SECONDS = 1500;
    private static final String PROTOCOL = "configured-cli-v1";
    private static final int STEP_LIMIT = 1024 * 1024;
    private static final int HISTORY_RESPONSE_LIMIT = 64 * 1024 * 1024;

    private final ProfileEnvStore profileEnv;
    private final ObjectMapper json;
    private final URI base;
    private final ProfileServiceAccountVerifier serviceAccounts;
    private final HttpClient http = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public ProjectWorkflowTool(ProfileEnvStore profileEnv, ObjectMapper json,
                               @Value("${agent.workflow.base-url:}") String baseUrl,
                               ProfileServiceAccountVerifier serviceAccounts) {
        this.profileEnv = profileEnv;
        this.json = json;
        this.base = baseUrl.isBlank() ? null : workflowBase(baseUrl);
        this.serviceAccounts = serviceAccounts;
    }

    public boolean configured(String profile) {
        try {
            return base != null && credentials(profile) != null;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    public WorkflowIdentity verifyIdentity(String profile) {
        try {
            var credentials = credentials(profile);
            if (base == null || credentials == null) {
                throw new IllegalStateException("Workflow identity is unavailable");
            }
            String subject = serviceAccounts.verify(credentials.token(),
                Set.of("project-workflow:read", "project-workflow:write"));
            return new WorkflowIdentity(credentials.namespace(), subject, base.toString());
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Workflow identity is unavailable");
        }
    }

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        if (base == null || session == null || "default".equals(session.profile())
            || !session.userId().equals("fleet-profile:" + session.profile())
            || ProfileExecutionConfiguration.current(session.profile()) == null
            || !validKey(session.getMetadata(TASK_METADATA))) {
            return ToolResult.fail("Workflow requires a Fleet task bound to this Java profile");
        }
        try {
            var args = json.readerFor(WorkflowArgs.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .<WorkflowArgs>readValue(arguments);
            var credentials = credentials(session.profile());
            if (credentials == null) {
                return ToolResult.fail("Workflow credentials are missing for this profile");
            }
            String task = session.getMetadata(TASK_METADATA);
            var request = switch (args.action() == null ? "" : args.action()) {
                case "step" -> step(args, task, credentials);
                case "history" -> history(args, task, credentials);
                default -> throw new IllegalArgumentException();
            };
            int maximum = "step".equals(args.action()) ? STEP_LIMIT : HISTORY_RESPONSE_LIMIT;
            var response = http.send(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), maximum));
            if (response.statusCode() != 200) {
                return ToolResult.fail("Workflow refused the operation (HTTP " + response.statusCode() + ")");
            }
            var body = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
            if (body == null || !body.path("ok").isBoolean() || !body.path("ok").booleanValue()
                || !body.path("exit_code").isInt() || body.path("exit_code").intValue() != 0
                || !body.path("result").isObject()) {
                return ToolResult.fail("Workflow did not return a configured assignment result");
            }
            var result = body.path("result");
            if (!PROTOCOL.equals(result.path("interaction_protocol").asText())
                || !task.equals(result.path("task").asText())
                || !result.path("namespace_id").isIntegralNumber()
                || result.path("namespace_id").longValue() != credentials.namespace()) {
                return ToolResult.fail("Workflow returned a different assignment");
            }
            String content = json.writeValueAsString(result);
            return content.contains(credentials.token())
                ? ToolResult.fail("Workflow result contains a protected credential")
                : ToolResult.ok(content);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return ToolResult.fail("Invalid Workflow arguments or response");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("Workflow operation interrupted. Recover a report using its original operation key");
        } catch (IOException e) {
            return ToolResult.fail("Workflow connection failed. Recover a report using its original operation key");
        }
    }

    private HttpRequest step(WorkflowArgs args, String task, Credentials credentials) throws IOException {
        if (args.n() != null || args.beforeHistoryId() != null) {
            throw new IllegalArgumentException();
        }
        var body = json.createObjectNode().put("interaction_protocol", PROTOCOL)
            .put("task", task).put("namespace_id", credentials.namespace());
        if (args.report() != null) {
            var report = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(args.report());
            if (report == null || !report.isObject() || !validKey(args.operationKey())
                || args.expectedVersion() == null || args.expectedVersion() < 1) {
                throw new IllegalArgumentException();
            }
            body.put("report", args.report()).put("operation_key", args.operationKey())
                .put("expected_version", args.expectedVersion());
            if (args.requirementsRevision() != null || args.requirementsSha256() != null) {
                if (args.requirementsRevision() == null || args.requirementsRevision() < 1
                    || !ProfileExecutionConfiguration.validRevision(args.requirementsSha256())) {
                    throw new IllegalArgumentException();
                }
                body.put("requirements_revision", args.requirementsRevision())
                    .put("requirements_sha256", args.requirementsSha256());
            }
        } else if (args.operationKey() != null || args.expectedVersion() != null
            || args.requirementsRevision() != null || args.requirementsSha256() != null) {
            throw new IllegalArgumentException();
        }
        return request("step", credentials).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
    }

    private HttpRequest history(WorkflowArgs args, String task, Credentials credentials) {
        int n = args.n() == null ? 200 : args.n();
        if (n < 1 || n > 200 || args.report() != null || args.expectedVersion() != null
            || args.requirementsRevision() != null || args.requirementsSha256() != null
            || args.operationKey() != null && !validKey(args.operationKey())
            || args.beforeHistoryId() != null && (args.beforeHistoryId() < 1 || args.operationKey() != null)) {
            throw new IllegalArgumentException();
        }
        String endpoint = "history?interaction_protocol=" + PROTOCOL + "&task=" + formValue(task)
            + "&namespace_id=" + credentials.namespace() + "&n=" + n;
        if (args.operationKey() != null) {
            endpoint += "&operation_key=" + formValue(args.operationKey());
        }
        if (args.beforeHistoryId() != null) {
            endpoint += "&before_history_id=" + args.beforeHistoryId();
        }
        return request(endpoint, credentials).GET().build();
    }

    private HttpRequest.Builder request(String route, Credentials credentials) {
        return HttpRequest.newBuilder(base.resolve("internal/runtime/" + route))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + credentials.token());
    }

    private Credentials credentials(String profile) throws IOException {
        var env = profileEnv.readAll(profile);
        String token = env.get("PROJECT_WORKFLOW_API_TOKEN");
        String namespace = env.get("PROJECT_WORKFLOW_NAMESPACE_ID");
        if (token == null || !token.matches("[!-~]{1,512}")
            || namespace == null || !namespace.matches("[1-9][0-9]{0,18}")) {
            return null;
        }
        return new Credentials(token, Long.parseLong(namespace));
    }

    private static URI workflowBase(String value) {
        URI uri = URI.create(value.endsWith("/") ? value : value + "/");
        if (!ProfileServiceAccountVerifier.trustedHttpEndpoint(uri)) {
            throw new IllegalArgumentException("Invalid Workflow base URL");
        }
        return uri;
    }

    private static String formValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static boolean validKey(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
            && value.equals(value.strip()) && value.chars().noneMatch(Character::isISOControl);
    }

    private record Credentials(String token, long namespace) {}

    public record WorkflowIdentity(long namespaceId, String machineSubject, String workflowBaseUrl) {}

    public record WorkflowArgs(
        @ToolParam(description = "Read or report the current step, or read history", enumValues = {"step", "history"})
        String action,
        @ToolParam(description = "Workflow phase report as a JSON string", required = false)
        String report,
        @JsonProperty("operation_key")
        @ToolParam(description = "Stable report operation key, also usable as a receipt lookup", required = false)
        String operationKey,
        @JsonProperty("expected_version")
        @ToolParam(description = "Version returned by the current step", required = false)
        Long expectedVersion,
        @JsonProperty("requirements_revision")
        @ToolParam(description = "Confirmed requirements revision for the final PM report", required = false)
        Long requirementsRevision,
        @JsonProperty("requirements_sha256")
        @ToolParam(description = "Confirmed requirements hash for the final PM report", required = false)
        String requirementsSha256,
        @ToolParam(description = "Number of history entries, up to 200", required = false)
        Integer n,
        @JsonProperty("before_history_id")
        @ToolParam(description = "History cursor returned in next_before", required = false)
        Long beforeHistoryId
    ) {}
}
