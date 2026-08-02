package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ActiveAgentDto;
import com.azhukov.agent.api.dto.ApproveMemoryRequest;
import com.azhukov.agent.api.dto.ApprovalRequest;
import com.azhukov.agent.api.dto.ApproveRequest;
import com.azhukov.agent.api.dto.BackgroundRequest;
import com.azhukov.agent.api.dto.AgentConfigDto;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.DoctorDto;
import com.azhukov.agent.api.dto.CompressRequest;
import com.azhukov.agent.api.dto.ContextInfoDto;
import com.azhukov.agent.api.dto.DenyRequest;
import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.MemoryDto;
import com.azhukov.agent.api.dto.PendingMemoryDto;
import com.azhukov.agent.api.dto.RejectMemoryRequest;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.service.CliRuntimeSettingsService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {

    private final AgentRuntimeService agentRuntimeService;
    private final AgentStreamingService streamingService;
    private final MemoryProvider memoryProvider;
    private final SkillManager skillManager;
    private final CheckpointManager checkpointManager;
    private final TtsService ttsService;
    private final TranscriptionService transcriptionService;
    private final SteerBuffer steerBuffer;
    private final ApprovalQueue approvalQueue;
    private final CliRuntimeSettingsService cliRuntimeSettingsService;
    private final AgentProperties properties;
    private final DomainDtoMapper domainDtoMapper;

    @PostMapping("/agent/chat")
    public ChatResponseDto chat(@Valid @RequestBody ChatRequest request) {
        return agentRuntimeService.runTurn(request);
    }

    @PostMapping(value = "/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        return streamingService.streamTurn(request);
    }

    @PostMapping("/agent/delegate")
    public ChatResponseDto delegate(@Valid @RequestBody ChatRequest request) {
        return agentRuntimeService.runDelegate(request);
    }

    // ── Doctor / diagnostics ──

    @GetMapping("/agent/doctor")
    public DoctorDto doctor() {
        return new DoctorDto(
            properties.getName(),
            "0.0.1-SNAPSHOT",
            "UP",
            properties.getModel().getModelName(),
            properties.getModel().getProvider(),
            properties.getCore().getMaxTurns(),
            properties.getBudget().getMaxModelCallsPerTurn(),
            memoryProvider != null,
            ttsService != null,
            transcriptionService != null,
            skillManager.listSkillNames().size(),
            0L
        );
    }

    // ── Config ──

    @GetMapping("/agent/config")
    public AgentConfigDto config() {
        return new AgentConfigDto(
            properties.getName(),
            properties.getModel().getModelName(),
            properties.getModel().getProvider(),
            properties.getModel().getBaseUrl(),
            properties.getCore().getMaxTurns(),
            properties.getBudget().getMaxModelCallsPerTurn(),
            properties.getModel().getMaxTokens(),
            properties.getModel().getTemperature(),
            properties.getModel().getTimeoutSeconds(),
            properties.getCore().getDefaultSystemPrompt(),
            properties.getCore().getReasoningConfig(),
            Map.of(
                "memory", memoryProvider != null,
                "tts", ttsService != null,
                "transcription", transcriptionService != null,
                "browser", properties.getBrowser() != null,
                "cron", properties.getCron() != null && properties.getCron().isEnabled()
            )
        );
    }

    // ── CLI runtime settings endpoints ──

    @PostMapping("/agent/reasoning")
    public ResponseEntity<Void> setReasoning(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String effort = body.get("effort");
        cliRuntimeSettingsService.setReasoningEffort(sessionId, effort);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/fast-mode")
    public ResponseEntity<Boolean> toggleFastMode(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        boolean enabled = Boolean.parseBoolean(body.getOrDefault("enabled", "true"));
        boolean newState = cliRuntimeSettingsService.toggleFastMode(sessionId, enabled);
        return ResponseEntity.ok(newState);
    }

    @PostMapping("/agent/voice-mode")
    public ResponseEntity<Boolean> toggleVoiceMode(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        boolean enabled = Boolean.parseBoolean(body.getOrDefault("enabled", "true"));
        boolean newState = cliRuntimeSettingsService.toggleVoiceMode(sessionId, enabled);
        return ResponseEntity.ok(newState);
    }

    @GetMapping("/agent/tools")
    public ResponseEntity<java.util.List<String>> listTools() {
        return ResponseEntity.ok(cliRuntimeSettingsService.listToolNames());
    }

    @PostMapping("/agent/tools/enable")
    public ResponseEntity<Void> enableTool(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String toolName = body.get("toolName");
        cliRuntimeSettingsService.enableTool(sessionId, toolName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/tools/disable")
    public ResponseEntity<Void> disableTool(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String toolName = body.get("toolName");
        cliRuntimeSettingsService.disableTool(sessionId, toolName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/personality")
    public ResponseEntity<Void> setPersonality(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String personality = body.get("personality");
        cliRuntimeSettingsService.setPersonality(sessionId, personality);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/subgoal")
    public ResponseEntity<Void> setSubgoal(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String subgoal = body.get("subgoal");
        String append = body.getOrDefault("append", "false");
        if (Boolean.parseBoolean(append)) {
            cliRuntimeSettingsService.appendSubgoal(sessionId, subgoal);
        } else {
            cliRuntimeSettingsService.setSubgoal(sessionId, subgoal);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/goal")
    public ResponseEntity<Void> setGoal(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String goal = body.get("goal");
        if (goal == null || goal.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        cliRuntimeSettingsService.setGoal(sessionId, goal);
        cliRuntimeSettingsService.setGoalPaused(sessionId, false);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/goal/pause")
    public ResponseEntity<Void> pauseGoal(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        cliRuntimeSettingsService.setGoalPaused(sessionId, true);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/goal/resume")
    public ResponseEntity<Void> resumeGoal(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        cliRuntimeSettingsService.setGoalPaused(sessionId, false);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/agent/goal")
    public ResponseEntity<Void> clearGoal(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        cliRuntimeSettingsService.clearGoal(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/goal/clear")
    public ResponseEntity<Void> clearGoalPost(@RequestBody java.util.Map<String, String> body) {
        return clearGoal(body);
    }

    @PostMapping("/agent/session/title")
    public ResponseEntity<Void> setTitle(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String title = body.get("title");
        cliRuntimeSettingsService.setTitle(sessionId, title);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/snapshot")
    public ResponseEntity<com.azhukov.agent.api.dto.SessionSummaryDto> createSnapshot(@RequestBody java.util.Map<String, String> body) {
        String description = body.getOrDefault("description", "");
        checkpointManager.snapshot(description);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/queue")
    public ResponseEntity<Void> queuePrompt(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String queued = body.get("queued");
        cliRuntimeSettingsService.setQueuedPrompt(sessionId, queued);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/browser")
    public ResponseEntity<Void> setBrowserCdp(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        String cdpUrl = body.get("cdpUrl");
        cliRuntimeSettingsService.setCdpUrl(sessionId, cdpUrl);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/state/reset")
    public ResponseEntity<Void> resetState(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        cliRuntimeSettingsService.resetSessionState(sessionId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/agent/subgoal")
    public ResponseEntity<Void> clearSubgoals(@RequestBody java.util.Map<String, String> body) {
        UUID sessionId = UUID.fromString(body.get("sessionId"));
        cliRuntimeSettingsService.clearSubgoals(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/subgoal/clear")
    public ResponseEntity<Void> clearSubgoalsPost(@RequestBody java.util.Map<String, String> body) {
        return clearSubgoals(body);
    }

    @GetMapping("/sessions")
    public List<SessionSummaryDto> sessions() {
        return agentRuntimeService.listSessions();
    }

    @PostMapping("/agent/session")
    public ResponseEntity<SessionSummaryDto> createSession(@RequestBody(required = false) CreateSessionRequest request) {
        String userId = request != null && request.userId() != null ? request.userId() : "user-1";
        Session session = agentRuntimeService.createSession(userId, "openai-compatible", properties.getModel().getModelName());
        SessionSummaryDto dto = domainDtoMapper.toSessionSummaryDto(session);
        return ResponseEntity.created(java.net.URI.create("/api/v1/agent/session/" + session.id())).body(dto);
    }

    public record CreateSessionRequest(String userId) {}

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

    @GetMapping("/agent/memory")
    public List<String> memory() {
        return memoryProvider.recall("default", "", 100);
    }

    // ── Memory management endpoints (Stage 6.1-6.6) ──

    @GetMapping("/agent/memory/pending/{userId}")
    public List<PendingMemoryDto> listPendingMemory(@PathVariable String userId) {
        return agentRuntimeService.listPendingMemory(userId);
    }

    @PostMapping("/agent/memory/approve")
    public boolean approveMemory(@RequestBody ApproveMemoryRequest request) {
        return agentRuntimeService.approvePendingMemory(request);
    }

    @PostMapping("/agent/memory/reject")
    public boolean rejectMemory(@RequestBody RejectMemoryRequest request) {
        return agentRuntimeService.rejectPendingMemory(request);
    }

    @PostMapping("/agent/memory/approval")
    public void setApproval(@RequestBody ApprovalRequest request) {
        agentRuntimeService.setMemoryApproval(request.enabled());
    }

    @GetMapping("/agent/memory/approval")
    public boolean getApproval() {
        return agentRuntimeService.isMemoryApprovalEnabled();
    }

    @GetMapping("/agent/memory/all/{userId}")
    public List<MemoryDto> listAllMemory(@PathVariable String userId) {
        return agentRuntimeService.listAllMemory(userId);
    }

    @DeleteMapping("/agent/memory/{userId}/{entryId}")
    public void deleteMemory(@PathVariable String userId, @PathVariable UUID entryId) {
        agentRuntimeService.deleteMemory(userId, entryId);
    }

    @GetMapping("/agent/skills")
    public List<String> skills() {
        return skillManager.listSkillNames();
    }

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

    @PostMapping("/agent/session/{sessionId}/undo")
    public int undoTurns(@PathVariable UUID sessionId, @RequestParam(defaultValue = "1") int turns) {
        return agentRuntimeService.undoTurns(sessionId, turns);
    }

    @PostMapping("/agent/approve")
    public String approve(@RequestBody ApproveRequest request) {
        boolean all = request.all();
        String scope = request.scope();
        return "Approved" + (all ? " all" : "") + (scope != null ? " (" + scope + ")" : "");
    }

    @PostMapping("/agent/deny")
    public String deny(@RequestBody DenyRequest request) {
        boolean all = request.all();
        return "Denied" + (all ? " all" : "");
    }

    // ── Tool approval endpoints (A12) ──

    @GetMapping("/agent/approvals/pending")
    public List<ApprovalQueue.PendingApproval> pendingApprovals() {
        return approvalQueue.getPendingApprovals();
    }

    @PostMapping("/agent/approvals/{sessionId}/approve")
    public ApprovalQueue.PendingApproval approveTool(@PathVariable UUID sessionId, @RequestParam(required = false, defaultValue = "approve") String decision, @RequestBody(required = false) String note) {
        return approvalQueue.approve(sessionId, decision, note);
    }

    @PostMapping("/agent/approvals/{sessionId}/deny")
    public ApprovalQueue.PendingApproval denyTool(@PathVariable UUID sessionId, @RequestBody(required = false) String note) {
        return approvalQueue.deny(sessionId, note);
    }

    @GetMapping("/agent/agents")
    public List<ActiveAgentDto> agents() {
        return agentRuntimeService.listActiveAgents();
    }

    @GetMapping("/agent/insights")
    public InsightsDto insights() {
        return agentRuntimeService.getInsights();
    }

    // ── Model switching ──

    @PostMapping("/agent/model")
    public java.util.Map<String, Object> switchModel(@RequestBody SwitchModelRequest request) {
        UUID sessionId = request.sessionId();
        String model = request.model();
        String provider = request.provider();
        if (sessionId == null || model == null || model.isBlank()) {
            return java.util.Map.of("ok", false, "error", "sessionId and model are required");
        }
        try {
            agentRuntimeService.switchModel(sessionId, model, provider);
            return java.util.Map.of("ok", true, "model", model,
                "provider", provider != null ? provider : "",
                "sessionId", sessionId.toString());
        } catch (Exception e) {
            return java.util.Map.of("ok", false, "error", e.getMessage());
        }
    }

    @GetMapping("/agent/model")
    public java.util.Map<String, Object> getCurrentModel(@RequestParam(required = false) UUID sessionId) {
        if (sessionId != null) {
            try {
                var session = agentRuntimeService.getContext(sessionId);
                return java.util.Map.of(
                    "sessionId", sessionId.toString(),
                    "messageCount", session.messageCount(),
                    "tokenEstimate", session.tokenEstimate()
                );
            } catch (Exception e) {
                return java.util.Map.of("error", e.getMessage());
            }
        }
        return java.util.Map.of("error", "sessionId required");
    }

    public record SwitchModelRequest(UUID sessionId, String model, String provider) {}

    // ── Stop (interrupt active turn) ──

    @PostMapping("/agent/stop")
    public java.util.Map<String, Object> stop(@RequestBody(required = false) StopRequest request) {
        UUID sessionId = request != null ? request.sessionId() : null;
        if (sessionId != null) {
            steerBuffer.steer(sessionId, "__INTERRUPT__");
        }
        return java.util.Map.of("ok", true, "message", "Agent stopped");
    }

    public record StopRequest(UUID sessionId) {}

    // ── Skill content ──

    @GetMapping("/agent/skills/{name}")
    public java.util.Map<String, Object> getSkillContent(@PathVariable String name) {
        String content = skillManager.getSkill(name);
        if (content == null) {
            return java.util.Map.of("ok", false, "error", "Skill not found: " + name);
        }
        return java.util.Map.of("ok", true, "name", name, "content", content);
    }

    // ── Bundle install / uninstall ──

    @PostMapping("/agent/bundles/install")
    public java.util.Map<String, Object> installBundle(@RequestBody BundleRequest request) {
        try {
            agentRuntimeService.installBundle(request.bundleName());
            return java.util.Map.of("ok", true, "message", "Bundle installed: " + request.bundleName());
        } catch (Exception e) {
            return java.util.Map.of("ok", false, "error", e.getMessage());
        }
    }

    @PostMapping("/agent/bundles/uninstall")
    public java.util.Map<String, Object> uninstallBundle(@RequestBody BundleRequest request) {
        try {
            agentRuntimeService.uninstallBundle(request.bundleName());
            return java.util.Map.of("ok", true, "message", "Bundle uninstalled: " + request.bundleName());
        } catch (Exception e) {
            return java.util.Map.of("ok", false, "error", e.getMessage());
        }
    }

    public record BundleRequest(String bundleName) {}

    @PostMapping("/agent/restart")
    public void restart() {
        agentRuntimeService.restart();
    }

    @PostMapping("/agent/reload-mcp")
    public void reloadMcp() {
        agentRuntimeService.reloadMcp();
    }

    @PostMapping("/agent/reload-skills")
    public void reloadSkills() {
        agentRuntimeService.reloadSkills();
    }

    @PostMapping("/agent/reload")
    public void reloadAll() {
        agentRuntimeService.reloadSkills();
        agentRuntimeService.reloadMcp();
    }

    @GetMapping("/agent/diff")
    public JsonNode diffCheckpoints(@RequestParam UUID left, @RequestParam UUID right,
                                    @RequestParam(defaultValue = "context") String scope) {
        return checkpointManager.diff(left, right, scope);
    }

    @GetMapping("/agent/bundles")
    public List<String> bundles() {
        return agentRuntimeService.listBundles();
    }

    @PostMapping("/agent/session/{sessionId}/branch")
    public SessionSummaryDto branchSession(@PathVariable UUID sessionId, @RequestParam(required = false) String name) {
        return agentRuntimeService.branchSession(sessionId, name);
    }

    @PostMapping("/agent/background")
    public String background(@RequestBody BackgroundRequest request) {
        return agentRuntimeService.runBackground(request.prompt(), request.sessionId());
    }

    // ── Checkpoint / Rollback endpoints ──

    @PostMapping("/agent/checkpoint")
    public CheckpointEntity createCheckpoint(@RequestBody(required = false) CheckpointRequest request) {
        String description = request != null ? request.description() : "Manual checkpoint";
        return checkpointManager.snapshot(description);
    }

    @GetMapping("/agent/checkpoint")
    public List<CheckpointEntity> listCheckpoints() {
        return checkpointManager.list();
    }

    @PostMapping("/agent/checkpoint/{id}/restore")
    public String restoreCheckpoint(@PathVariable UUID id) {
        checkpointManager.restore(id);
        return "Checkpoint restored: " + id;
    }

    @DeleteMapping("/agent/checkpoint/{id}")
    public void deleteCheckpoint(@PathVariable UUID id) {
        checkpointManager.remove(id);
    }

    public record CheckpointRequest(String description) {}

    // ── Steer endpoint ──

    @PostMapping("/agent/steer")
    public java.util.Map<String, Object> steer(@RequestBody SteerRequest request) {
        if (request.sessionId() == null || request.text() == null || request.text().isBlank()) {
            return java.util.Map.of("accepted", false, "reason", "sessionId and text are required");
        }
        boolean accepted = steerBuffer.steer(request.sessionId(), request.text());
        return java.util.Map.of("accepted", accepted, "sessionId", request.sessionId().toString());
    }

    public record SteerRequest(UUID sessionId, String text) {}

    // ── TTS endpoint ──

    @PostMapping(value = "/agent/tts", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] tts(@RequestBody TtsRequest request) {
        return ttsService.synthesize(request.text(), request.voice());
    }

    public record TtsRequest(String text, String voice) {}

    // ── Transcription endpoint ──

    @PostMapping(value = "/agent/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public java.util.Map<String, String> transcribe(@org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            byte[] audio = file.getBytes();
            String text = transcriptionService.transcribe(audio);
            return java.util.Map.of("text", text != null ? text : "");
        } catch (Exception e) {
            return java.util.Map.of("text", "", "error", e.getMessage());
        }
    }
}
