package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ActiveAgentDto;
import com.azhukov.agent.api.dto.ApproveRequest;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.CompressRequest;
import com.azhukov.agent.api.dto.ContextInfoDto;
import com.azhukov.agent.api.dto.DenyRequest;
import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AgentController {

    private final AgentRuntimeService agentRuntimeService;
    private final AgentStreamingService streamingService;
    private final MemoryProvider memoryProvider;
    private final SkillManager skillManager;

    public AgentController(AgentRuntimeService agentRuntimeService,
                         AgentStreamingService streamingService,
                         MemoryProvider memoryProvider,
                         SkillManager skillManager) {
        this.agentRuntimeService = agentRuntimeService;
        this.streamingService = streamingService;
        this.memoryProvider = memoryProvider;
        this.skillManager = skillManager;
    }

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

    @GetMapping("/agent/skills")
    public List<String> skills() {
        return skillManager.listSkillNames();
    }

    @PostMapping("/agent/session/{sessionId}/compress")
    public void compressSession(@PathVariable UUID sessionId, @RequestBody(required = false) CompressRequest request) {
        String focus = request != null ? request.focus() : null;
        agentRuntimeService.compressSession(sessionId, focus);
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
}
