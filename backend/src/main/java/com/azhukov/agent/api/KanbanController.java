package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.KanbanAddRequest;
import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.core.memory.MemoryScope;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.TodoService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/p/{profile}/api/v1"})
@Slf4j
@Tag(name = "Kanban", description = "Todo/kanban board management")
public class KanbanController {

    private static final String DEFAULT_TODO_USER_ID = "default";

    private final TodoService todoService;
    private final ProfileService profileService;

    @Autowired
    public KanbanController(TodoService todoService, ProfileService profileService) {
        this.todoService = todoService;
        this.profileService = profileService;
    }

    KanbanController(TodoService todoService) {
        this(todoService, null);
    }

    @Operation(summary = "List all kanban/todo items")
    @GetMapping("/agent/kanban")
    public ResponseEntity<?> getKanban(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        return ResponseEntity.ok(todoService.listByUserId(todoUserId(profile.profile())));
    }

    @Operation(summary = "Add a new kanban/todo item")
    @PostMapping("/agent/kanban/add")
    public ResponseEntity<?> addKanbanItem(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @Valid @RequestBody KanbanAddRequest body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        String text = body.text();
        return ResponseEntity.ok(todoService.add(todoUserId(profile.profile()), text));
    }

    @PostMapping("/agent/kanban/done/{id}")
    public ResponseEntity<?> completeKanbanItem(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable UUID id,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        return todoService.markDoneForUser(id, todoUserId(profile.profile()))
            .map(dto -> ResponseEntity.ok().<Void>build())
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/agent/kanban")
    public ResponseEntity<?> deleteKanbanItem(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        todoService.clearByUserId(todoUserId(profile.profile()));
        return ResponseEntity.ok().build();
    }

    private String todoUserId(String profile) {
        return "default".equals(profile)
            ? DEFAULT_TODO_USER_ID
            : MemoryScope.userId(DEFAULT_TODO_USER_ID, profile);
    }

    private ProfileResolution resolveProfileScope(String pathProfile, String queryProfile) {
        ProfileResolution path = normalizeProfile(pathProfile);
        if (path.error() != null) {
            return path;
        }
        ProfileResolution query = normalizeProfile(queryProfile);
        if (query.error() != null) {
            return query;
        }
        String profile = path.profile() != null ? path.profile() : query.profile();
        if (profile == null) {
            profile = "default";
        }
        if (path.profile() != null && query.profile() != null && !path.profile().equals(query.profile())) {
            return ProfileResolution.error(badRequest("profile values do not match"));
        }
        if (!"default".equals(profile) && profileService == null) {
            return ProfileResolution.error(notImplemented(
                "profile-scoped kanban is not available in this Java agent configuration"));
        }
        if (profileService != null && !profileService.knownProfile(profile)) {
            return ProfileResolution.error(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Unknown profile: " + profile)));
        }
        return ProfileResolution.ok(profile);
    }

    private ProfileResolution normalizeProfile(String rawProfile) {
        if (rawProfile == null || rawProfile.isBlank()) {
            return ProfileResolution.ok(null);
        }
        try {
            String profile = profileService != null
                ? profileService.normalizeProfileName(rawProfile)
                : rawProfile.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(profile)) {
                return ProfileResolution.error(badRequest("profile=all is not supported for kanban"));
            }
            if (profileService != null) {
                profileService.validateProfileName(profile);
            } else if (!"default".equals(profile)) {
                return ProfileResolution.error(notImplemented(
                    "profile-scoped kanban is not available in this Java agent configuration"));
            }
            return ProfileResolution.ok(profile);
        } catch (IllegalArgumentException e) {
            return ProfileResolution.error(badRequest(e.getMessage()));
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(Map.of("detail", detail));
    }

    private static ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("detail", detail));
    }

    private record ProfileResolution(String profile, ResponseEntity<Map<String, Object>> error) {
        private static ProfileResolution ok(String profile) {
            return new ProfileResolution(profile, null);
        }

        private static ProfileResolution error(ResponseEntity<Map<String, Object>> error) {
            return new ProfileResolution(null, error);
        }
    }
}
