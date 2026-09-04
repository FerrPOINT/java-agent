package com.azhukov.agent.api;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.UserAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin surface for multi-user management (plan Phase 4): create users,
 * issue/revoke per-user API keys. Every endpoint requires the admin role —
 * per-user keys carry ROLE_USER and are rejected here.
 * <p>
 * The raw API key is returned exactly once, on creation; only its SHA-256
 * digest is persisted.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin: Users & API Keys", description = "Multi-user administration (admin role required)")
public class UserAdminController {

    private final UserAccessService userAccessService;

    /** Rejects non-admin callers with 403 before any data is touched. */
    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Admin role required for user management");
        }
    }

    public record CreateUserRequest(
        @NotBlank String username,
        String displayName,
        String role) {}

    public record UserDto(String id, String username, String displayName,
                          String role, Instant createdAt) {}

    public record IssueKeyRequest(String label) {}

    /** The raw key appears exactly once, in the creation response. */
    public record IssuedKeyDto(UUID id, String rawKey, String userId, String label, Instant createdAt) {}

    public record ApiKeyDto(UUID id, String userId, String label,
                            Instant createdAt, Instant lastUsedAt) {}

    @Operation(summary = "Create a user (role: user|admin)")
    @PostMapping
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        requireAdmin();
        var user = userAccessService.createUser(
            request.username(), request.displayName(), request.role());
        return toDto(user);
    }

    @Operation(summary = "List all users")
    @GetMapping
    public List<UserDto> list() {
        requireAdmin();
        return userAccessService.listUsers().stream().map(this::toDto).toList();
    }

    @Operation(summary = "Get one user")
    @GetMapping("/{userId}")
    public UserDto get(@PathVariable String userId) {
        requireAdmin();
        var user = userAccessService.getUser(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId);
        }
        return toDto(user);
    }

    @Operation(summary = "Issue a per-user API key; raw key returned once")
    @PostMapping("/{userId}/keys")
    public IssuedKeyDto issueKey(@PathVariable String userId,
                                 @RequestBody(required = false) IssueKeyRequest request) {
        requireAdmin();
        try {
            var issued = userAccessService.issueApiKey(userId,
                request != null ? request.label() : null);
            return new IssuedKeyDto(issued.id(), issued.rawKey(), issued.userId(),
                request != null ? request.label() : null, Instant.now());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @Operation(summary = "List a user's API keys (hashes never exposed)")
    @GetMapping("/{userId}/keys")
    public List<ApiKeyDto> listKeys(@PathVariable String userId) {
        requireAdmin();
        return userAccessService.listApiKeys(userId).stream()
            .map(k -> new ApiKeyDto(k.getId(), k.getUserId(), k.getLabel(),
                k.getCreatedAt(), k.getLastUsedAt()))
            .toList();
    }

    @Operation(summary = "Revoke an API key by id")
    @DeleteMapping("/keys/{keyId}")
    public ResponseEntity<Map<String, Object>> revokeKey(@PathVariable UUID keyId) {
        requireAdmin();
        boolean revoked = userAccessService.revokeApiKey(keyId);
        if (!revoked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Key not found: " + keyId);
        }
        log.info("API key revoked: id={} actor={}", keyId, UserContext.getUserId());
        return ResponseEntity.ok(Map.of("revoked", true, "keyId", keyId.toString()));
    }

    private UserDto toDto(com.azhukov.agent.persistence.entity.AgentUserEntity user) {
        return new UserDto(user.getId(), user.getUsername(), user.getDisplayName(),
            user.getRole(), user.getCreatedAt());
    }
}
