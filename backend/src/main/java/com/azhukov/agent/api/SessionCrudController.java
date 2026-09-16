package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.MessageListDto;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.SessionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * Session CRUD and session-scoped chat endpoints.
 *
 * Mirrors Hermes' /api/sessions endpoints:
 * <ul>
 *   <li>GET    /api/v2/sessions — list sessions (paginated)</li>
 *   <li>POST   /api/v2/sessions — create a new session</li>
 *   <li>GET    /api/v2/sessions/{id} — get a session by ID</li>
 *   <li>PATCH  /api/v2/sessions/{id} — update session metadata (title)</li>
 *   <li>DELETE /api/v2/sessions/{id} — delete a session</li>
 *   <li>GET    /api/v2/sessions/{id}/messages — list session messages</li>
 *   <li>POST   /api/v2/sessions/{id}/chat — chat within a session</li>
 *   <li>POST   /api/v2/sessions/{id}/chat/stream — stream chat within a session</li>
 * </ul>
 */
@RestController
@RequestMapping({"/api/v2/sessions", "/api/sessions"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Session CRUD", description = "Session lifecycle CRUD and session-scoped chat")
public class SessionCrudController {

    private final SessionQueryService sessionQueryService;
    private final AgentRuntimeService agentRuntimeService;
    private final AgentStreamingService streamingService;
    private final org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.service.SessionPruneService> pruneServiceProvider;

    // ── List sessions ──

    @Operation(summary = "List sessions with pagination")
    @GetMapping
    public Map<String, Object> listSessions(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String userId
    ) {
        return sessionQueryService.listSessions(limit, offset, userId);
    }

    // ── Create session ──

    @Operation(summary = "Create a new session")
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody(required = false) CreateSessionBody body) {
        String userId = body != null ? body.userId() : null;
        String model = body != null ? body.model() : null;
        String title = body != null ? body.title() : null;
        Map<String, Object> response = sessionQueryService.createSession(userId, model, title);
        String sessionId = (String) response.get("id");
        return ResponseEntity.created(URI.create("/api/sessions/" + sessionId)).body(response);
    }

    public record CreateSessionBody(String userId, String model, String title) {}

    // ── Get session ──

    @Operation(summary = "Get a session by ID")
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable UUID sessionId) {
        return sessionQueryService.getSession(sessionId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Update session ──

    @Operation(summary = "Update session metadata (title)")
    @PatchMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> updateSession(
            @PathVariable UUID sessionId,
            @RequestBody UpdateSessionBody body) {
        return sessionQueryService.updateSession(sessionId, body.title())
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record UpdateSessionBody(String title) {}

    // ── Delete session ──

    @Operation(summary = "Delete a session and all its messages")
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable UUID sessionId) {
        boolean deleted = sessionQueryService.deleteSession(sessionId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
            "object", "session.deleted",
            "id", sessionId.toString(),
            "deleted", true
        ));
    }

    // ── Get session messages ──

    @Operation(summary = "List messages in a session")
    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<MessageListDto> getSessionMessages(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return sessionQueryService.getSessionMessages(sessionId, limit, offset)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Prune ended sessions (WP-4.6) — Hermes prune_sessions parity subset ──

    @org.springframework.web.bind.annotation.PostMapping("/prune")
    @io.swagger.v3.oas.annotations.Operation(summary = "Prune ended sessions over persisted filters; unsupported filters are rejected explicitly")
    public ResponseEntity<Map<String, Object>> pruneSessions(
        @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> body
    ) {
        com.azhukov.agent.service.SessionPruneService pruneService =
            pruneServiceProvider == null ? null : pruneServiceProvider.getIfAvailable();
        if (pruneService == null) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("detail", "session prune is not available in this deployment"));
        }
        Map<String, Object> raw = body == null ? Map.of() : body;
        // Fail closed on filters Java cannot evaluate (no fabricated matches).
        Map<String, Object> unsupported = new java.util.LinkedHashMap<>();
        for (String filter : com.azhukov.agent.service.SessionPruneService.UNSUPPORTED_FILTERS) {
            Object value = raw.get(filter);
            if (value != null && !String.valueOf(value).isBlank()) {
                unsupported.put(filter, value);
            }
        }
        if (!unsupported.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of(
                    "detail", "unsupported prune filters (fields not persisted in the Java port): "
                        + String.join(", ", unsupported.keySet()),
                    "unsupported_filters", unsupported.keySet(),
                    "error", "unsupported_filters"));
        }
        com.azhukov.agent.service.SessionPruneService.PruneRequest request =
            new com.azhukov.agent.service.SessionPruneService.PruneRequest(
                string(raw, "profile"),
                string(raw, "source"),
                string(raw, "title_contains"),
                string(raw, "end_reason"),
                string(raw, "user_id"),
                string(raw, "model_contains"),
                instant(raw, "started_before"),
                instant(raw, "started_after"),
                instant(raw, "older_than"),
                integer(raw, "min_messages"),
                integer(raw, "max_messages"),
                Boolean.parseBoolean(String.valueOf(raw.getOrDefault("include_archived", false))),
                Boolean.parseBoolean(String.valueOf(raw.getOrDefault("dry_run", false))));
        com.azhukov.agent.service.SessionPruneService.PruneResult result = pruneService.prune(request);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("pruned", result.pruned());
        response.put("pruned_count", result.pruned().size());
        response.put("skipped_open", result.skippedOpen());
        response.put("deleted_messages", result.deletedMessages());
        response.put("dry_run", result.dryRun());
        return ResponseEntity.ok(response);
    }

    private static String string(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value);
    }

    private static Integer integer(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static java.time.Instant instant(Map<String, Object> raw, String key) {
        Object value = raw.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    // ── Fork (branch) session — Hermes parity for POST /api/sessions/{id}/fork ──

    @Operation(summary = "Fork a session (copies the transcript to a new session)")
    @PostMapping("/{sessionId}/fork")
    public ResponseEntity<Map<String, Object>> forkSession(
            @PathVariable UUID sessionId,
            @RequestBody(required = false) Map<String, Object> body) {
        String name = body != null && body.get("title") instanceof String t ? t : null;
        try {
            var summary = agentRuntimeService.branchSession(sessionId, name);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("object", "session");
            response.put("id", summary.id() != null ? summary.id().toString() : null);
            response.put("title", summary.title());
            response.put("created_at", summary.createdAt() != null ? summary.createdAt().toString() : null);
            response.put("parent_session_id", sessionId.toString());
            return ResponseEntity.status(201).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Session model lock — Hermes parity for POST /api/sessions/{id}/model ──

    @Operation(summary = "Persist a per-session model lock")
    @PostMapping("/{sessionId}/model")
    public ResponseEntity<Map<String, Object>> sessionModelLock(
            @PathVariable UUID sessionId,
            @RequestBody Map<String, Object> body) {
        Object modelObj = body != null ? body.get("model") : null;
        Object providerObj = body != null ? body.get("provider") : null;
        if (modelObj == null || modelObj.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", Map.of("message", "model is required", "type", "invalid_request_error")));
        }
        try {
            agentRuntimeService.switchModel(sessionId, modelObj.toString(),
                providerObj != null ? providerObj.toString() : null);
            return ResponseEntity.ok(Map.of(
                "object", "session.model_lock",
                "session_id", sessionId.toString(),
                "model", modelObj.toString(),
                "provider", providerObj != null ? providerObj.toString() : ""));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                "error", Map.of("message", "Could not persist the requested session model lock",
                    "type", "server_error")));
        }
    }

    // ── Session-scoped chat (synchronous) ──

    @Operation(summary = "Chat within a specific session (synchronous)")
    @PostMapping("/{sessionId}/chat")
    public ResponseEntity<ChatResponseDto> sessionChat(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SessionChatRequest body) {
        if (!sessionQueryService.sessionExists(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        ChatRequest request = ChatRequest.simple(sessionId, body.message(), null, body.timeoutMs());
        ChatResponseDto response = agentRuntimeService.runTurn(request);
        return ResponseEntity.ok(response);
    }

    // ── Session-scoped chat (streaming) ──

    @Operation(summary = "Stream chat within a specific session via SSE")
    @PostMapping(value = "/{sessionId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> sessionChatStream(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SessionChatRequest body) {
        if (!sessionQueryService.sessionExists(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        ChatRequest request = ChatRequest.simple(sessionId, body.message(), null, body.timeoutMs());
        SseEmitter emitter = streamingService.streamTurn(request);
        return ResponseEntity.ok(emitter);
    }

    // ── Helper record ──

    public record SessionChatRequest(String message, Long timeoutMs) {}
}