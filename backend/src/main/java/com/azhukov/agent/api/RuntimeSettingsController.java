package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ActiveAgentDto;
import com.azhukov.agent.api.dto.AgentConfigDto;
import com.azhukov.agent.api.dto.BrowserRequest;
import com.azhukov.agent.api.dto.CodexRuntimeModelRequest;
import com.azhukov.agent.api.dto.CreditsDto;
import com.azhukov.agent.api.dto.DisableToolRequest;
import com.azhukov.agent.api.dto.EnableToolRequest;
import com.azhukov.agent.api.dto.FastModeRequest;
import com.azhukov.agent.api.dto.GoalRequest;
import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.PersonalityRequest;
import com.azhukov.agent.api.dto.QueueRequest;
import com.azhukov.agent.api.dto.ReasoningRequest;
import com.azhukov.agent.api.dto.SessionIdRequest;
import com.azhukov.agent.api.dto.SubgoalRequest;
import com.azhukov.agent.api.dto.TitleRequest;
import com.azhukov.agent.api.dto.VoiceModeRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.security.UrlSafetyHandler;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.CliRuntimeSettingsService;
import com.azhukov.agent.service.RuntimeConfigService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Runtime Settings", description = "Config, reasoning, tools, goals, credits, codex runtime")
public class RuntimeSettingsController {

    private final CliRuntimeSettingsService cliRuntimeSettingsService;
    private final AgentProperties properties;
    private final MemoryProvider memoryProvider;
    private final TtsService ttsService;
    private final TranscriptionService transcriptionService;
    private final RuntimeConfigService runtimeConfigService;
    private final AgentRuntimeService agentRuntimeService;
    private final UrlSafetyHandler urlSafetyHandler;

    // ── Config ──

