package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ActiveAgentDto;
import com.azhukov.agent.api.dto.ApproveMemoryRequest;
import com.azhukov.agent.api.dto.ApprovalRequest;
import com.azhukov.agent.api.dto.ApproveRequest;
import com.azhukov.agent.api.dto.BackgroundRequest;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.CompressRequest;
import com.azhukov.agent.api.dto.ContextInfoDto;
import com.azhukov.agent.api.dto.DenyRequest;
import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.MemoryDto;
import com.azhukov.agent.api.dto.PendingMemoryDto;
import com.azhukov.agent.api.dto.RejectMemoryRequest;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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

    @GetMapping("/sessions")
    public List<SessionSummaryDto> sessions() {
        return agentRuntimeService.listSessions();
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

    @GetMapping("/agent/agents")
    public List<ActiveAgentDto> agents() {
        return agentRuntimeService.listActiveAgents();
    }

    @GetMapping("/agent/insights")
    public InsightsDto insights() {
        return agentRuntimeService.getInsights();
    }

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
}
