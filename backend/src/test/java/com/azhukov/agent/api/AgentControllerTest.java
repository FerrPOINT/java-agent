package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.CreditsDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.service.TodoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant FIXED_TIME = Instant.parse("2024-01-01T00:00:00Z");

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private AgentStreamingService streamingService;
    @Mock private MemoryProvider memoryProvider;
    @Mock private SkillManager skillManager;
    @Mock private CheckpointManager checkpointManager;
    @Mock private com.azhukov.agent.service.tts.TtsService ttsService;
    @Mock private com.azhukov.agent.service.transcription.TranscriptionService transcriptionService;
    @Mock private AgentProperties agentProperties;
    @Mock private AgentProperties.ModelProperties modelProperties;
    @Mock private AgentProperties.CoreProperties coreProperties;
    @Mock private AgentProperties.BudgetProperties budgetProperties;
    @Mock private DomainDtoMapper domainDtoMapper;
    @Mock private com.azhukov.agent.core.skill.CuratorService curatorService;
    @Mock private com.azhukov.agent.service.CliRuntimeSettingsService cliRuntimeSettingsService;
    @Mock private com.azhukov.agent.persistence.repository.TodoRepository todoRepository;
    @Mock private com.azhukov.agent.service.RuntimeConfigService runtimeConfigService;
    @Mock private TodoService todoService;
    @Mock private com.azhukov.agent.security.UrlSafetyHandler urlSafetyHandler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    // ── AgentChatController tests ──

    private MockMvc chatMockMvc() {
        AgentChatController controller = new AgentChatController(
            agentRuntimeService, streamingService, memoryProvider, skillManager,
            ttsService, transcriptionService,
            new com.azhukov.agent.core.agent.SteerBuffer(),
            new com.azhukov.agent.core.agent.InterruptToken(),
            new com.azhukov.agent.security.ApprovalQueue(),
            agentProperties,
            null
        );
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void chatReturnsChatResponseDtoJson() throws Exception {
        mockMvc = chatMockMvc();
        ChatResponseDto response = new ChatResponseDto(
            SESSION_ID,
            "Hello, I am the agent.",
            List.of("search_web", "read_file"),
            true
        );
        when(agentRuntimeService.runTurn(any(ChatRequest.class))).thenReturn(response);

        String requestBody = """
            {
              "message": "hello"
            }
            """;

        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.content").value("Hello, I am the agent."))
            .andExpect(jsonPath("$.toolCalls").isArray())
            .andExpect(jsonPath("$.toolCalls[0]").value("search_web"))
            .andExpect(jsonPath("$.toolCalls[1]").value("read_file"))
            .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void delegateReturnsChatResponseDto() throws Exception {
        mockMvc = chatMockMvc();
        ChatResponseDto response = new ChatResponseDto(
            SESSION_ID,
            "Delegated task completed.",
            null,
            true
        );
        when(agentRuntimeService.runDelegate(any(ChatRequest.class))).thenReturn(response);

        String requestBody = """
            {
              "message": "delegate this",
              "delegationDepth": 2
            }
            """;

        mockMvc.perform(post("/api/v1/agent/delegate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.content").value("Delegated task completed."))
            .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void doctorReturnsDiagnostics() throws Exception {
        mockMvc = chatMockMvc();
        when(agentProperties.getModel()).thenReturn(modelProperties);
        when(agentProperties.getCore()).thenReturn(coreProperties);
        when(agentProperties.getBudget()).thenReturn(budgetProperties);
        when(agentProperties.getName()).thenReturn("Test Agent");
        when(modelProperties.getModelName()).thenReturn("test-model");
        when(modelProperties.getProvider()).thenReturn("test-provider");
        when(coreProperties.getMaxTurns()).thenReturn(100);
        when(budgetProperties.getMaxModelCallsPerTurn()).thenReturn(100);
        when(skillManager.listSkillNames()).thenReturn(List.of("search", "read_file"));

        mockMvc.perform(get("/api/v1/agent/doctor"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.name").value("Test Agent"))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.provider").value("test-provider"))
            .andExpect(jsonPath("$.maxTurns").value(100))
            .andExpect(jsonPath("$.maxModelCallsPerTurn").value(100))
            .andExpect(jsonPath("$.skillCount").value(2));
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        mockMvc = chatMockMvc();
        String requestBody = """
            {
              "message": ""
            }
            """;

        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors.message").exists());
    }

    @Test
    void serviceExceptionReturns500() throws Exception {
        mockMvc = chatMockMvc();
        when(agentRuntimeService.runTurn(any(ChatRequest.class)))
            .thenThrow(new RuntimeException("agent runtime failure"));

        String requestBody = """
            {
              "message": "hello"
            }
            """;

        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.type").value("internal"))
            .andExpect(jsonPath("$.error").value("Internal error: agent runtime failure"));
    }

    // ── SessionController tests ──

    private MockMvc sessionMockMvc() {
        SessionController controller = new SessionController(
            agentRuntimeService, domainDtoMapper, agentProperties, checkpointManager, todoService
        );
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void sessionsListsSessions() throws Exception {
        mockMvc = sessionMockMvc();
        List<SessionSummaryDto> sessions = List.of(
            new SessionSummaryDto(
                SESSION_ID,
                "user-1",
                "Test session",
                "noop",
                "",
                FIXED_TIME,
                FIXED_TIME
            )
        );
        when(agentRuntimeService.listSessions()).thenReturn(sessions);

        mockMvc.perform(get("/api/v1/sessions"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$[0].userId").value("user-1"))
            .andExpect(jsonPath("$[0].title").value("Test session"))
            .andExpect(jsonPath("$[0].modelProvider").value("noop"))
            .andExpect(jsonPath("$[0].modelName").value(""))
            .andExpect(jsonPath("$[0].createdAt").exists())
            .andExpect(jsonPath("$[0].updatedAt").exists());
    }

    @Test
    void createSessionReturns201WithSessionDto() throws Exception {
        mockMvc = sessionMockMvc();
        SessionSummaryDto dto = new SessionSummaryDto(
            SESSION_ID,
            "user-1",
            "New chat",
            "openai-compatible",
            "kimi-k2.6",
            FIXED_TIME,
            FIXED_TIME
        );
        when(agentRuntimeService.createSession(any(), any(), any())).thenReturn(
            new com.azhukov.agent.core.model.Session(SESSION_ID, "user-1", "New chat", "openai-compatible", "kimi-k2.6", null, java.util.Map.of())
        );
        when(domainDtoMapper.toSessionSummaryDto(any(com.azhukov.agent.core.model.Session.class))).thenReturn(dto);

        AgentProperties.ModelProperties modelProps = new AgentProperties.ModelProperties();
        modelProps.setModelName("kimi-k2.6");
        when(agentProperties.getModel()).thenReturn(modelProps);

        mockMvc.perform(post("/api/v1/agent/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.userId").value("user-1"))
            .andExpect(jsonPath("$.title").value("New chat"));
    }

    @Test
    void createSnapshotReturnsOk() throws Exception {
        mockMvc = sessionMockMvc();
        when(checkpointManager.snapshot(any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/agent/snapshot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "description": "check"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void compressEndpointReturns200() throws Exception {
        mockMvc = sessionMockMvc();
        doNothing().when(agentRuntimeService)
            .compressSession(any(UUID.class), any(), any());

        mockMvc.perform(post("/api/v1/agent/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "focusTopic": "main topic"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void undoEndpointReturns200() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentRuntimeService.undoTurns(any(UUID.class), any(Integer.class))).thenReturn(3);

        mockMvc.perform(post("/api/v1/agent/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "turns": 3
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string("3"));
    }

    // ── MemoryController tests ──

    private MockMvc memoryMockMvc() {
        MemoryController controller = new MemoryController(memoryProvider, agentRuntimeService);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void getMemoryApprovalReturnsCurrentState() throws Exception {
        mockMvc = memoryMockMvc();
        when(agentRuntimeService.isMemoryApprovalEnabled()).thenReturn(true);

        mockMvc.perform(get("/api/v1/agent/memory/approval"))
            .andExpect(status().isOk())
            .andExpect(content().string("true"));
    }

    @Test
    void setMemoryApprovalDelegatesToService() throws Exception {
        mockMvc = memoryMockMvc();
        mockMvc.perform(post("/api/v1/agent/memory/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": false}"))
            .andExpect(status().isOk());
    }

    // ── RuntimeSettingsController tests ──

    private MockMvc runtimeSettingsMockMvc() {
        RuntimeSettingsController controller = new RuntimeSettingsController(
            cliRuntimeSettingsService, agentProperties,
            memoryProvider, ttsService, transcriptionService,
            runtimeConfigService, agentRuntimeService, urlSafetyHandler
        );
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void setReasoningEffortReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/reasoning")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "effort": "high"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void toggleFastModeReturnsNewState() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(cliRuntimeSettingsService.toggleFastMode(any(UUID.class), any(Boolean.class))).thenReturn(true);

        mockMvc.perform(post("/api/v1/agent/fast-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "enabled": "true"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").value(true));
    }

    @Test
    void toggleVoiceModeReturnsNewState() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(cliRuntimeSettingsService.toggleVoiceMode(any(UUID.class), any(Boolean.class))).thenReturn(false);

        mockMvc.perform(post("/api/v1/agent/voice-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "enabled": "false"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").value(false));
    }

    @Test
    void setTitleReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/session/title")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "title": "test-run"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void setSubgoalReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "subgoal": "verify cli"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void listToolsReturnsToolNames() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(cliRuntimeSettingsService.listToolNames()).thenReturn(List.of("read_file", "write_file"));

        mockMvc.perform(get("/api/v1/agent/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0]").value("read_file"))
            .andExpect(jsonPath("$[1]").value("write_file"));
    }

    @Test
    void enableToolReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/tools/enable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "toolName": "read_file"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void disableToolReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/tools/disable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "toolName": "write_file"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void setBrowserCdpReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(urlSafetyHandler.validate("ws://localhost:9222")).thenReturn(null);
        mockMvc.perform(post("/api/v1/agent/browser")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "cdpUrl": "ws://localhost:9222"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void queuePromptReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/queue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "queued": "hello"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void configReturnsConfiguration() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(agentProperties.getModel()).thenReturn(modelProperties);
        when(agentProperties.getCore()).thenReturn(coreProperties);
        when(agentProperties.getBudget()).thenReturn(budgetProperties);
        when(agentProperties.getName()).thenReturn("Test Agent");
        when(modelProperties.getModelName()).thenReturn("test-model");
        when(modelProperties.getProvider()).thenReturn("test-provider");
        when(modelProperties.getBaseUrl()).thenReturn("http://localhost:9999");
        when(modelProperties.getMaxTokens()).thenReturn(4096);
        when(modelProperties.getTemperature()).thenReturn(0.7);
        when(modelProperties.getTimeoutSeconds()).thenReturn(600);
        when(coreProperties.getMaxTurns()).thenReturn(100);
        when(coreProperties.getDefaultSystemPrompt()).thenReturn("Be concise.");
        when(coreProperties.getReasoningConfig()).thenReturn("medium");
        when(budgetProperties.getMaxModelCallsPerTurn()).thenReturn(100);

        mockMvc.perform(get("/api/v1/agent/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Test Agent"))
            .andExpect(jsonPath("$.model").value("test-model"))
            .andExpect(jsonPath("$.provider").value("test-provider"))
            .andExpect(jsonPath("$.baseUrl").value("http://localhost:9999"))
            .andExpect(jsonPath("$.maxTurns").value(100))
            .andExpect(jsonPath("$.maxModelCallsPerTurn").value(100));
    }

    @Test
    void disableToolWithoutSessionIdReturns400() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/tools/disable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "toolName": "image_generate"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void disableToolWithoutToolNameReturns400() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/tools/disable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void disableToolWithValidBodyReturns200() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/tools/disable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "toolName": "image_generate"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void enableToolWithoutSessionIdReturns400() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/tools/enable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "toolName": "image_generate"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void setGoalWithoutSessionIdReturns400() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "goal": "Test"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void setGoalWithValidBodyReturns200() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "goal": "Test goal"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void getGoalReturnsCurrentGoal() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(cliRuntimeSettingsService.getGoal(SESSION_ID)).thenReturn("My goal");
        when(cliRuntimeSettingsService.isGoalPaused(SESSION_ID)).thenReturn(false);

        mockMvc.perform(get("/api/v1/agent/goal")
                .param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goal").value("My goal"))
            .andExpect(jsonPath("$.paused").value(false));
    }

    @Test
    void getGoalReturnsEmptyWhenNoGoalSet() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(cliRuntimeSettingsService.getGoal(SESSION_ID)).thenReturn(null);
        when(cliRuntimeSettingsService.isGoalPaused(SESSION_ID)).thenReturn(false);

        mockMvc.perform(get("/api/v1/agent/goal")
                .param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.goal").value(""))
            .andExpect(jsonPath("$.paused").value(false));
    }

    // ── Credits ──

    @Test
    void creditsReturnsSummary() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(agentRuntimeService.getCreditsSummary())
            .thenReturn(new CreditsDto(1.23, 4500, 12));

        mockMvc.perform(get("/api/v1/agent/credits"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.totalCost").value(1.23))
            .andExpect(jsonPath("$.totalTokens").value(4500))
            .andExpect(jsonPath("$.totalMessages").value(12));
    }

    // ── Codex Runtime ──

    @Test
    void codexRuntimeStatusReturnsModelAndProvider() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(agentProperties.getModel()).thenReturn(modelProperties);
        when(modelProperties.getModelName()).thenReturn("gpt-4o");
        when(modelProperties.getProvider()).thenReturn("openai");
        when(modelProperties.getMaxRetries()).thenReturn(3);
        when(modelProperties.getMaxTokens()).thenReturn(4096);
        when(modelProperties.getTimeoutSeconds()).thenReturn(600);

        mockMvc.perform(get("/api/v1/agent/codex-runtime"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.model").value("gpt-4o"))
            .andExpect(jsonPath("$.provider").value("openai"))
            .andExpect(jsonPath("$.modelOverride").doesNotExist());
    }

    @Test
    void codexRuntimeModelValidReturns200() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        String body = """
            {
              "model": "claude-sonnet-4"
            }
            """;

        mockMvc.perform(post("/api/v1/agent/codex-runtime/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    @Test
    void codexRuntimeModelBlankReturns400() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        String body = """
            {
              "model": ""
            }
            """;

        mockMvc.perform(post("/api/v1/agent/codex-runtime/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
    }

    @Test
    void codexRuntimeResetReturns200() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        org.mockito.Mockito.doNothing().when(cliRuntimeSettingsService).resetAllSessions();

        mockMvc.perform(post("/api/v1/agent/codex-runtime/reset"))
            .andExpect(status().isOk());
    }

    // ── KanbanController tests ──

    private MockMvc kanbanMockMvc() {
        KanbanController controller = new KanbanController(todoService);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void kanbanListReturnsTodos() throws Exception {
        mockMvc = kanbanMockMvc();
        TodoDto todo = new TodoDto(SESSION_ID, null, "default", "Test todo", "pending", "medium", FIXED_TIME);
        when(todoService.listByUserId("default")).thenReturn(List.of(todo));

        mockMvc.perform(get("/api/v1/agent/kanban"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$[0].title").value("Test todo"))
            .andExpect(jsonPath("$[0].status").value("pending"));
    }

    @Test
    void kanbanListEmptyBoardReturnsEmptyArray() throws Exception {
        mockMvc = kanbanMockMvc();
        when(todoService.listByUserId("default")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/kanban"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void kanbanAddValidTextReturnsSavedTodo() throws Exception {
        mockMvc = kanbanMockMvc();
        TodoDto saved = new TodoDto(SESSION_ID, null, "default", "new task", "pending", "medium", FIXED_TIME);
        when(todoService.add(eq("default"), any())).thenReturn(saved);

        String body = """
            {
              "text": "new task"
            }
            """;

        mockMvc.perform(post("/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.title").value("new task"))
            .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void kanbanAddBlankTextReturns400() throws Exception {
        mockMvc = kanbanMockMvc();

        String body = """
            {
              "text": "   "
            }
            """;

        mockMvc.perform(post("/api/v1/agent/kanban/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
    }

    @Test
    void kanbanDoneTaskNotFoundReturns404() throws Exception {
        mockMvc = kanbanMockMvc();
        when(todoService.markDone(SESSION_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/agent/kanban/done/" + SESSION_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void kanbanClearReturns200() throws Exception {
        mockMvc = kanbanMockMvc();
        doNothing().when(todoService).clearByUserId("default");

        mockMvc.perform(delete("/api/v1/agent/kanban"))
            .andExpect(status().isOk());
    }

    // ── CuratorController tests ──

    private MockMvc curatorMockMvc() {
        CuratorController controller = new CuratorController(curatorService);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void curatorStatusReturnsFields() throws Exception {
        mockMvc = curatorMockMvc();
        when(curatorService.isEnabled()).thenReturn(true);
        when(curatorService.isPaused()).thenReturn(false);
        when(curatorService.isDryRun()).thenReturn(true);
        when(curatorService.getIntervalHours()).thenReturn(6);
        when(curatorService.getMinIdleHours()).thenReturn(1.5);
        when(curatorService.getStaleAfterDays()).thenReturn(14);
        when(curatorService.getArchiveAfterDays()).thenReturn(30);

        mockMvc.perform(get("/api/v1/agent/curator/status"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.paused").value(false))
            .andExpect(jsonPath("$.dryRun").value(true))
            .andExpect(jsonPath("$.intervalHours").value(6));
    }
}