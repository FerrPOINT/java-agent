package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.BackgroundRequest;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ContextInfoDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.skill.CuratorService;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.api.dto.CheckpointDto;
import com.azhukov.agent.api.dto.CheckpointDiffDto;
import com.azhukov.agent.persistence.entity.CheckpointEntity;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.service.CliRuntimeSettingsService;
import com.azhukov.agent.service.RuntimeConfigService;
import com.azhukov.agent.service.TodoService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch-coverage tests for the split controllers — focuses on error paths,
 * edge cases, null inputs, and boundary conditions.
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerBranchCoverageTest {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant FIXED_TIME = Instant.parse("2024-01-01T00:00:00Z");

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private AgentStreamingService streamingService;
    @Mock private MemoryProvider memoryProvider;
    @Mock private SkillManager skillManager;
    @Mock private com.azhukov.agent.persistence.repository.SkillAuditLogRepository skillAuditLogRepository;
    @Mock private CheckpointManager checkpointManager;
    @Mock private com.azhukov.agent.persistence.repository.SessionRepository sessionRepository;
    @Mock private TtsService ttsService;
    @Mock private TranscriptionService transcriptionService;
    @Mock private AgentProperties agentProperties;
    @Mock private AgentProperties.ModelProperties modelProperties;
    @Mock private AgentProperties.CoreProperties coreProperties;
    @Mock private AgentProperties.BudgetProperties budgetProperties;
    private final DomainDtoMapper domainDtoMapper = Mappers.getMapper(DomainDtoMapper.class);
    @Mock private CuratorService curatorService;
    @Mock private CliRuntimeSettingsService cliRuntimeSettingsService;
    @Mock private TodoService todoService;
    @Mock private RuntimeConfigService runtimeConfigService;
    @Mock private com.azhukov.agent.core.security.UrlSafetyHandler urlSafetyHandler;

    private SteerBuffer steerBuffer;
    private InterruptToken interruptToken;
    private ApprovalQueue approvalQueue;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        steerBuffer = new SteerBuffer();
        interruptToken = new InterruptToken();
        approvalQueue = new ApprovalQueue();
    }

    // ── MockMvc builders for each controller ──

    private MockMvc chatMockMvc() {
        AgentChatController controller = new AgentChatController(
            agentRuntimeService, streamingService, memoryProvider, skillManager,
            ttsService, transcriptionService,
            steerBuffer, interruptToken,
            null,
            org.mockito.Mockito.mock(com.azhukov.agent.persistence.repository.BackgroundJobRepository.class),
            null, null, null,
            approvalQueue,
            agentProperties,
            null,
            org.mockito.Mockito.mock(com.azhukov.agent.core.tool.ToolRegistry.class)
        );
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    private MockMvc sessionMockMvc() {
        SessionController controller = new SessionController(
            agentRuntimeService, domainDtoMapper, agentProperties, checkpointManager, todoService, null, sessionRepository);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    private MockMvc memoryMockMvc() {
        MemoryController controller = new MemoryController(memoryProvider, agentRuntimeService);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    private MockMvc skillMockMvc() {
        SkillController controller = new SkillController(skillManager, agentRuntimeService, skillAuditLogRepository, mock(com.azhukov.agent.core.skill.SkillsHubService.class), domainDtoMapper);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    private MockMvc checkpointMockMvc() {
        CheckpointController controller = new CheckpointController(checkpointManager, Mappers.getMapper(com.azhukov.agent.api.mapper.CheckpointDtoMapper.class));
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    private MockMvc runtimeSettingsMockMvc() {
        RuntimeSettingsController controller = new RuntimeSettingsController(
            cliRuntimeSettingsService, agentProperties,
            memoryProvider, ttsService, transcriptionService,
            runtimeConfigService, agentRuntimeService, urlSafetyHandler
        );
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    private MockMvc kanbanMockMvc() {
        KanbanController controller = new KanbanController(todoService);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    private MockMvc curatorMockMvc() {
        CuratorController controller = new CuratorController(curatorService);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    // ── RuntimeSettings: Goal endpoint branches ──

    @Test
    void setGoalBlankReturns400() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "goal": ""
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void setGoalNullReturns400() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void setGoalValidReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "goal": "fix all tests"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void pauseGoalReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/goal/pause")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void resumeGoalReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/goal/resume")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void clearGoalDeleteReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(delete("/api/v1/agent/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void clearGoalPostReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/goal/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk());
    }

    // ── RuntimeSettings: Subgoal append branch ──

    @Test
    void setSubgoalWithAppendTrueCallsAppend() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "subgoal": "task A",
                      "append": "true"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void clearSubgoalsDeleteReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(delete("/api/v1/agent/subgoal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void clearSubgoalsPostReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/subgoal/clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk());
    }

    // ── RuntimeSettings: Personality endpoint ──

    @Test
    void setPersonalityReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/personality")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "personality": "concise"
                    }
                    """))
            .andExpect(status().isOk());
    }

    // ── RuntimeSettings: State reset ──

    @Test
    void resetStateReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        mockMvc.perform(post("/api/v1/agent/state/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk());
    }

    // ── Memory endpoint branches ──

    @Test
    void storeMemoryBlankFactReturns400() throws Exception {
        mockMvc = memoryMockMvc();
        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fact": ""
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
    }

    @Test
    void storeMemoryNullFactReturns400() throws Exception {
        mockMvc = memoryMockMvc();
        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"));
    }

    @Test
    void storeMemoryWithDefaultsReturnsOk() throws Exception {
        mockMvc = memoryMockMvc();
        doNothing().when(memoryProvider).store(any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "fact": "user prefers dark mode"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void storeMemoryWithCustomUserIdAndCategoryReturnsOk() throws Exception {
        mockMvc = memoryMockMvc();
        doNothing().when(memoryProvider).store(eq("custom-user"), any(), eq("preference"), any());

        mockMvc.perform(post("/api/v1/agent/memory")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "custom-user",
                      "fact": "likes Java 25",
                      "category": "preference",
                      "target": "memory"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void memoryReturnsList() throws Exception {
        mockMvc = memoryMockMvc();
        when(memoryProvider.recall(eq("default"), eq(""), eq(100)))
            .thenReturn(List.of("fact1", "fact2"));

        mockMvc.perform(get("/api/v1/agent/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0]").value("fact1"));
    }

    // ── Memory management endpoints ──

    @Test
    void listPendingMemoryReturnsList() throws Exception {
        mockMvc = memoryMockMvc();
        when(agentRuntimeService.listPendingMemory("user-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/memory/pending/user-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listAllMemoryReturnsList() throws Exception {
        mockMvc = memoryMockMvc();
        when(agentRuntimeService.listAllMemory("user-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/memory/all/user-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void deleteMemoryReturnsOk() throws Exception {
        mockMvc = memoryMockMvc();
        doNothing().when(agentRuntimeService).deleteMemory(any(), any());

        mockMvc.perform(delete("/api/v1/agent/memory/user-1/" + SESSION_ID))
            .andExpect(status().isOk());
    }

    // ── AgentChat: Approve/Deny branches ──

    @Test
    void approveAllPendingReturnsMessage() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "all": true
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void approveWithNoPendingReturnsMessage() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "all": false
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void approveWithInvalidSessionIdReturnsMessage() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "not-a-uuid",
                      "all": false
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void approveWithValidSessionIdNoPendingReturnsMessage() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scope": "550e8400-e29b-41d4-a716-446655440000",
                      "all": false
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void denyAllPendingReturnsMessage() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "all": true
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void denyWithNoPendingReturnsMessage() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "all": false
                    }
                    """))
            .andExpect(status().isOk());
    }

    // ── AgentChat: Tool approval endpoints ──

    @Test
    void pendingApprovalsReturnsList() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(get("/api/v1/agent/approvals/pending"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void approveToolReturnsOk() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/approvals/" + SESSION_ID + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .param("decision", "approve"))
            .andExpect(status().isOk());
    }

    @Test
    void denyToolReturnsOk() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/approvals/" + SESSION_ID + "/deny")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk());
    }

    // ── Session: Model switching branches ──

    @Test
    void switchModelNullSessionReturnsError() throws Exception {
        mockMvc = sessionMockMvc();
        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-4o"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void switchModelBlankModelReturnsError() throws Exception {
        mockMvc = sessionMockMvc();
        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "model": ""
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void switchModelServiceThrowsReturnsError() throws Exception {
        mockMvc = sessionMockMvc();
        doThrow(new RuntimeException("session not found"))
            .when(agentRuntimeService).switchModel(any(UUID.class), any(), any());

        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "model": "gpt-4o"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("session not found"));
    }

    @Test
    void switchModelSuccessReturnsOk() throws Exception {
        mockMvc = sessionMockMvc();
        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "model": "gpt-4o",
                      "provider": "openai"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.model").value("gpt-4o"));
    }

    @Test
    void getCurrentModelWithoutSessionReturnsError() throws Exception {
        mockMvc = sessionMockMvc();
        mockMvc.perform(get("/api/v1/agent/model"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("sessionId required"));
    }

    @Test
    void getCurrentModelWithSessionReturnsInfo() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentRuntimeService.getContext(SESSION_ID))
            .thenReturn(new ContextInfoDto(
                SESSION_ID, 5, 100, List.of(), null, null, null));
        com.azhukov.agent.persistence.entity.SessionEntity se = new com.azhukov.agent.persistence.entity.SessionEntity();
        se.setId(SESSION_ID);
        se.setModelName("app-test");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(java.util.Optional.of(se));

        mockMvc.perform(get("/api/v1/agent/model")
                .param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.messageCount").value(5))
            .andExpect(jsonPath("$.model").value("app-test"));
    }

    @Test
    void getCurrentModelServiceThrowsReturnsError() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentRuntimeService.getContext(SESSION_ID))
            .thenThrow(new RuntimeException("session not found"));

        mockMvc.perform(get("/api/v1/agent/model")
                .param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("session not found"));
    }

    // ── AgentChat: Stop endpoint ──

    @Test
    void stopWithoutBodyReturnsOk() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/stop"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void stopWithSessionIdCancelsInterrupt() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    // ── Skill: content endpoint ──

    @Test
    void getSkillContentFoundReturnsContent() throws Exception {
        mockMvc = skillMockMvc();
        // mu14: controller reads through the userId-scoped overload
        when(skillManager.getSkill(org.mockito.ArgumentMatchers.eq("coding"),
            org.mockito.ArgumentMatchers.isNull())).thenReturn("Write clean code");

        mockMvc.perform(get("/api/v1/agent/skills/coding"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.name").value("coding"))
            .andExpect(jsonPath("$.content").value("Write clean code"));
    }

    @Test
    void getSkillContentNotFoundReturnsError() throws Exception {
        mockMvc = skillMockMvc();
        // mu14: controller reads through the userId-scoped overload
        when(skillManager.getSkill(org.mockito.ArgumentMatchers.eq("unknown"),
            org.mockito.ArgumentMatchers.isNull())).thenReturn(null);

        mockMvc.perform(get("/api/v1/agent/skills/unknown"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").exists());
    }

    // ── Skill: Bundle endpoints ──

    @Test
    void installBundleSuccessReturnsOk() throws Exception {
        mockMvc = skillMockMvc();
        mockMvc.perform(post("/api/v1/agent/bundles/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "bundleName": "test-bundle"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void installBundleThrowsReturnsError() throws Exception {
        mockMvc = skillMockMvc();
        doThrow(new RuntimeException("bundle not found"))
            .when(agentRuntimeService).installBundle(any());

        mockMvc.perform(post("/api/v1/agent/bundles/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "bundleName": "missing"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("bundle not found"));
    }

    @Test
    void uninstallBundleSuccessReturnsOk() throws Exception {
        mockMvc = skillMockMvc();
        mockMvc.perform(post("/api/v1/agent/bundles/uninstall")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "bundleName": "test-bundle"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void uninstallBundleThrowsReturnsError() throws Exception {
        mockMvc = skillMockMvc();
        doThrow(new RuntimeException("not installed"))
            .when(agentRuntimeService).uninstallBundle(any());

        mockMvc.perform(post("/api/v1/agent/bundles/uninstall")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "bundleName": "missing"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void bundlesListReturnsList() throws Exception {
        mockMvc = skillMockMvc();
        when(agentRuntimeService.listBundles()).thenReturn(List.of("bundle-1", "bundle-2"));

        mockMvc.perform(get("/api/v1/agent/bundles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void bundlesAliasReturnsSameList() throws Exception {
        mockMvc = skillMockMvc();
        when(agentRuntimeService.listBundles()).thenReturn(List.of("bundle-1"));

        mockMvc.perform(get("/api/v1/agent/skills/bundles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("bundle-1"));
    }

    // ── Checkpoint endpoints ──

    @Test
    void createCheckpointReturnsDto() throws Exception {
        mockMvc = checkpointMockMvc();
        CheckpointEntity cp = new CheckpointEntity();
        cp.setId(UUID.randomUUID());
        cp.setDescription("test");
        when(checkpointManager.snapshot(any(), eq("Manual checkpoint"))).thenReturn(cp);

        mockMvc.perform(post("/api/v1/agent/checkpoint"))
            .andExpect(status().isOk());
    }

    @Test
    void createCheckpointWithDescription() throws Exception {
        mockMvc = checkpointMockMvc();
        CheckpointEntity cp = new CheckpointEntity();
        cp.setId(UUID.randomUUID());
        cp.setDescription("custom");
        when(checkpointManager.snapshot(any(), eq("custom desc"))).thenReturn(cp);

        mockMvc.perform(post("/api/v1/agent/checkpoint")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "description": "custom desc"
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void listCheckpointsReturnsList() throws Exception {
        mockMvc = checkpointMockMvc();
        when(checkpointManager.list(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/checkpoint"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void restoreCheckpointReturnsMessage() throws Exception {
        mockMvc = checkpointMockMvc();
        doNothing().when(checkpointManager).restore(any(UUID.class));

        UUID cpId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/agent/checkpoint/" + cpId + "/restore"))
            .andExpect(status().isOk());
    }

    @Test
    void deleteCheckpointReturnsOk() throws Exception {
        mockMvc = checkpointMockMvc();
        doNothing().when(checkpointManager).remove(any(UUID.class));

        UUID cpId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/agent/checkpoint/" + cpId))
            .andExpect(status().isOk());
    }

    // ── Diff endpoint ──

    @Test
    void diffCheckpointsReturnsJson() throws Exception {
        mockMvc = checkpointMockMvc();
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        when(checkpointManager.diff(left, right, "context"))
            .thenReturn(om.createObjectNode().put("left", left.toString()).put("right", right.toString()));

        mockMvc.perform(get("/api/v1/agent/diff")
                .param("left", left.toString())
                .param("right", right.toString())
                .param("scope", "context"))
            .andExpect(status().isOk());
    }

    // ── AgentChat: Steer endpoint ──

    @Test
    void steerWithNullSessionIdReturnsNotAccepted() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "text": "do something"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    void steerWithBlankTextReturnsNotAccepted() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "text": ""
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    void steerValidReturnsAccepted() throws Exception {
        mockMvc = chatMockMvc();
        mockMvc.perform(post("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000",
                      "text": "focus on tests"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true));
    }

    // ── Curator run/pause/resume ──

    @Test
    void curatorRunReturnsReport() throws Exception {
        mockMvc = curatorMockMvc();
        when(curatorService.runCycle()).thenReturn(null);

        mockMvc.perform(post("/api/v1/agent/curator/run"))
            .andExpect(status().isOk());
    }

    @Test
    void curatorRunWithReportReturnsReport() throws Exception {
        mockMvc = curatorMockMvc();
        when(curatorService.runCycle()).thenAnswer(inv -> null);

        mockMvc.perform(post("/api/v1/agent/curator/run"))
            .andExpect(status().isOk());
    }

    @Test
    void curatorPauseReturnsOk() throws Exception {
        mockMvc = curatorMockMvc();
        doNothing().when(curatorService).setPaused(true);

        mockMvc.perform(post("/api/v1/agent/curator/pause"))
            .andExpect(status().isOk());
    }

    @Test
    void curatorResumeReturnsOk() throws Exception {
        mockMvc = curatorMockMvc();
        doNothing().when(curatorService).setPaused(false);

        mockMvc.perform(post("/api/v1/agent/curator/resume"))
            .andExpect(status().isOk());
    }

    // ── RuntimeSettings: Restart/reload endpoints ──

    @Test
    void restartReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        doNothing().when(agentRuntimeService).restart();
        mockMvc.perform(post("/api/v1/agent/restart"))
            .andExpect(status().isOk());
    }

    @Test
    void reloadMcpReturnsOk() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        doNothing().when(agentRuntimeService).reloadMcp();
        mockMvc.perform(post("/api/v1/agent/reload-mcp"))
            .andExpect(status().isOk());
    }

    @Test
    void reloadSkillsReturnsOk() throws Exception {
        mockMvc = skillMockMvc();
        doNothing().when(agentRuntimeService).reloadSkills();
        mockMvc.perform(post("/api/v1/agent/reload-skills"))
            .andExpect(status().isOk());
    }

    @Test
    void reloadAllReturnsOk() throws Exception {
        mockMvc = skillMockMvc();
        doNothing().when(agentRuntimeService).reloadSkills();
        doNothing().when(agentRuntimeService).reloadMcp();
        mockMvc.perform(post("/api/v1/agent/reload"))
            .andExpect(status().isOk());
    }

    // ── AgentChat: Background endpoint ──

    @Test
    void backgroundReturnsSessionId() throws Exception {
        mockMvc = chatMockMvc();
        when(agentRuntimeService.submitBackgroundJob(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(java.util.UUID.randomUUID());

        mockMvc.perform(post("/api/v1/agent/background")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "prompt": "do something in background",
                      "sessionId": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))
            .andExpect(status().isOk());
    }

    // ── Session: Branch session ──

    @Test
    void branchSessionReturnsDto() throws Exception {
        mockMvc = sessionMockMvc();
        SessionSummaryDto dto = new SessionSummaryDto(
            SESSION_ID, "user-1", "Branch of test", "openai-compatible", "", FIXED_TIME, FIXED_TIME);
        when(agentRuntimeService.branchSession(eq(SESSION_ID), any())).thenReturn(dto);

        mockMvc.perform(post("/api/v1/agent/session/" + SESSION_ID + "/branch"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()));
    }

    // ── Session: Context / Usage / Sessions by user ──

    @Test
    void getContextReturnsInfo() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentRuntimeService.getContext(SESSION_ID))
            .thenReturn(new ContextInfoDto(
                SESSION_ID, 3, 200, List.of("read_file"), null, null, null));

        mockMvc.perform(get("/api/v1/agent/session/" + SESSION_ID + "/context"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messageCount").value(3));
    }

    @Test
    void resetSessionReturnsOk() throws Exception {
        mockMvc = sessionMockMvc();
        doNothing().when(agentRuntimeService).resetSession(any());

        mockMvc.perform(post("/api/v1/agent/session/" + SESSION_ID + "/reset"))
            .andExpect(status().isOk());
    }

    @Test
    void getUsageReturnsDto() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentRuntimeService.getUsage(SESSION_ID))
            .thenReturn(new UsageDto(SESSION_ID, 100, 500));

        mockMvc.perform(get("/api/v1/agent/session/" + SESSION_ID + "/usage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()));
    }

    @Test
    void sessionsByUserIdReturnsList() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentRuntimeService.listSessionsByUserId("user-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/sessions/user-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    // ── Skill: Skills endpoint ──

    @Test
    void skillsReturnsList() throws Exception {
        mockMvc = skillMockMvc();
        when(skillManager.listSkillNames(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(List.of("coding", "web"));

        mockMvc.perform(get("/api/v1/agent/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    // ── RuntimeSettings: Agents / Insights ──

    @Test
    void agentsReturnsList() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(agentRuntimeService.listActiveAgents()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/agents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    @Test
    void insightsReturnsDto() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(agentRuntimeService.getInsights()).thenReturn(new com.azhukov.agent.api.dto.InsightsDto(
            0, 0, Map.of()));

        mockMvc.perform(get("/api/v1/agent/insights"))
            .andExpect(status().isOk());
    }

    // ── Session: Compress endpoint branches ──

    @Test
    void compressWithFocusTopicReturnsMessage() throws Exception {
        mockMvc = sessionMockMvc();
        doNothing().when(agentRuntimeService).compressSession(any(), any(), any());

        mockMvc.perform(post("/api/v1/agent/session/" + SESSION_ID + "/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "focusTopic": "authentication",
                      "keepLastN": 3
                    }
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void compressWithoutBodyReturnsMessage() throws Exception {
        mockMvc = sessionMockMvc();
        doNothing().when(agentRuntimeService).compressSession(any(), any(), any());

        mockMvc.perform(post("/api/v1/agent/session/" + SESSION_ID + "/compress"))
            .andExpect(status().isOk());
    }

    @Test
    void compressWithFocusOnlyReturnsMessage() throws Exception {
        mockMvc = sessionMockMvc();
        doNothing().when(agentRuntimeService).compressSession(any(), eq("auth"), any());

        mockMvc.perform(post("/api/v1/agent/session/" + SESSION_ID + "/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "focusTopic": "auth"
                    }
                    """))
            .andExpect(status().isOk());
    }

    // ── Session: Undo endpoint ──

    @Test
    void undoTurnsReturnsCount() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentRuntimeService.undoTurns(eq(SESSION_ID), anyInt())).thenReturn(3);

        mockMvc.perform(post("/api/v1/agent/session/" + SESSION_ID + "/undo")
                .param("turns", "2"))
            .andExpect(status().isOk());
    }

    // ── RuntimeSettings: Codex runtime with override ──

    @Test
    void codexRuntimeStatusWithOverrideReturnsOverride() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        when(runtimeConfigService.getModelOverride()).thenReturn("claude-sonnet-4");
        when(agentProperties.getModel()).thenReturn(modelProperties);
        when(modelProperties.getProvider()).thenReturn("openai");
        when(modelProperties.getMaxRetries()).thenReturn(3);
        when(modelProperties.getMaxTokens()).thenReturn(4096);
        when(modelProperties.getTimeoutSeconds()).thenReturn(600);

        mockMvc.perform(get("/api/v1/agent/codex-runtime"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("claude-sonnet-4"))
            .andExpect(jsonPath("$.modelOverride").value("claude-sonnet-4"));
    }

    @Test
    void codexRuntimeResetClearsOverride() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        doNothing().when(cliRuntimeSettingsService).resetAllSessions();

        mockMvc.perform(post("/api/v1/agent/codex-runtime/reset"))
            .andExpect(status().isOk());
    }

    // ── Session: Create session with null body ──

    @Test
    void createSessionWithNullBodyUsesDefaultUser() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentProperties.getModel()).thenReturn(modelProperties);
        when(modelProperties.getModelName()).thenReturn("test-model");
        Session session = new Session(SESSION_ID, "user-1", "New chat", "openai-compatible", "test-model", null, Map.of());
        when(agentRuntimeService.createSession(eq("user-1"), any(), any())).thenReturn(session);
        SessionSummaryDto dto = new SessionSummaryDto(SESSION_ID, "user-1", "New chat", "openai-compatible", "test-model", FIXED_TIME, FIXED_TIME);
        // domainDtoMapper is now a real MapStruct mapper — no stub needed

        mockMvc.perform(post("/api/v1/agent/session"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userId").value("user-1"));
    }

    @Test
    void createSessionWithUserIdUsesProvided() throws Exception {
        mockMvc = sessionMockMvc();
        when(agentProperties.getModel()).thenReturn(modelProperties);
        when(modelProperties.getModelName()).thenReturn("test-model");
        Session session = new Session(SESSION_ID, "custom-user", "New chat", "openai-compatible", "test-model", null, Map.of());
        when(agentRuntimeService.createSession(eq("custom-user"), any(), any())).thenReturn(session);
        SessionSummaryDto dto = new SessionSummaryDto(SESSION_ID, "custom-user", "New chat", "openai-compatible", "test-model", FIXED_TIME, FIXED_TIME);
        // domainDtoMapper is now a real MapStruct mapper — no stub needed

        mockMvc.perform(post("/api/v1/agent/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "custom-user"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userId").value("custom-user"));
    }

    // ── Kanban done found ──

    @Test
    void kanbanDoneTaskFoundReturnsOk() throws Exception {
        mockMvc = kanbanMockMvc();
        TodoDto dto = new TodoDto(SESSION_ID, null, "default", "Test", "done", "medium", FIXED_TIME);
        when(todoService.markDoneForUser(SESSION_ID, "default")).thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/v1/agent/kanban/done/" + SESSION_ID))
            .andExpect(status().isOk());
    }
}
