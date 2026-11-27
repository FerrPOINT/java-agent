package com.azhukov.agent.tools.forge;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.ProfileEnvStore;
import com.azhukov.agent.service.ProfileServiceAccountVerifier;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.tools.tracker.TrackerRunScope;
import com.azhukov.agent.tools.tracker.TrackerTool;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
    name = ForgeTool.NAME,
    description = "Read Forge resources for the current Tracker assignment. Use repository, tree and blob for "
        + "assigned source. Use workspace to check the active Fleet-issued workspace session. "
        + "Repository, workspace and session IDs are taken from the assignment.",
    toolset = ForgeTool.NAME
)
@Component
public class ForgeTool implements ToolHandler {
    public static final String NAME = "forge";
    private static final int PAGE_SIZE = 50;
    private static final int RESPONSE_LIMIT = 4 * 1024 * 1024;
    private static final Set<String> READ = Set.of("ci-cd:read");
    private static final Set<String> OPTIONAL_WRITE = Set.of("ci-cd:write");
    private static final Set<String> CANDIDATE_STAGES = Set.of("Development", "Review", "Testing", "Rework", "Deployment");

    private final ProfileEnvStore profileEnv;
    private final ProfileServiceAccountVerifier serviceAccounts;
    private final TrackerTool tracker;
    private final ObjectMapper json;
    private final URI origin;
    private final String instanceId;
    private final HttpClient http = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public ForgeTool(ProfileEnvStore profileEnv, ProfileServiceAccountVerifier serviceAccounts,
                     TrackerTool tracker, ObjectMapper json, @Value("${agent.forge.base-url:}") String baseUrl,
                     @Value("${agent.forge.instance-id:}") String instanceId) {
        this.profileEnv = profileEnv;
        this.serviceAccounts = serviceAccounts;
        this.tracker = tracker;
        this.json = json;
        this.instanceId = instanceId;
        this.origin = baseUrl.isBlank() ? null : URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (origin != null && (!ProfileServiceAccountVerifier.trustedHttpEndpoint(origin)
            || !"/".equals(origin.getRawPath()))) {
            throw new IllegalArgumentException("Invalid Forge origin");
        }
    }

