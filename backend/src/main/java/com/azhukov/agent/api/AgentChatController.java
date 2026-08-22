package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ApproveRequest;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.api.dto.BackgroundRequest;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.RefineRequest;
import com.azhukov.agent.api.dto.DenyRequest;
import com.azhukov.agent.api.dto.DoctorDto;
import com.azhukov.agent.api.dto.StopRequest;
import com.azhukov.agent.api.dto.SteerRequest;
import com.azhukov.agent.api.dto.TtsRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.metrics.AgentMetrics;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Agent Chat", description = "Chat, streaming, steer, approvals, TTS, transcription")
public class AgentChatController {

    private final AgentRuntimeService agentRuntimeService;
    private final AgentStreamingService streamingService;
    private final MemoryProvider memoryProvider;
    private final SkillManager skillManager;
    private final TtsService ttsService;
    private final TranscriptionService transcriptionService;
    private final SteerBuffer steerBuffer;
    private final InterruptToken interruptToken;
    private final org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.core.memory.BackgroundReviewService> backgroundReviewServiceProvider;
    private final com.azhukov.agent.persistence.repository.BackgroundJobRepository backgroundJobRepository;
    private final com.azhukov.agent.persistence.repository.SessionRepository sessionRepository;
    private final com.azhukov.agent.persistence.repository.MessageRepository messageRepository;
    private final com.azhukov.agent.persistence.mapper.MessageMapper messageMapper;
    private final ApprovalQueue approvalQueue;
    private final AgentProperties properties;
    private final AgentMetrics agentMetrics;

    @Operation(summary = "Send a chat message and get a synchronous response")
    @PostMapping("/agent/chat")
    public ChatResponseDto chat(@Valid @RequestBody ChatRequest request) {
        if (agentMetrics != null) agentMetrics.incrementChatRequests();
        return agentRuntimeService.runTurn(request);
    }

    @Operation(summary = "Stream a chat response via SSE")
    @PostMapping(value = "/agent/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody ChatRequest request) {
        if (agentMetrics != null) {
            agentMetrics.incrementChatRequests();
            agentMetrics.incrementChatStreaming();
        }
        return streamingService.streamTurn(request);
    }

