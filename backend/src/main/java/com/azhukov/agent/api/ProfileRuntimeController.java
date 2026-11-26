package com.azhukov.agent.api;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.OpenAiRunService;
import com.azhukov.agent.service.ProfileInstructions;
import com.azhukov.agent.service.ProfileModelRouting;
import com.azhukov.agent.service.ProfileRuntimeRegistry;
import com.azhukov.agent.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    @GetMapping
    public Map<String, Object> state(@PathVariable String profile) {
        requireNamedProfile(profile);
        return Map.of("profile", profile, "state", runtime.namedWorkerState(profile));
    }

    @PutMapping
    public Map<String, Object> setState(@PathVariable String profile, @RequestBody WorkerState request) {
        requireNamedProfile(profile);
        if (!UserContext.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
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

    private void requireNamedProfile(String profile) {
        if (!profiles.isValidNamedProfileName(profile) || !profiles.knownProfile(profile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown profile");
        }
    }

    public record WorkerState(String state) {}
}
