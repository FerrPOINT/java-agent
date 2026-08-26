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
@RequestMapping("/api/v2/sessions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Session CRUD", description = "Session lifecycle CRUD and session-scoped chat")
public class SessionCrudController {

    private final SessionQueryService sessionQueryService;
    private final AgentRuntimeService agentRuntimeService;
    private final AgentStreamingService streamingService;

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
        return ResponseEntity.created(URI.create("/api/v2/sessions/" + sessionId)).body(response);
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