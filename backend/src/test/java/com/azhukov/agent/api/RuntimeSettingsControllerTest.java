package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ActiveAgentDto;
import com.azhukov.agent.api.dto.CreditsDto;
import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.security.UrlSafetyHandler;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.CliRuntimeSettingsService;
import com.azhukov.agent.service.RuntimeConfigService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Focused unit tests for {@link RuntimeSettingsController} — covers success,
 * bad input/error/edge cases for config, reasoning, tools, goals, credits,
 * codex-runtime, browser, state reset, and restart endpoints.
 */
@ExtendWith(MockitoExtension.class)
class RuntimeSettingsControllerTest {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private CliRuntimeSettingsService cliRuntimeSettingsService;
    @Mock private MemoryProvider memoryProvider;
    @Mock private TtsService ttsService;
    @Mock private TranscriptionService transcriptionService;
    @Mock private RuntimeConfigService runtimeConfigService;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private UrlSafetyHandler urlSafetyHandler;

    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        RuntimeSettingsController controller = new RuntimeSettingsController(
            cliRuntimeSettingsService,
            properties,
            memoryProvider,
            ttsService,
            transcriptionService,
            runtimeConfigService,
            agentRuntimeService,
            urlSafetyHandler
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    // ── Config ──

    @Test
    void configReturnsAllFieldsWithFeatures() throws Exception {
        mockMvc.perform(get("/api/v1/agent/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(properties.getName()))
            .andExpect(jsonPath("$.maxTurns").value(properties.getCore().getMaxTurns()))
            .andExpect(jsonPath("$.features.memory").value(true))
            .andExpect(jsonPath("$.features.tts").value(true))
            .andExpect(jsonPath("$.features.transcription").value(true))
            .andExpect(jsonPath("$.features.browser").value(true))
            .andExpect(jsonPath("$.features.cron").value(false)); // cron disabled by default
    }

    // ── Reasoning levels ──

    @Test
    void reasoningLevelsReturnsSortedLevels() throws Exception {
        mockMvc.perform(get("/api/v1/agent/reasoning-levels"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("high"))
            .andExpect(jsonPath("$[1]").value("low"))
            .andExpect(jsonPath("$[2]").value("max"))
            .andExpect(jsonPath("$[3]").value("medium"))
            .andExpect(jsonPath("$[4]").value("minimal"))
            .andExpect(jsonPath("$[5]").value("none"))
            .andExpect(jsonPath("$[6]").value("ultra"))
            .andExpect(jsonPath("$[7]").value("xhigh"));
    }

    // ── Set reasoning ──

    @Test
    void setReasoningWithValidEffortReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setReasoningEffort(SESSION_ID, "medium");

        mockMvc.perform(post("/api/v1/agent/reasoning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","effort":"medium"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setReasoningEffort(SESSION_ID, "medium");
    }

    @Test
    void setReasoningWithInvalidEffortReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/reasoning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","effort":"bogus"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void setReasoningWithNullEffortReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/reasoning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","effort":null}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void setReasoningWithUppercaseEffortNormalizesToLowerCase() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setReasoningEffort(SESSION_ID, "high");

        mockMvc.perform(post("/api/v1/agent/reasoning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","effort":"HIGH"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setReasoningEffort(SESSION_ID, "high");
    }

    @Test
    void setReasoningWithBlankSessionIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/reasoning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"","effort":"medium"}
                    """))
            .andExpect(status().isBadRequest());
    }

    // ── Fast mode ──

    @Test
    void toggleFastModeReturnsNewState() throws Exception {
        when(cliRuntimeSettingsService.toggleFastMode(SESSION_ID, true)).thenReturn(true);

        mockMvc.perform(post("/api/v1/agent/fast-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","enabled":"true"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk())
            .andExpect(content().string("true"));
    }

    @Test
    void toggleFastModeWithNullEnabledDefaultsTrue() throws Exception {
        when(cliRuntimeSettingsService.toggleFastMode(SESSION_ID, true)).thenReturn(true);

        mockMvc.perform(post("/api/v1/agent/fast-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","enabled":null}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk())
            .andExpect(content().string("true"));
    }

    @Test
    void toggleFastModeDisabledReturnsFalse() throws Exception {
        when(cliRuntimeSettingsService.toggleFastMode(SESSION_ID, false)).thenReturn(false);

        mockMvc.perform(post("/api/v1/agent/fast-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","enabled":"false"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk())
            .andExpect(content().string("false"));
    }

    // ── Voice mode ──

    @Test
    void toggleVoiceModeReturnsNewState() throws Exception {
        when(cliRuntimeSettingsService.toggleVoiceMode(SESSION_ID, true)).thenReturn(true);

        mockMvc.perform(post("/api/v1/agent/voice-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","enabled":"true"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk())
            .andExpect(content().string("true"));
    }

    // ── Tools ──

    @Test
    void listToolsReturnsToolNames() throws Exception {
        when(cliRuntimeSettingsService.listToolNames()).thenReturn(List.of("read_file", "write_file"));

        mockMvc.perform(get("/api/v1/agent/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("read_file"))
            .andExpect(jsonPath("$[1]").value("write_file"));
    }

    @Test
    void enableToolReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).enableTool(SESSION_ID, "browser");

        mockMvc.perform(post("/api/v1/agent/tools/enable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","toolName":"browser"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).enableTool(SESSION_ID, "browser");
    }

    @Test
    void disableToolReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).disableTool(SESSION_ID, "browser");

        mockMvc.perform(post("/api/v1/agent/tools/disable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","toolName":"browser"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).disableTool(SESSION_ID, "browser");
    }

    // ── Personality ──

    @Test
    void setPersonalityReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setPersonality(SESSION_ID, "helpful");

        mockMvc.perform(post("/api/v1/agent/personality")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","personality":"helpful"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setPersonality(SESSION_ID, "helpful");
    }

    // ── Subgoal ──

    @Test
    void setSubgoalAppendModeCallsAppend() throws Exception {
        doNothing().when(cliRuntimeSettingsService).appendSubgoal(SESSION_ID, "finish tests");

        mockMvc.perform(post("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","subgoal":"finish tests","append":"true"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).appendSubgoal(SESSION_ID, "finish tests");
    }

    @Test
    void setSubgoalReplaceModeCallsSet() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setSubgoal(SESSION_ID, "new goal");

        mockMvc.perform(post("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","subgoal":"new goal","append":"false"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setSubgoal(SESSION_ID, "new goal");
    }

    @Test
    void setSubgoalDefaultsToAppendWhenNull() throws Exception {
        doNothing().when(cliRuntimeSettingsService).appendSubgoal(SESSION_ID, "goal");

        mockMvc.perform(post("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","subgoal":"goal","append":null}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).appendSubgoal(SESSION_ID, "goal");
    }

    @Test
    void clearSubgoalsReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).clearSubgoals(SESSION_ID);

        mockMvc.perform(delete("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).clearSubgoals(SESSION_ID);
    }

    @Test
    void clearSubgoalsPostReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).clearSubgoals(SESSION_ID);

        mockMvc.perform(post("/api/v1/agent/subgoal/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).clearSubgoals(SESSION_ID);
    }

    // ── Goal ──

    @Test
    void setGoalReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setGoal(SESSION_ID, "ship product");
        doNothing().when(cliRuntimeSettingsService).setGoalPaused(SESSION_ID, false);

        mockMvc.perform(post("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","goal":"ship product"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setGoal(SESSION_ID, "ship product");
        verify(cliRuntimeSettingsService).setGoalPaused(SESSION_ID, false);
    }

    @Test
    void getGoalReturnsGoalAndPausedState() throws Exception {
        when(cliRuntimeSettingsService.getGoal(SESSION_ID)).thenReturn("ship product");
        when(cliRuntimeSettingsService.isGoalPaused(SESSION_ID)).thenReturn(false);

        mockMvc.perform(get("/api/v1/agent/goal")
                .param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goal").value("ship product"))
            .andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    void getGoalWhenNullReturnsEmptyString() throws Exception {
        when(cliRuntimeSettingsService.getGoal(SESSION_ID)).thenReturn(null);
        when(cliRuntimeSettingsService.isGoalPaused(SESSION_ID)).thenReturn(true);

        mockMvc.perform(get("/api/v1/agent/goal")
                .param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goal").value(""))
            .andExpect(jsonPath("$.paused").value(true));
    }

    @Test
    void pauseGoalReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setGoalPaused(SESSION_ID, true);

        mockMvc.perform(post("/api/v1/agent/goal/pause")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setGoalPaused(SESSION_ID, true);
    }

    @Test
    void resumeGoalReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setGoalPaused(SESSION_ID, false);

        mockMvc.perform(post("/api/v1/agent/goal/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setGoalPaused(SESSION_ID, false);
    }

    @Test
    void clearGoalReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).clearGoal(SESSION_ID);

        mockMvc.perform(delete("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).clearGoal(SESSION_ID);
    }

    @Test
    void clearGoalPostReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).clearGoal(SESSION_ID);

        mockMvc.perform(post("/api/v1/agent/goal/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).clearGoal(SESSION_ID);
    }

    // ── Title ──

    @Test
    void setTitleReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setTitle(SESSION_ID, "New Title");

        mockMvc.perform(post("/api/v1/agent/session/title")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","title":"New Title"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setTitle(SESSION_ID, "New Title");
    }

    // ── Queue ──

    @Test
    void queuePromptReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).setQueuedPrompt(SESSION_ID, "queued prompt");

        mockMvc.perform(post("/api/v1/agent/queue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","queued":"queued prompt"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setQueuedPrompt(SESSION_ID, "queued prompt");
    }

    // ── Browser CDP ──

    @Test
    void setBrowserCdpWithValidUrlReturns200() throws Exception {
        when(urlSafetyHandler.validate("ws://localhost:9222")).thenReturn(null);
        doNothing().when(cliRuntimeSettingsService).setCdpUrl(SESSION_ID, "ws://localhost:9222");

        mockMvc.perform(post("/api/v1/agent/browser")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","cdpUrl":"ws://localhost:9222"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).setCdpUrl(SESSION_ID, "ws://localhost:9222");
    }

    @Test
    void setBrowserCdpWithInvalidUrlReturns400() throws Exception {
        when(urlSafetyHandler.validate("ftp://bad")).thenReturn("cdpUrl must start with ws://, wss://, http://, or https://");

        mockMvc.perform(post("/api/v1/agent/browser")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","cdpUrl":"ftp://bad"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isBadRequest());
    }

    // ── State reset ──

    @Test
    void resetStateReturns200() throws Exception {
        doNothing().when(cliRuntimeSettingsService).resetSessionState(SESSION_ID);

        mockMvc.perform(post("/api/v1/agent/state/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s"}
                    """.formatted(SESSION_ID)))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).resetSessionState(SESSION_ID);
    }