    @PostMapping("/agent/delegate")
    public ChatResponseDto delegate(@Valid @RequestBody ChatRequest request) {
        if (agentMetrics != null) agentMetrics.incrementChatRequests();
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

    // ── Stop (interrupt active turn) ──

    @Operation(summary = "Stop/interrupt the active agent turn")
    @PostMapping("/agent/stop")
    public Map<String, Object> stop(@Valid @RequestBody(required = false) StopRequest request) {
        UUID sessionId = request != null ? request.sessionId() : null;
        if (sessionId != null) {
            interruptToken.cancel(sessionId);
        }
        return Map.of("ok", true, "message", "Agent stopped");
    }


    // ── Steer endpoint ──

    @Operation(summary = "Inject a steer message into an active streaming turn")
    @PostMapping("/agent/steer")
    public Map<String, Object> steer(@Valid @RequestBody SteerRequest request) {
        if (request.sessionId() == null || request.text() == null || request.text().isBlank()) {
            return Map.of("accepted", false, "reason", "sessionId and text are required");
        }
        boolean accepted = steerBuffer.steer(request.sessionId(), request.text());
        return Map.of("accepted", accepted, "sessionId", request.sessionId().toString());
    }


    // ── Background ──

    @PostMapping("/agent/background")
    public java.util.Map<String, Object> background(@Valid @RequestBody BackgroundRequest request) {
        // Hermes parity: job model — id + status, result via GET /agent/background/{id}
        java.util.UUID jobId = agentRuntimeService.submitBackgroundJob(
            request.prompt(), request.sessionId(), false);
        return java.util.Map.of("jobId", jobId.toString(), "status", "PENDING");
    }

    @org.springframework.web.bind.annotation.GetMapping("/agent/background/{id}")
    public java.util.Map<String, Object> backgroundStatus(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        var job = backgroundJobRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown background job: " + id));
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("jobId", job.getId().toString());
        out.put("status", job.getStatus());
        if (job.getSessionId() != null) {
            out.put("sessionId", job.getSessionId().toString());
        }
        if (job.getResult() != null) {
            out.put("result", job.getResult());
        }
        if (job.getFinishedAt() != null) {
            out.put("finishedAt", job.getFinishedAt().toString());
        }
        return out;
    }

    // ── Refine (Hermes /refine) ──

    @Operation(summary = "Run the memory/skill background review on demand (Hermes /refine parity)")
    @PostMapping("/agent/refine")
    public Map<String, Object> refine(@Valid @RequestBody RefineRequest request) {
        var sessionOpt = sessionRepository.findById(request.sessionId());
        if (sessionOpt.isEmpty()) {
            return Map.of("accepted", false, "reason", "session not found");
        }
        var session = sessionOpt.get();
        List<Message> history = this.messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
            .stream().map(messageMapper::toDomain).toList();
        if (history.isEmpty()) {
            return Map.of("accepted", false, "reason", "nothing to refine — the conversation is empty");
        }
        // Hermes: review_skills iff "skill_manage" in agent.valid_tool_names —
        // here: the skills toolset is enabled for the session's toolsets.
        boolean reviewSkills = properties.getSkills().getDefaultToolsets().contains("skills");
        backgroundReviewServiceProvider.getObject().reviewTurn(session.getId(), history, session.getUserId(),
            true, reviewSkills, request.focus());
        String focus = request.focus() == null ? "" : request.focus().strip();
        return Map.of(
            "accepted", true,
            "sessionId", session.getId().toString(),
            "message", focus.isEmpty()
                ? "Reviewing this conversation in the background — any memory/skill updates will be reported when done."
                : "Reviewing this conversation in the background (focus: " + focus + ") — updates reported when done."
        );
    }

    // ── Approve / Deny ──

    @PostMapping("/agent/approve")
    public String approve(@Valid @RequestBody ApproveRequest request) {
        if (request.all()) {
            for (var pending : approvalQueue.getPendingApprovals()) {
                approvalQueue.approve(pending.sessionId(), "approve", null);
            }
            return "Approved all pending approvals";
        }
        String scope = request.scope();
        if (scope != null && !scope.isBlank()) {
            try {
                UUID sessionId = UUID.fromString(scope);
                var result = approvalQueue.approve(sessionId, "approve", null);
                if (result == null) return "No pending approval for session: " + scope;
                return "Approved: " + scope;
            } catch (IllegalArgumentException e) {
                return "Invalid session ID: " + scope;
            }
        }
        var pendingList = approvalQueue.getPendingApprovals();
        if (pendingList.isEmpty()) return "No pending approvals";
        approvalQueue.approve(pendingList.get(0).sessionId(), "approve", null);
        return "Approved: " + pendingList.get(0).sessionId();
    }

    @PostMapping("/agent/deny")
    public String deny(@Valid @RequestBody DenyRequest request) {
        if (request.all()) {
            for (var pending : approvalQueue.getPendingApprovals()) {
                approvalQueue.deny(pending.sessionId(), null);
            }
            return "Denied all pending approvals";
        }
        var pendingList = approvalQueue.getPendingApprovals();
        if (pendingList.isEmpty()) return "No pending approvals";
        approvalQueue.deny(pendingList.get(0).sessionId(), null);
        return "Denied: " + pendingList.get(0).sessionId();
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

    // ── TTS endpoint ──

    @PostMapping(value = "/agent/tts", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] tts(@Valid @RequestBody TtsRequest request) {
        return ttsService.synthesize(request.text(), request.voice());
    }


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

    // ── Debug report ──

    @Operation(summary = "Upload a debug report (system info + logs) and get a shareable link")
    @PostMapping(value = "/agent/debug-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> debugReport(
            @org.springframework.web.bind.annotation.RequestPart(value = "systemInfo", required = false) String systemInfo,
            @org.springframework.web.bind.annotation.RequestPart(value = "logs", required = false) String logs) {
        String reportId = UUID.randomUUID().toString();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", reportId);
        report.put("link", "https://debug.agent.local/r/" + reportId);
        report.put("timestamp", java.time.Instant.now().toString());
        // Collect system properties as debug info
        Map<String, String> sysProps = new LinkedHashMap<>();
        sysProps.put("java.version", System.getProperty("java.version", "unknown"));
        sysProps.put("java.vm.name", System.getProperty("java.vm.name", "unknown"));
        sysProps.put("os.name", System.getProperty("os.name", "unknown"));
        sysProps.put("os.arch", System.getProperty("os.arch", "unknown"));
        sysProps.put("os.version", System.getProperty("os.version", "unknown"));
        sysProps.put("user.dir", System.getProperty("user.dir", "unknown"));
        report.put("systemProperties", sysProps);
        if (systemInfo != null && !systemInfo.isBlank()) {
            report.put("providedSystemInfo", systemInfo);
        }
        if (logs != null && !logs.isBlank()) {
            report.put("logsIncluded", true);
            report.put("logsSize", logs.length());
        } else {
            report.put("logsIncluded", false);
        }
        report.put("message", "Debug report uploaded.");
        return report;
    }
}