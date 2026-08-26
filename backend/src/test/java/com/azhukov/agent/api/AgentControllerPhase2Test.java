package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.BackgroundRequest;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.service.RuntimeConfigService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentControllerPhase2Test {

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
    @Mock private TtsService ttsService;
    @Mock private TranscriptionService transcriptionService;
    @Mock private AgentProperties agentProperties;
    private final DomainDtoMapper domainDtoMapper = Mappers.getMapper(DomainDtoMapper.class);
    @Mock private RuntimeConfigService runtimeConfigService;
    @Mock private com.azhukov.agent.service.CliRuntimeSettingsService cliRuntimeSettingsService;
    @Mock private com.azhukov.agent.core.security.UrlSafetyHandler urlSafetyHandler;
    @Mock private com.azhukov.agent.service.TodoService todoService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

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

    private MockMvc skillMockMvc() {
        SkillController controller = new SkillController(skillManager, agentRuntimeService, skillAuditLogRepository, mock(com.azhukov.agent.core.skill.SkillsHubService.class), domainDtoMapper);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private MockMvc sessionMockMvc() {
        SessionController controller = new SessionController(
            agentRuntimeService, domainDtoMapper, agentProperties, checkpointManager, todoService, null, null);
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private MockMvc chatMockMvc() {
        AgentChatController controller = new AgentChatController(
            agentRuntimeService, streamingService, memoryProvider, skillManager,
            ttsService, transcriptionService,
            new com.azhukov.agent.core.agent.SteerBuffer(),
            new com.azhukov.agent.core.agent.InterruptToken(),
            null,
            org.mockito.Mockito.mock(com.azhukov.agent.persistence.repository.BackgroundJobRepository.class),
            null, null, null,
            new com.azhukov.agent.core.security.ApprovalQueue(),
            agentProperties,
            null
        );
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void restartReturns200() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        doNothing().when(agentRuntimeService).restart();

        mockMvc.perform(post("/api/v1/agent/restart"))
            .andExpect(status().isOk());
    }

    @Test
    void reloadMcpReturns200() throws Exception {
        mockMvc = runtimeSettingsMockMvc();
        doNothing().when(agentRuntimeService).reloadMcp();

        mockMvc.perform(post("/api/v1/agent/reload-mcp"))
            .andExpect(status().isOk());
    }

    @Test
    void reloadSkillsReturns200() throws Exception {
        mockMvc = skillMockMvc();
        doNothing().when(agentRuntimeService).reloadSkills();

        mockMvc.perform(post("/api/v1/agent/reload-skills"))
            .andExpect(status().isOk());
    }

    @Test
    void bundlesReturns200WithArray() throws Exception {
        mockMvc = skillMockMvc();
        when(agentRuntimeService.listBundles()).thenReturn(List.of("bundle-1", "bundle-2"));

        mockMvc.perform(get("/api/v1/agent/bundles"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0]").value("bundle-1"))
            .andExpect(jsonPath("$[1]").value("bundle-2"));
    }

    @Test
    void branchSessionReturns200WithSessionDto() throws Exception {
        mockMvc = sessionMockMvc();
        SessionSummaryDto dto = new SessionSummaryDto(
            SESSION_ID,
            "user-1",
            "Branch of Test session",
            "openai-compatible",
            "gpt-4",
            FIXED_TIME,
            FIXED_TIME
        );
        when(agentRuntimeService.branchSession(eq(SESSION_ID), any())).thenReturn(dto);

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/branch", SESSION_ID)
                .param("name", "Branch of Test session"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.userId").value("user-1"))
            .andExpect(jsonPath("$.title").value("Branch of Test session"))
            .andExpect(jsonPath("$.modelProvider").value("openai-compatible"))
            .andExpect(jsonPath("$.modelName").value("gpt-4"))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void backgroundReturns200WithJobId() throws Exception {
        mockMvc = chatMockMvc();
        java.util.UUID jobId = java.util.UUID.randomUUID();
        when(agentRuntimeService.submitBackgroundJob(any(String.class), any(), any(Boolean.class)))
            .thenReturn(jobId);

        String requestBody = objectMapper.writeValueAsString(
            new BackgroundRequest("do something", null));

        mockMvc.perform(post("/api/v1/agent/background")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"jobId\": \"" + jobId + "\", \"status\": \"PENDING\"}"));
    }
}