    @Operation(summary = "Get agent configuration")
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
    public ResponseEntity<Void> setReasoning(@Valid @RequestBody ReasoningRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String effort = body.effort();
        cliRuntimeSettingsService.setReasoningEffort(sessionId, effort);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/fast-mode")
    public ResponseEntity<Boolean> toggleFastMode(@Valid @RequestBody FastModeRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        boolean enabled = Boolean.parseBoolean(body.enabled() != null ? body.enabled() : "true");
        boolean newState = cliRuntimeSettingsService.toggleFastMode(sessionId, enabled);
        return ResponseEntity.ok(newState);
    }

    @PostMapping("/agent/voice-mode")
    public ResponseEntity<Boolean> toggleVoiceMode(@Valid @RequestBody VoiceModeRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        boolean enabled = Boolean.parseBoolean(body.enabled() != null ? body.enabled() : "true");
        boolean newState = cliRuntimeSettingsService.toggleVoiceMode(sessionId, enabled);
        return ResponseEntity.ok(newState);
    }

    @GetMapping("/agent/tools")
    public ResponseEntity<List<String>> listTools() {
        return ResponseEntity.ok(cliRuntimeSettingsService.listToolNames());
    }

    @PostMapping("/agent/tools/enable")
    public ResponseEntity<Void> enableTool(@Valid @RequestBody EnableToolRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String toolName = body.toolName();
        cliRuntimeSettingsService.enableTool(sessionId, toolName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/tools/disable")
    public ResponseEntity<Void> disableTool(@Valid @RequestBody DisableToolRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String toolName = body.toolName();
        cliRuntimeSettingsService.disableTool(sessionId, toolName);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/personality")
    public ResponseEntity<Void> setPersonality(@Valid @RequestBody PersonalityRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String personality = body.personality();
        cliRuntimeSettingsService.setPersonality(sessionId, personality);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/subgoal")
    public ResponseEntity<Void> setSubgoal(@Valid @RequestBody SubgoalRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String subgoal = body.subgoal();
        String append = body.append() != null ? body.append() : "false";
        if (Boolean.parseBoolean(append)) {
            cliRuntimeSettingsService.appendSubgoal(sessionId, subgoal);
        } else {
            cliRuntimeSettingsService.setSubgoal(sessionId, subgoal);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/goal")
    public ResponseEntity<Void> setGoal(@Valid @RequestBody GoalRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String goal = body.goal();
        cliRuntimeSettingsService.setGoal(sessionId, goal);
        cliRuntimeSettingsService.setGoalPaused(sessionId, false);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/agent/goal")
    public ResponseEntity<Map<String, Object>> getGoal(@RequestParam UUID sessionId) {
        String goal = cliRuntimeSettingsService.getGoal(sessionId);
        boolean paused = cliRuntimeSettingsService.isGoalPaused(sessionId);
        return ResponseEntity.ok(Map.of(
            "goal", goal != null ? goal : "",
            "paused", paused
        ));
    }

    @PostMapping("/agent/goal/pause")
    public ResponseEntity<Void> pauseGoal(@Valid @RequestBody SessionIdRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        cliRuntimeSettingsService.setGoalPaused(sessionId, true);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/goal/resume")
    public ResponseEntity<Void> resumeGoal(@Valid @RequestBody SessionIdRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        cliRuntimeSettingsService.setGoalPaused(sessionId, false);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/agent/goal")
    public ResponseEntity<Void> clearGoal(@Valid @RequestBody SessionIdRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        cliRuntimeSettingsService.clearGoal(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/goal/clear")
    public ResponseEntity<Void> clearGoalPost(@Valid @RequestBody SessionIdRequest body) {
        return clearGoal(body);
    }

    @PostMapping("/agent/session/title")
    public ResponseEntity<Void> setTitle(@Valid @RequestBody TitleRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String title = body.title();
        cliRuntimeSettingsService.setTitle(sessionId, title);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/queue")
    public ResponseEntity<Void> queuePrompt(@Valid @RequestBody QueueRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String queued = body.queued();
        cliRuntimeSettingsService.setQueuedPrompt(sessionId, queued);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/browser")
    public ResponseEntity<Void> setBrowserCdp(@Valid @RequestBody BrowserRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        String cdpUrl = body.cdpUrl();
        String validationError = urlSafetyHandler.validate(cdpUrl);
        if (validationError != null) {
            log.warn("Invalid cdpUrl rejected: {}", validationError);
            return ResponseEntity.badRequest().build();
        }
        cliRuntimeSettingsService.setCdpUrl(sessionId, cdpUrl);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/state/reset")
    public ResponseEntity<Void> resetState(@RequestBody(required = false) SessionIdRequest body) {
        if (body == null || body.sessionId() == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID sessionId = UUID.fromString(body.sessionId());
        cliRuntimeSettingsService.resetSessionState(sessionId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/agent/subgoal")
    public ResponseEntity<Void> clearSubgoals(@Valid @RequestBody SessionIdRequest body) {
        UUID sessionId = UUID.fromString(body.sessionId());
        cliRuntimeSettingsService.clearSubgoals(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/agent/subgoal/clear")
    public ResponseEntity<Void> clearSubgoalsPost(@Valid @RequestBody SessionIdRequest body) {
        return clearSubgoals(body);
    }

    // ── Credits / Usage ──

    @Operation(summary = "Get credits/usage summary")
    @GetMapping("/agent/credits")
    public CreditsDto getCredits() {
        return agentRuntimeService.getCreditsSummary();
    }

    // ── Insights ──

    @GetMapping("/agent/insights")
    public InsightsDto getInsights() {
        return agentRuntimeService.getInsights();
    }

    // ── Agents ──

    @GetMapping("/agent/agents")
    public List<ActiveAgentDto> getAgents() {
        return agentRuntimeService.listActiveAgents();
    }

    // ── Codex Runtime ──

    @GetMapping("/agent/codex-runtime")
    public Map<String, Object> codexRuntimeStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        String override = runtimeConfigService.getModelOverride();
        status.put("model", override != null ? override : properties.getModel().getModelName());
        status.put("provider", properties.getModel().getProvider());
        status.put("maxRetries", properties.getModel().getMaxRetries());
        status.put("maxTokens", properties.getModel().getMaxTokens());
        status.put("timeoutSeconds", properties.getModel().getTimeoutSeconds());
        status.put("modelOverride", override);
        return status;
    }

    @PostMapping("/agent/codex-runtime/model")
    public void codexRuntimeModel(@Valid @RequestBody CodexRuntimeModelRequest body) {
        String model = body.model();
        runtimeConfigService.setModelOverride(model);
    }

    @PostMapping("/agent/codex-runtime/reset")
    public void codexRuntimeReset() {
        cliRuntimeSettingsService.resetAllSessions();
        runtimeConfigService.clearModelOverride();
    }

    // ── Restart / reload ──

    @PostMapping("/agent/restart")
    public void restart() {
        agentRuntimeService.restart();
    }

    @PostMapping("/agent/reload-mcp")
    public void reloadMcp() {
        agentRuntimeService.reloadMcp();
    }
}