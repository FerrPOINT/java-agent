package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.CompressRequest;
import com.azhukov.agent.api.dto.ContextInfoDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.SnapshotRequest;
import com.azhukov.agent.api.dto.UndoRequest;
import com.azhukov.agent.api.dto.UsageDto;
import jakarta.validation.Valid;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.service.TodoService;
import com.azhukov.agent.api.dto.TodoDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Sessions", description = "Session lifecycle, compression, undo, model switching, snapshots")
public class SessionController {

    private final AgentRuntimeService agentRuntimeService;
    private final DomainDtoMapper domainDtoMapper;
    private final AgentProperties properties;
    private final CheckpointManager checkpointManager;
    private final TodoService todoService;
    private final com.azhukov.agent.persistence.repository.MessageRepository messageRepository;

    @Operation(summary = "List all sessions")
    @GetMapping("/sessions")
    public List<SessionSummaryDto> sessions() {
        return agentRuntimeService.listSessions();
    }

    @Operation(summary = "Create a new chat session")
    @PostMapping("/agent/session")
    public ResponseEntity<SessionSummaryDto> createSession(@RequestBody(required = false) CreateSessionRequest request) {
        String userId = request != null && request.userId() != null ? request.userId() : "user-1";
        Session session = agentRuntimeService.createSession(userId, "openai-compatible", properties.getModel().getModelName());
        SessionSummaryDto dto = domainDtoMapper.toSessionSummaryDto(session);
        return ResponseEntity.created(URI.create("/api/v1/agent/session/" + session.id())).body(dto);
    }

    public record CreateSessionRequest(String userId) {}

    /**
     * Hermes parity (/save): full message history for session export.
     * Returns id, role, content, turnIndex, createdAt per message.
     */
    @GetMapping("/agent/session/{sessionId}/history")
    public java.util.List<java.util.Map<String, Object>> history(@PathVariable java.util.UUID sessionId) {
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
            .map(m -> java.util.Map.<String, Object>of(
                "role", m.getRole() == null ? "?" : m.getRole(),
                "content", m.getContent() == null ? "" : m.getContent(),
                "turnIndex", m.getTurnIndex() == null ? 0 : m.getTurnIndex(),
                "createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString()))
            .toList();
    }

    @GetMapping("/agent/session/{sessionId}/context")
    public ContextInfoDto getContext(@PathVariable UUID sessionId) {
        return agentRuntimeService.getContext(sessionId);
    }

    @PostMapping("/agent/session/{sessionId}/reset")
    public void resetSession(@PathVariable UUID sessionId) {
        agentRuntimeService.resetSession(sessionId);
    }

    @GetMapping("/agent/session/{sessionId}/usage")
    public UsageDto getUsage(@PathVariable UUID sessionId) {
        return agentRuntimeService.getUsage(sessionId);
    }

    @GetMapping("/agent/sessions/{userId}")
    public List<SessionSummaryDto> sessionsByUserId(@PathVariable String userId) {
        return agentRuntimeService.listSessionsByUserId(userId);
    }

    @Operation(summary = "Compress session context to reduce token usage")
    @PostMapping("/agent/session/{sessionId}/compress")
    public String compressSession(@PathVariable UUID sessionId, @RequestBody(required = false) CompressRequest request) {
        String focusTopic = request != null ? request.focusTopic() : null;
        Integer keepLastN = request != null ? request.keepLastN() : null;
        // If focusTopic is null, fall back to focus() for backward compatibility
        if (focusTopic == null && request != null) {
            focusTopic = request.focus();
        }
        agentRuntimeService.compressSession(sessionId, focusTopic, keepLastN);
        return "Context compressed." + (focusTopic != null ? " Focus: " + focusTopic : "")
            + (keepLastN != null ? " Kept last " + keepLastN : "");
    }

    @Operation(summary = "Undo the last N turns of a session")
    @PostMapping("/agent/session/{sessionId}/undo")
    public int undoTurns(@PathVariable UUID sessionId, @RequestParam(defaultValue = "1") int turns) {
        return agentRuntimeService.undoTurns(sessionId, turns);
    }

    // Convenience endpoints without sessionId path variable (for E2E / CLI usage)

    @PostMapping("/agent/compress")
    public String compressSessionBody(@RequestBody(required = false) CompressRequest request) {
        UUID sessionId = request != null ? request.sessionId() : null;
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        String focusTopic = request.focusTopic();
        if (focusTopic == null) {
            focusTopic = request.focus();
        }
        Integer keepLastN = request.keepLastN();
        agentRuntimeService.compressSession(sessionId, focusTopic, keepLastN);
        return "Context compressed." + (focusTopic != null ? " Focus: " + focusTopic : "")
            + (keepLastN != null ? " Kept last " + keepLastN : "");
    }

    @PostMapping("/agent/undo")
    public int undoTurnsBody(@Valid @RequestBody UndoRequest request) {
        UUID sessionId = request.sessionId();
        int turns = request.effectiveTurns();
        return agentRuntimeService.undoTurns(sessionId, turns);
    }

    // ── Model switching ──

    @PostMapping("/agent/model")
    public Map<String, Object> switchModel(@Valid @RequestBody SwitchModelRequest request) {
        UUID sessionId = request.sessionId();
        String model = request.model();
        String provider = request.provider();
        if (sessionId == null || model == null || model.isBlank()) {
            return Map.of("ok", false, "error", "sessionId and model are required");
        }
        try {
            agentRuntimeService.switchModel(sessionId, model, provider);
            return Map.of("ok", true, "model", model,
                "provider", provider != null ? provider : "",
                "sessionId", sessionId.toString());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @GetMapping("/agent/model")
    public Map<String, Object> getCurrentModel(@RequestParam(required = false) UUID sessionId) {
        if (sessionId != null) {
            try {
                var session = agentRuntimeService.getContext(sessionId);
                return Map.of(
                    "sessionId", sessionId.toString(),
                    "messageCount", session.messageCount(),
                    "tokenEstimate", session.tokenEstimate()
                );
            } catch (Exception e) {
                return Map.of("error", e.getMessage());
            }
        }
        return Map.of("error", "sessionId required");
    }

    public record SwitchModelRequest(UUID sessionId, String model, String provider) {}

    // ── Snapshot ──

    @PostMapping("/agent/snapshot")
    public ResponseEntity<SessionSummaryDto> createSnapshot(@Valid @RequestBody SnapshotRequest body) {
        String description = body.description() != null ? body.description() : "";
        checkpointManager.snapshot(description);
        return ResponseEntity.ok().build();
    }

    // ── Branch session ──

    @PostMapping("/agent/session/{sessionId}/branch")
    public SessionSummaryDto branchSession(@PathVariable UUID sessionId, @RequestParam(required = false) String name) {
        return agentRuntimeService.branchSession(sessionId, name);
    }

    // ── Plan (todo list for session) ──

    @Operation(summary = "Get the current plan (todo list) for a session")
    @GetMapping("/agent/session/{sessionId}/plan")
    public Map<String, Object> getPlan(@PathVariable UUID sessionId) {
        List<TodoDto> todos = todoService.listBySessionId(sessionId);
        return Map.of(
            "session_id", sessionId.toString(),
            "todos", todos
        );
    }
}