    public boolean configured(String profile) {
        try {
            return origin != null && TrackerRunScope.canonicalId(instanceId)
                && tracker.configured(profile) && credential(profile) != null;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    public String verifyIdentity(String profile, String expectedSubject) {
        try {
            verifyCredential(credential(profile), expectedSubject);
            return origin.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Forge identity is unavailable");
        }
    }

    @Override
    public ToolResult execute(String input, Message lastAssistant, Session session) {
        try {
            var args = json.readerFor(ForgeArgs.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).<ForgeArgs>readValue(input);
            validate(args);
            var assignment = tracker.boundSource(session);
            var source = assignment.binding();
            if (!instanceId.equals(source.path("forge_instance_id").asText())) {
                throw new IllegalStateException("Forge installation differs from the Tracker assignment");
            }
            String token = credential(session.profile());
            verifyCredential(token, assignment.scope().machineSubject());
            JsonNode response = switch (args.action()) {
                case "repository" -> repository(source, token);
                case "tree", "blob" -> gitRead(assignment, args, token);
                case "workspace" -> workspace(assignment, token);
                default -> throw new IllegalArgumentException();
            };
            String content = json.writeValueAsString(response);
            return content.contains(token) ? ToolResult.fail("Forge response contains a protected credential")
                : ToolResult.ok(content);
        } catch (ForgeRefused e) {
            return ToolResult.fail("Forge refused the operation (HTTP " + e.status + ")");
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return ToolResult.fail("Invalid Forge arguments or response");
        } catch (IllegalStateException e) {
            return ToolResult.fail("Forge assignment, credential or returned resource does not match");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("Forge operation interrupted");
        } catch (IOException e) {
            return ToolResult.fail("Forge assigned source is unavailable");
        }
    }

    private void verifyCredential(String token, String subject) {
        if (origin == null || token == null || !subject.equals(serviceAccounts.verify(token, READ, OPTIONAL_WRITE))) {
            throw new IllegalStateException("Forge identity is unavailable");
        }
    }

    private JsonNode repository(JsonNode source, String token) throws IOException, InterruptedException {
        String repositoryId = source.path("repository_id").asText();
        JsonNode namespace = source.path("namespace");
        if (!TrackerRunScope.canonicalId(namespace.path("registry_instance_id").asText())
            || !TrackerRunScope.canonicalId(namespace.path("namespace_id").asText())) {
            throw new IllegalStateException("Invalid bound Forge namespace");
        }
        JsonNode record = get("api/v1/catalog/repositories/" + repositoryId, token);
        String publicName = record.path("public_name").asText();
        if (!repositoryId.equals(record.path("id").asText()) || !namespace.equals(record.path("namespace"))
            || publicName.length() > 512 || !publicName.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
            || !validPath(publicName)) {
            throw new IllegalStateException("Forge repository differs");
        }
        var result = json.createObjectNode().put("repository_id", repositoryId)
            .put("forge_instance_id", source.path("forge_instance_id").asText())
            .put("public_name", publicName).put("base_ref", source.path("base_ref").asText())
            .put("candidate_ref", source.path("candidate_ref").asText());
        result.set("namespace", namespace);
        return result;
    }

    private JsonNode gitRead(TrackerTool.BoundSource assigned, ForgeArgs args, String token)
            throws IOException, InterruptedException {
        var source = assigned.binding();
        boolean candidate = "candidate".equals(args.ref());
        String ref = source.path(candidate ? "candidate_ref" : "base_ref").asText();
        if (!ref.startsWith("refs/heads/") || !validRef(ref)
            || (candidate && (!CANDIDATE_STAGES.contains(assigned.stage())
                || !("refs/heads/sdlc/" + assigned.scope().taskId()).equals(ref)))) {
            throw new IllegalStateException("Forge reference is unavailable for this assignment");
        }
        JsonNode repo = repository(source, token);
        String route = "api/v1/repos/" + encoded(repo.path("public_name").asText())
            + "/" + args.action() + "?ref=" + encoded(ref);
        if (args.path() != null) {
            route += "&path=" + encoded(args.path());
        }
        if ("blob".equals(args.action())) {
            JsonNode blob = get(route, token);
            if (!args.path().equals(blob.path("path").asText())
                || !blob.path("sha").asText().matches("[a-f0-9]{40}")) {
                throw new IllegalStateException("Forge blob differs");
            }
            var result = json.createObjectNode().put("ref", ref);
            result.set("blob", blob);
            return result;
        }
        int offset = args.offset() == null ? 0 : args.offset();
        JsonNode entries = get(route + "&offset=" + offset + "&limit=" + PAGE_SIZE, token);
        if (!entries.isArray() || entries.size() > PAGE_SIZE) {
            throw new IllegalStateException("Forge tree page differs");
        }
        var result = json.createObjectNode().put("ref", ref);
        result.set("entries", entries);
        if (entries.size() == PAGE_SIZE) {
            result.put("next_offset", offset + PAGE_SIZE);
        }
        return result;
    }

    private JsonNode workspace(TrackerTool.BoundSource assigned, String token) throws IOException, InterruptedException {
        var source = assigned.binding();
        var scope = assigned.scope();
        String id = source.path("development_workspace_id").asText();
        String session = scope.forgeSessionId();
        if (!TrackerRunScope.canonicalId(id) || session == null) {
            throw new IllegalStateException("Fleet has no workspace session for the assignment");
        }
        String route = "api/v1/development-workspaces/" + id;
        JsonNode principal = get("api/v1/auth/principal", token);
        String actorId = principal.path("sub").asText();
        if (!"service_account".equals(principal.path("principal_type").asText())
            || !TrackerRunScope.canonicalId(actorId)
            || !actorId.equals(principal.path("service_account").path("id").asText())) {
            throw new IllegalStateException("Forge workspace actor is unavailable");
        }
        JsonNode begin = get(route + "/operations/" + session, token);
        if (!id.equals(begin.path("workspace_id").asText()) || !session.equals(begin.path("id").asText())
            || !"begin_session".equals(begin.path("request").path("action").asText())
            || !scope.taskId().equals(begin.path("request").path("task").asText())
            || !actorId.equals(begin.path("actor_id").asText())
            || !"succeeded".equals(begin.path("state").asText())
            || !"succeeded".equals(begin.path("result").path("state").asText())
            || !session.equals(begin.path("result").path("sessionId").asText())) {
            throw new IllegalStateException("Forge workspace session receipt differs");
        }
        JsonNode card = get(route, token);
        JsonNode workspace = card.path("workspace");
        JsonNode active = card.path("session");
        JsonNode authority = active.path("machine_assignment");
        if (!id.equals(workspace.path("id").asText())
            || !scope.trackerInstanceId().equals(workspace.path("tracker_instance_id").asText())
            || !scope.projectId().equals(workspace.path("tracker_project_id").asText())
            || !source.path("repository_id").equals(workspace.path("repository_id"))
            || !actorId.equals(card.path("actor_id").asText()) || !actorId.equals(active.path("actor_id").asText())
            || !active.path("ended_at").isNull() || !session.equals(active.path("id").asText())
            || !scope.machineSubject().equals(active.path("owner_subject").asText())
            || !scope.taskId().equals(active.path("task_id").asText()) || !id.equals(active.path("workspace_id").asText())
            || !scope.machineSubject().equals(authority.path("machine_subject").asText())
            || !scope.taskId().equals(authority.path("task_id").asText())
            || !scope.agentId().equals(authority.path("agent_id").asText())
            || !scope.assignmentId().equals(authority.path("assignment_id").asText())
            || !scope.executionId().equals(authority.path("execution_id").asText())
            || scope.assignmentVersion() != authority.path("version").asLong(-1)
            || !source.path("requirement_revision").equals(authority.path("requirement_revision"))) {
            throw new IllegalStateException("Current Forge workspace session differs");
        }
        var result = json.createObjectNode().put("workspace_id", id).put("session_id", session)
            .put("task_id", scope.taskId()).put("repository_id", workspace.path("repository_id").asText())
            .put("candidate_ref", source.path("candidate_ref").asText());
        return result;
    }

    private static void validate(ForgeArgs args) {
        if (args == null || args.action() == null) {
            throw new IllegalArgumentException();
        }
        boolean git = "tree".equals(args.action()) || "blob".equals(args.action());
        if (git) {
            if ((args.ref() != null && !Set.of("base", "candidate").contains(args.ref()))
                || (args.path() != null && !validPath(args.path()))
                || ("blob".equals(args.action()) && (args.path() == null || args.offset() != null))
                || (args.offset() != null && (args.offset() < 0 || args.offset() > 100_000))) {
                throw new IllegalArgumentException();
            }
        } else if (!Set.of("repository", "workspace").contains(args.action()) || args.ref() != null
            || args.path() != null || args.offset() != null) {
            throw new IllegalArgumentException();
        }
    }

    private static boolean validPath(String path) {
        return !path.isEmpty() && path.length() <= 768 && path.indexOf('\\') < 0
            && path.chars().noneMatch(Character::isISOControl)
            && java.util.Arrays.stream(path.split("/", -1))
                .noneMatch(part -> part.isEmpty() || ".".equals(part) || "..".equals(part));
    }

    private static boolean validRef(String ref) {
        return ref.length() <= 256 && validPath(ref)
            && ref.chars().allMatch(c -> c >= 33 && c <= 126 && "~^:?*[".indexOf(c) < 0);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String credential(String profile) throws IOException {
        String token = profileEnv.readAll(profile).get("CICD_API_TOKEN");
        return token != null && token.matches("(?:pdlc_pat_|sdlc_pat_)[a-zA-Z0-9_-]{1,498}") ? token : null;
    }

    private JsonNode get(String route, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(origin.resolve(route)).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token).header("Accept", "application/json")
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache, no-store").GET().build();
        var response = http.send(request,
            HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), RESPONSE_LIMIT));
        if (response.statusCode() != 200) {
            throw new ForgeRefused(response.statusCode());
        }
        if (!"identity".equals(response.headers().firstValue("Content-Encoding").orElse("identity"))) {
            throw new IOException("Unexpected Forge content encoding");
        }
        var data = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(response.body());
        if (data == null || !(data.isObject() || data.isArray())) {
            throw new IOException("Invalid Forge response");
        }
        return data;
    }

    private static class ForgeRefused extends IOException {
        private final int status;

        ForgeRefused(int status) {
            this.status = status;
        }
    }

    public record ForgeArgs(
        @ToolParam(description = "Read assigned Forge resources", enumValues = {"repository", "tree", "blob", "workspace"})
        String action,
        @ToolParam(description = "Assigned Git reference. Base is the default.", enumValues = {"base", "candidate"},
            required = false)
        String ref,
        @ToolParam(description = "Repository-relative path for tree or blob. Omit it for the root tree.", required = false)
        String path,
        @ToolParam(description = "Tree page offset", required = false)
        Integer offset
    ) {}
}
