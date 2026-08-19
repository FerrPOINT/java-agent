package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AgentRuntimeService agentRuntimeService;
    private final AgentStreamingService streamingService;
    private final AgentSessionResolver sessionResolver;
    private final SessionEntityMapper sessionMapper;
    private final DomainDtoMapper domainDtoMapper;
    private final AgentProperties properties;

    // ── List sessions ──

    @Operation(summary = "List sessions with pagination")
    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> listSessions(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String userId
    ) {
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        int cappedOffset = Math.max(offset, 0);
        String effectiveUserId = userId != null ? userId : "user-1";

        List<SessionSummaryDto> sessions = sessionRepository
            .findAllByUserId(effectiveUserId, PageRequest.of(cappedOffset / cappedLimit, cappedLimit))
            .stream()
            .map(sessionMapper::toDomain)
            .map(domainDtoMapper::toSessionSummaryDto)
            .toList();

        // M15: Use count query for reliable has_more instead of size==limit heuristic.
        // The old approach (sessions.size() == cappedLimit) is unreliable because
        // it reports has_more=true even when exactly cappedLimit results exist (the last page).
        long totalCount = sessionRepository.countByUserId(effectiveUserId);
        boolean hasMore = (cappedOffset + sessions.size()) < totalCount;

        return Map.of(
            "object", "list",
            "data", sessions,
            "limit", cappedLimit,
            "offset", cappedOffset,
            "has_more", hasMore
        );
    }

    // ── Create session ──

    @Operation(summary = "Create a new session")
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody(required = false) CreateSessionBody body) {
        String userId = body != null && body.userId() != null ? body.userId() : "user-1";
        String model = body != null && body.model() != null && !body.model().isBlank()
            ? body.model() : properties.getModel().getModelName();
        String title = body != null && body.title() != null ? body.title() : "New chat";

        Session session = sessionResolver.createSession(userId, "openai-compatible", model);

        // Set title if provided
        if (body != null && body.title() != null) {
            sessionRepository.findById(session.id()).ifPresent(e -> {
                e.setTitle(title);
                e.setUpdatedAt(Instant.now());
                sessionRepository.save(e);
            });
        }

        Map<String, Object> response = toSessionResponse(session.id(), userId, title, model);
        return ResponseEntity.created(URI.create("/api/v2/sessions/" + session.id())).body(response);
    }

    public record CreateSessionBody(String userId, String model, String title) {}

    // ── Get session ──

    @Operation(summary = "Get a session by ID")
    @GetMapping("/{sessionId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> response = toSessionResponse(
            entity.getId(), entity.getUserId(), entity.getTitle(), entity.getModelName());
        return ResponseEntity.ok(response);
    }

    // ── Update session ──

    @Operation(summary = "Update session metadata (title)")
    @PatchMapping("/{sessionId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateSession(
            @PathVariable UUID sessionId,
            @RequestBody UpdateSessionBody body) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.title() != null) {
            entity.setTitle(body.title());
        }
        entity.setUpdatedAt(Instant.now());
        sessionRepository.save(entity);

        Map<String, Object> response = toSessionResponse(
            entity.getId(), entity.getUserId(), entity.getTitle(), entity.getModelName());
        return ResponseEntity.ok(response);
    }

    public record UpdateSessionBody(String title) {}

    // ── Delete session ──

    @Operation(summary = "Delete a session and all its messages")
    @DeleteMapping("/{sessionId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        // Delete messages first
        messageRepository.deleteAll(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
        sessionRepository.delete(entity);
        return ResponseEntity.ok(Map.of(
            "object", "session.deleted",
            "id", sessionId.toString(),
            "deleted", true
        ));
    }

    // ── Get session messages ──

    @Operation(summary = "List messages in a session")
    @GetMapping("/{sessionId}/messages")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getSessionMessages(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        if (!sessionRepository.existsById(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        int cappedLimit = Math.min(Math.max(limit, 1), 500);
        // M16: Use Pageable repository method instead of loading ALL messages into memory.
        // The old approach called findBySessionIdOrderByCreatedAtAsc(sessionId) which loads
        // the entire message history, then applied offset/limit in Java — wasteful for
        // sessions with thousands of messages.
        int cappedOffset = Math.max(offset, 0);
        int page = cappedOffset / cappedLimit;
        List<MessageEntity> messages = messageRepository
            .findBySessionIdOrderByCreatedAtAsc(sessionId,
                org.springframework.data.domain.PageRequest.of(page, cappedLimit))
            .getContent();

        List<Map<String, Object>> data = messages.stream()
            .map(this::toMessageResponse)
            .toList();

        return ResponseEntity.ok(Map.of(
            "object", "list",
            "session_id", sessionId.toString(),
            "data", data,
            "limit", cappedLimit,
            "offset", cappedOffset
        ));
    }

    // ── Session-scoped chat (synchronous) ──

    @Operation(summary = "Chat within a specific session (synchronous)")
    @PostMapping("/{sessionId}/chat")
    public ResponseEntity<ChatResponseDto> sessionChat(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SessionChatRequest body) {
        if (!sessionRepository.existsById(sessionId)) {
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
        if (!sessionRepository.existsById(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        ChatRequest request = ChatRequest.simple(sessionId, body.message(), null, body.timeoutMs());
        SseEmitter emitter = streamingService.streamTurn(request);
        return ResponseEntity.ok(emitter);
    }

    // ── Helper methods ──

    public record SessionChatRequest(String message, Long timeoutMs) {}

    private Map<String, Object> toSessionResponse(UUID id, String userId, String title, String model) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "session");
        response.put("id", id.toString());
        response.put("user_id", userId);
        response.put("title", title != null ? title : "New chat");
        response.put("model", model != null ? model : "");
        response.put("source", "api_server");
        return response;
    }

    private Map<String, Object> toMessageResponse(MessageEntity msg) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", msg.getId().toString());
        response.put("session_id", msg.getSessionId().toString());
        response.put("role", msg.getRole());
        response.put("content", msg.getContent());
        if (msg.getToolCallName() != null) {
            response.put("tool_name", msg.getToolCallName());
        }
        if (msg.getToolCallId() != null) {
            response.put("tool_call_id", msg.getToolCallId());
        }
        if (msg.getTurnIndex() != null) {
            response.put("turn_index", msg.getTurnIndex());
        }
        if (msg.getCreatedAt() != null) {
            response.put("timestamp", msg.getCreatedAt().toString());
        }
        return response;
    }
}