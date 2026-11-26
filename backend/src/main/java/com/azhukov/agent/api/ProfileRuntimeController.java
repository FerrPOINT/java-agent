package com.azhukov.agent.api;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.security.NativeIdentityOrigin;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.OpenAiRunService;
import com.azhukov.agent.service.ProfileExecutionConfiguration;
import com.azhukov.agent.service.ProfileInstructions;
import com.azhukov.agent.service.ProfileModelRouting;
import com.azhukov.agent.service.ProfileRuntimeRegistry;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.tools.workflow.ProjectWorkflowTool;
import com.azhukov.agent.tools.tracker.TrackerTool;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/p/{profile}/v1/runtime")
@RequiredArgsConstructor
public class ProfileRuntimeController {
    private final ProfileService profiles;
    private final ProfileRuntimeRegistry runtime;
    private final OpenAiRunService runs;
    private final ProfileModelRouting models;
    private final ProfileInstructions instructions;
    private final ProfileExecutionConfiguration settings;
    private final ToolRegistry toolRegistry;
    private final ProjectWorkflowTool workflow;
    private final TrackerTool tracker;

    @GetMapping
    public Map<String, Object> state(@PathVariable String profile) {
        requireNamedProfile(profile);
        return Map.of("profile", profile, "state", runtime.namedWorkerState(profile));
    }

    @GetMapping("/workflow-identity")
    public ResponseEntity<Map<String, Object>> workflowIdentity(@PathVariable String profile) {
        requireNamedProfile(profile);
        if (!fleetPrincipal(profile)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Fleet profile access required");
        }
        try {
            var accepted = runtime.locked(profile, () -> {
                runtime.requireRunning(profile);
                return settings.capture(profile);
            });
            var identity = accepted.use(() -> {
                Session selected = Session.create(UserContext.getUserId(), "openai-compatible", "workflow-observation")
                    .withMetadata("profile", profile).withMetadata("platform", "api_server");
                if (!toolRegistry.isProfileToolEnabled(ProjectWorkflowTool.NAME, selected)) {
                    throw new IllegalStateException("Workflow tool is unavailable");
                }
                var proof = workflow.verifyIdentity(profile);
                var result = new LinkedHashMap<String, Object>(Map.of(
                    "profile", profile, "profile_revision", accepted.revision(),
                    "protocol", "configured-cli-v1", "namespace_id", proof.namespaceId(),
                    "machine_subject", proof.machineSubject(), "workflow_base_url", proof.workflowBaseUrl()));
                if (toolRegistry.isProfileToolEnabled(TrackerTool.NAME, selected)) {
                    result.put("tracker_base_url", tracker.verifyIdentity(profile, proof.machineSubject()));
                }
                return result;
            });
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Workflow identity is unavailable");
        }
    }

    @PutMapping
    public Map<String, Object> setState(@PathVariable String profile, @RequestBody WorkerState request) {
        requireNamedProfile(profile);
        if (!UserContext.isAdmin() && !fleetPrincipal(profile)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Profile operator access required");
        }
        String state = request.state();
        if (!ProfileRuntimeRegistry.WORKER_RUNNING.equals(state)
            && !ProfileRuntimeRegistry.WORKER_STOPPED.equals(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile state must be running or stopped");
        }
        return runtime.locked(profile, () -> {
            if (ProfileRuntimeRegistry.WORKER_RUNNING.equals(state)) {
                try {
                    models.bind(profile, null);
                    instructions.read(profile);
                } catch (ProfileModelRouting.ProfileModelUnavailable | IllegalStateException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Profile configuration is unavailable");
                }
            }
            if (!runtime.updateWorkerState(profile, state)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Profile state storage is unavailable");
            }
            int stopRequests = ProfileRuntimeRegistry.WORKER_STOPPED.equals(state) ? runs.requestProfileStop(profile) : 0;
            return Map.of("profile", profile, "state", state, "run_stop_requests", stopRequests);
        });
    }

    private static boolean fleetPrincipal(String profile) {
        var origin = NativeIdentityOrigin.current();
        return origin != null && origin.kind() == NativeIdentityOrigin.Kind.FLEET_PROFILE
            && ("fleet-profile:" + profile).equals(origin.principal());
    }

    private void requireNamedProfile(String profile) {
        if (!profiles.isValidNamedProfileName(profile) || !profiles.knownProfile(profile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown profile");
        }
    }

    public record WorkerState(String state) {}
}