    @Test
    void resetStateWithNullBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/state/reset"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void resetStateWithNullSessionIdReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/state/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":null}
                    """))
            .andExpect(status().isBadRequest());
    }

    // ── Credits / Usage ──

    @Test
    void getCreditsReturnsCreditsDto() throws Exception {
        when(agentRuntimeService.getCreditsSummary())
            .thenReturn(new CreditsDto(1.5, 5000, 100));

        mockMvc.perform(get("/api/v1/agent/credits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCost").value(1.5))
            .andExpect(jsonPath("$.totalTokens").value(5000))
            .andExpect(jsonPath("$.totalMessages").value(100));
    }

    // ── Insights ──

    @Test
    void getInsightsReturnsInsightsDto() throws Exception {
        when(agentRuntimeService.getInsights())
            .thenReturn(new InsightsDto(8000, 200, Map.of("gpt-4", 150)));

        mockMvc.perform(get("/api/v1/agent/insights"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalTokens").value(8000))
            .andExpect(jsonPath("$.totalMessages").value(200));
    }

    // ── Agents ──

    @Test
    void getAgentsReturnsList() throws Exception {
        when(agentRuntimeService.listActiveAgents())
            .thenReturn(List.of(new ActiveAgentDto(SESSION_ID.toString(), "running", 1234L, "hello")));

        mockMvc.perform(get("/api/v1/agent/agents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sessionId").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$[0].status").value("running"));
    }

    // ── Codex Runtime ──

    @Test
    void codexRuntimeStatusReturnsDefaultModel() throws Exception {
        when(runtimeConfigService.getModelOverride()).thenReturn(null);

        mockMvc.perform(get("/api/v1/agent/codex-runtime"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value(properties.getModel().getModelName()))
            .andExpect(jsonPath("$.provider").value(properties.getModel().getProvider()))
            .andExpect(jsonPath("$.modelOverride").doesNotExist());
    }

    @Test
    void codexRuntimeStatusReturnsOverrideWhenPresent() throws Exception {
        when(runtimeConfigService.getModelOverride()).thenReturn("gpt-4-override");

        mockMvc.perform(get("/api/v1/agent/codex-runtime"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("gpt-4-override"))
            .andExpect(jsonPath("$.modelOverride").value("gpt-4-override"));
    }

    @Test
    void codexRuntimeModelSetsOverride() throws Exception {
        doNothing().when(runtimeConfigService).setModelOverride("gpt-4o");

        mockMvc.perform(post("/api/v1/agent/codex-runtime/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"model":"gpt-4o"}
                    """))
            .andExpect(status().isOk());

        verify(runtimeConfigService).setModelOverride("gpt-4o");
    }

    @Test
    void codexRuntimeResetClearsOverrideAndSessions() throws Exception {
        doNothing().when(cliRuntimeSettingsService).resetAllSessions();
        doNothing().when(runtimeConfigService).clearModelOverride();

        mockMvc.perform(post("/api/v1/agent/codex-runtime/reset"))
            .andExpect(status().isOk());

        verify(cliRuntimeSettingsService).resetAllSessions();
        verify(runtimeConfigService).clearModelOverride();
    }

    // ── Restart / reload ──

    @Test
    void restartCallsAgentRuntime() throws Exception {
        doNothing().when(agentRuntimeService).restart();

        mockMvc.perform(post("/api/v1/agent/restart"))
            .andExpect(status().isOk());

        verify(agentRuntimeService).restart();
    }

    @Test
    void reloadMcpCallsAgentRuntime() throws Exception {
        doNothing().when(agentRuntimeService).reloadMcp();

        mockMvc.perform(post("/api/v1/agent/reload-mcp"))
            .andExpect(status().isOk());

        verify(agentRuntimeService).reloadMcp();
    }
}