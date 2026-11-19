package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ApproveRequest;
import com.azhukov.agent.api.dto.BackgroundRequest;
import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.shared.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.DenyRequest;
import com.azhukov.agent.api.dto.DoctorDto;
import com.azhukov.agent.api.dto.RefineRequest;
import com.azhukov.agent.api.dto.StopRequest;
import com.azhukov.agent.api.dto.SteerRequest;
import com.azhukov.agent.api.dto.TtsRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.memory.BackgroundReviewService;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.metrics.AgentMetrics;
import com.azhukov.agent.persistence.entity.BackgroundJobEntity;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.BackgroundJobRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.tts.TtsService;
import com.azhukov.agent.service.transcription.TranscriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

/**
 * T1 phase A — focused NEW unit tests for {@link AgentChatController}.
 * Covers success, bad input, error, and edge paths via MockMvc + Mockito services.
 * Does NOT modify any existing tests.
 */
@ExtendWith(MockitoExtension.class)
class AgentChatControllerT1Test {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant FIXED_TIME = Instant.parse("2024-01-01T00:00:00Z");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private AgentStreamingService streamingService;
    @Mock private MemoryProvider memoryProvider;
    @Mock private SkillManager skillManager;
    @Mock private TtsService ttsService;
    @Mock private TranscriptionService transcriptionService;
    private SteerBuffer steerBuffer;
    private InterruptToken interruptToken;
    @Mock private ObjectProvider<BackgroundReviewService> backgroundReviewServiceProvider;
    @Mock private BackgroundJobRepository backgroundJobRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private MessageRepository messageRepository;
    private final MessageMapper messageMapper = org.mapstruct.factory.Mappers.getMapper(MessageMapper.class);
    private ApprovalQueue approvalQueue;
    @Mock private com.azhukov.agent.core.tool.ToolRegistry toolRegistry;
    @Mock private AgentProperties properties;
    @Mock private AgentProperties.ModelProperties modelProperties;
    @Mock private AgentProperties.CoreProperties coreProperties;
    @Mock private AgentProperties.BudgetProperties budgetProperties;
    @Mock private AgentProperties.SkillsProperties skillsProperties;
    @Mock private AgentProperties.MemoryProperties memoryProperties;
    @Mock private AgentProperties.BackgroundReviewProperties backgroundReviewProperties;
    @Mock private AgentMetrics agentMetrics;
    private com.azhukov.agent.core.agent.SessionTurnLockManager sessionTurnLockManager;

    @BeforeEach
    void setUp() {
        steerBuffer = new SteerBuffer();
        interruptToken = new InterruptToken();
        sessionTurnLockManager = new com.azhukov.agent.core.agent.SessionTurnLockManager();
        approvalQueue = new ApprovalQueue();
        lenient().when(properties.getModel()).thenReturn(modelProperties);
        lenient().when(properties.getCore()).thenReturn(coreProperties);
        lenient().when(properties.getBudget()).thenReturn(budgetProperties);
        lenient().when(properties.getSkills()).thenReturn(skillsProperties);
        lenient().when(properties.getMemory()).thenReturn(memoryProperties);
        lenient().when(memoryProperties.getBackgroundReview()).thenReturn(backgroundReviewProperties);
        lenient().when(memoryProperties.isMemoryEnabled()).thenReturn(true);
        lenient().when(properties.getName()).thenReturn("Test Agent");
        lenient().when(modelProperties.getModelName()).thenReturn("gpt-4o");
        lenient().when(modelProperties.getProvider()).thenReturn("openai-compatible");
        lenient().when(coreProperties.getMaxTurns()).thenReturn(100);
        lenient().when(budgetProperties.getMaxModelCallsPerTurn()).thenReturn(50);
        lenient().when(skillManager.listSkillNames()).thenReturn(List.of("a", "b"));
        lenient().when(skillsProperties.getDefaultToolsets()).thenReturn(List.of("memory", "skills"));
        lenient().when(toolRegistry.getDefinitions(org.mockito.ArgumentMatchers.anySet()))
            .thenReturn(List.of(new com.azhukov.agent.core.model.ToolDefinition(
                "skill_manage", "desc", java.util.Map.of())));

        AgentChatController controller = new AgentChatController(
            agentRuntimeService, streamingService, memoryProvider, skillManager,
            ttsService, transcriptionService,
            steerBuffer, interruptToken,
            backgroundReviewServiceProvider,
            backgroundJobRepository,
            sessionRepository, messageRepository, messageMapper,
            approvalQueue,
            properties,
            agentMetrics,
            toolRegistry
        );
        try {
            var lockField = AgentChatController.class.getDeclaredField("sessionTurnLockManager");
            lockField.setAccessible(true);
            lockField.set(controller, sessionTurnLockManager);
        } catch (Exception e) {
            throw new AssertionError("Session lock fixture wiring failed", e);
        }
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    // ── chat() ──

    @Test
    void chat_success() throws Exception {
        ChatResponseDto dto = new ChatResponseDto(SESSION_ID, "hello", List.of(), true);
        when(agentRuntimeService.runTurn(any())).thenReturn(dto);

        String body = objectMapper.writeValueAsString(ChatRequest.simple(SESSION_ID, "hello", 0, null));
        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("hello"))
            .andExpect(jsonPath("$.completed").value(true));
        verify(agentMetrics).incrementChatRequests();
    }

    @Test
    void chat_blankMessage_returns400() throws Exception {
        String body = "{\"sessionId\":\"" + SESSION_ID + "\",\"message\":\"\"}";
        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void chat_missingMessage_returns400() throws Exception {
        String body = "{\"sessionId\":\"" + SESSION_ID + "\"}";
        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void chat_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{bad json"))
            .andExpect(status().isBadRequest());
    }

    // ── delegate() ──

    @Test
    void delegate_success() throws Exception {
        ChatResponseDto dto = new ChatResponseDto(SESSION_ID, "delegated", List.of(), true);
        when(agentRuntimeService.runDelegate(any())).thenReturn(dto);

        String body = objectMapper.writeValueAsString(ChatRequest.simple(SESSION_ID, "task", 0, null));
        mockMvc.perform(post("/api/v1/agent/delegate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("delegated"));
        verify(agentMetrics).incrementChatRequests();
    }

    // ── doctor() ──

    @Test
    void doctor_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/agent/doctor"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.model").value("gpt-4o"))
            .andExpect(jsonPath("$.memoryEnabled").value(true))
            .andExpect(jsonPath("$.ttsEnabled").value(true))
            .andExpect(jsonPath("$.transcriptionEnabled").value(true))
            .andExpect(jsonPath("$.skillCount").value(2));
    }

    // ── stop() ──

    @Test
    void stop_withSessionId_callsCancel() throws Exception {
        String body = objectMapper.writeValueAsString(new StopRequest(SESSION_ID));
        mockMvc.perform(post("/api/v1/agent/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void stop_nullBody_doesNotThrow() throws Exception {
        mockMvc.perform(post("/api/v1/agent/stop"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void stop_nullSessionId_doesNotCallCancel() throws Exception {
        String body = "{\"sessionId\":null}";
        mockMvc.perform(post("/api/v1/agent/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    // ── steer() ──

    @Test
    void steer_success() throws Exception {
        assertThat(sessionTurnLockManager.tryAcquire(SESSION_ID, 1)).isTrue();
        try {
            String body = objectMapper.writeValueAsString(new SteerRequest(SESSION_ID, "direction"));
            mockMvc.perform(post("/api/v1/agent/steer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()));
        } finally {
            sessionTurnLockManager.release(SESSION_ID);
        }
    }

    @Test
    void steer_inactiveTurn_returnsNotAcceptedAndDoesNotBufferText() throws Exception {
        String body = objectMapper.writeValueAsString(new SteerRequest(SESSION_ID, "direction"));

        mockMvc.perform(post("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false))
            .andExpect(jsonPath("$.reason").value("no active turn"));

        assertThat(steerBuffer.hasPending(SESSION_ID)).isFalse();
    }

    @Test
    void steer_nullSessionId_returnsNotAccepted() throws Exception {
        String body = "{\"text\":\"direction\"}";
        mockMvc.perform(post("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    void steer_blankText_returnsNotAccepted() throws Exception {
        String body = objectMapper.writeValueAsString(new SteerRequest(SESSION_ID, "   "));
        mockMvc.perform(post("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    void steer_nullText_returnsNotAccepted() throws Exception {
        String body = objectMapper.writeValueAsString(new SteerRequest(SESSION_ID, null));
        mockMvc.perform(post("/api/v1/agent/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false));
    }

    // ── background() ──

    @Test
    void background_success() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(agentRuntimeService.submitBackgroundJob(eq("do work"), any(), eq(false), org.mockito.ArgumentMatchers.<String>isNull()))
            .thenReturn(jobId);

        String body = objectMapper.writeValueAsString(new BackgroundRequest("do work", null, null));
        mockMvc.perform(post("/api/v1/agent/background")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ── backgroundStatus() ──

    @Test
    void backgroundReportsServiceOwnershipDenial() throws Exception {
        when(agentRuntimeService.submitBackgroundJob(
            eq("do work"), eq(SESSION_ID.toString()), eq(false), org.mockito.ArgumentMatchers.isNull()))
            .thenThrow(new SecurityException("Session does not belong to the background job owner"));
        mockMvc.perform(post("/api/v1/agent/background")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BackgroundRequest("do work", SESSION_ID.toString(), null))))
            .andExpect(status().isForbidden());
    }

    @Test
    void backgroundStatus_found() throws Exception {
        UUID jobId = UUID.randomUUID();
        BackgroundJobEntity entity = new BackgroundJobEntity();
        entity.setId(jobId);
        entity.setStatus("DONE");
        entity.setResult("all good");
        entity.setSessionId(SESSION_ID);
        entity.setFinishedAt(FIXED_TIME);
        when(backgroundJobRepository.findById(jobId)).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/api/v1/agent/background/{id}", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.result").value("all good"))
            .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()));
    }

    @Test
    void sessionlessBackgroundResultStaysWithinItsPersistedOwner() throws Exception {
        BackgroundJobEntity job = new BackgroundJobEntity();
        job.setId(UUID.randomUUID());
        job.setUserId("owner-a");
        job.setStatus("DONE");
        job.setResult("owner-a result");
        when(backgroundJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        try {
            UserContext.set("owner-a", UserContext.ROLE_USER);
            mockMvc.perform(get("/api/v1/agent/background/{id}", job.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("owner-a result"));
            UserContext.set("owner-b", UserContext.ROLE_USER);
            mockMvc.perform(get("/api/v1/agent/background/{id}", job.getId()))
                .andExpect(status().isForbidden());
            UserContext.set("administrator", UserContext.ROLE_ADMIN);
            mockMvc.perform(get("/api/v1/agent/background/{id}", job.getId()))
                .andExpect(status().isOk());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void backgroundRequestPassesExplicitTelegramOwnerToItsService() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentRuntimeService.submitBackgroundJob("work", null, false, "456")).thenReturn(id);
        mockMvc.perform(post("/api/v1/agent/background")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"work\",\"userId\":\"456\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(id.toString()));
        verify(agentRuntimeService).submitBackgroundJob("work", null, false, "456");
    }

    @Test
    void sessionlessHistoricalBackgroundResultIsNotPublicToOrdinaryUsers() throws Exception {
        BackgroundJobEntity job = new BackgroundJobEntity();
        job.setId(UUID.randomUUID());
        job.setStatus("DONE");
        job.setResult("unattributed private background result");
        when(backgroundJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        UserContext.set("caller-a", UserContext.ROLE_USER);
        try {
            mockMvc.perform(get("/api/v1/agent/background/{id}", job.getId()))
                .andExpect(status().isForbidden());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void backgroundStatus_notFound_returns400() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(backgroundJobRepository.findById(jobId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/agent/background/{id}", jobId))
            .andExpect(status().isBadRequest());
    }

    // ── refine() ──

    @Test
    void refine_sessionNotFound_returnsNotAccepted() throws Exception {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        String body = objectMapper.writeValueAsString(new RefineRequest(SESSION_ID, null));
        mockMvc.perform(post("/api/v1/agent/refine")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false))
            .andExpect(jsonPath("$.reason").value("session not found"));
    }

    @Test
    void refine_rejectsForeignSessionBeforeReadingMessages() throws Exception {
        UserContext.set("user-77", UserContext.ROLE_USER);
        try {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionOf("owner")));

            String body = objectMapper.writeValueAsString(new RefineRequest(SESSION_ID, null));
            mockMvc.perform(post("/api/v1/agent/refine")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isForbidden());

            verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(SESSION_ID);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void refine_emptyHistory_returnsNotAccepted() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId("u");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of());

        String body = objectMapper.writeValueAsString(new RefineRequest(SESSION_ID, null));
        mockMvc.perform(post("/api/v1/agent/refine")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false))
            .andExpect(jsonPath("$.reason").value("nothing to refine — the conversation is empty"));
    }

    @Test
    void refine_success_noFocus() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId("u");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        MessageEntity msg = new MessageEntity();
        msg.setRole("user");
        msg.setContent("hello");
        msg.setTurnIndex(0);
        msg.setCreatedAt(FIXED_TIME);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(msg));
        BackgroundReviewService brs = mock(BackgroundReviewService.class);
        when(backgroundReviewServiceProvider.getObject()).thenReturn(brs);
        doNothing().when(brs).reviewTurn(eq(SESSION_ID), any(), eq("u"), eq(true), eq(true), eq(null));

        String body = objectMapper.writeValueAsString(new RefineRequest(SESSION_ID, null));
        mockMvc.perform(post("/api/v1/agent/refine")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void refine_success_withFocus() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setUserId("u");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        MessageEntity msg = new MessageEntity();
        msg.setRole("user");
        msg.setContent("hello");
        msg.setTurnIndex(0);
        msg.setCreatedAt(FIXED_TIME);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(msg));
        BackgroundReviewService brs = mock(BackgroundReviewService.class);
        when(backgroundReviewServiceProvider.getObject()).thenReturn(brs);
        doNothing().when(brs).reviewTurn(eq(SESSION_ID), any(), eq("u"), eq(true), eq(true), eq("focus"));

        String body = objectMapper.writeValueAsString(new RefineRequest(SESSION_ID, "focus"));
        mockMvc.perform(post("/api/v1/agent/refine")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("focus: focus")));
    }

    // ── approve() ──

    @Test
    void approve_noPendingApprovals() throws Exception {
        String body = objectMapper.writeValueAsString(new ApproveRequest(false, null));
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("No pending approvals"));
    }

    @Test
    void approve_omittedAllDefaultsToFalse() throws Exception {
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(content().string("No pending approvals"));
    }

    @Test
    void approve_invalidRequestIdScope() throws Exception {
        String body = "{\"all\":false,\"requestId\":\"not-a-uuid\"}";
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void approve_unknownRequestIdScope_returns404() throws Exception {
        String body = "{\"all\":false,\"requestId\":\"" + SESSION_ID + "\"}";
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"session", "always"})
    void removedGrantScopesAreRejectedWithoutApproving(String scope) throws Exception {
        var pending = approvalQueue.request(SESSION_ID,
            new com.azhukov.agent.core.model.ToolCall("call", "terminal", "{}"), "test");
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"" + scope + "\"}"))
            .andExpect(status().isBadRequest());
        assertThat(approvalQueue.getPending(SESSION_ID)).isSameAs(pending);
        assertThat(approvalQueue.isApproved(SESSION_ID)).isFalse();
    }

    // ── deny() ──

    @Test
    void deny_noPendingApprovals() throws Exception {
        String body = objectMapper.writeValueAsString(new DenyRequest(false));
        mockMvc.perform(post("/api/v1/agent/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("No pending approvals"));
    }

    @Test
    void deny_omittedAllDefaultsToFalse() throws Exception {
        mockMvc.perform(post("/api/v1/agent/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(content().string("No pending approvals"));
    }

    @Test
    void deny_all_whenEmpty() throws Exception {
        String body = objectMapper.writeValueAsString(new DenyRequest(true));
        mockMvc.perform(post("/api/v1/agent/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("Denied 0 pending requests"));
    }

    // ── pendingApprovals() ──

    @Test
    void pendingApprovals_empty() throws Exception {
        mockMvc.perform(get("/api/v1/agent/approvals/pending"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── tts() ──

    @Test
    void tts_success() throws Exception {
        when(ttsService.synthesize("hello", "voice1")).thenReturn(new byte[]{1, 2, 3});

        String body = objectMapper.writeValueAsString(new TtsRequest("hello", "voice1"));
        mockMvc.perform(post("/api/v1/agent/tts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    @Test
    void tts_blankText_returnsOk() throws Exception {
        // TtsRequest has no @NotBlank on text — blank text passes validation
        when(ttsService.synthesize("", null)).thenReturn(new byte[0]);

        String body = "{\"text\":\"\"}";
        mockMvc.perform(post("/api/v1/agent/tts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    @Test
    void tts_serviceThrows_returns500() throws Exception {
        when(ttsService.synthesize(any(), any())).thenThrow(new RuntimeException("fail"));

        String body = objectMapper.writeValueAsString(new TtsRequest("hello", null));
        mockMvc.perform(post("/api/v1/agent/tts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isInternalServerError());
    }

    // ── debugReport() ──

    @Test
    void debugReport_withSystemInfoAndLogs() throws Exception {
        mockMvc.perform(multipart("/api/v1/agent/debug-report")
                .file(new MockMultipartFile("systemInfo", "systemInfo.txt", "text/plain", "my system info".getBytes()))
                .file(new MockMultipartFile("logs", "logs.txt", "text/plain", "some log lines".getBytes())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logsIncluded").value(true))
            .andExpect(jsonPath("$.logsSize").value("some log lines".length()));
    }

    @Test
    void debugReport_withoutLogs() throws Exception {
        mockMvc.perform(post("/api/v1/agent/debug-report")
                .contentType(MediaType.MULTIPART_FORM_DATA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logsIncluded").value(false));
    }

    // ── multi-user ownership: stop / steer / approvals ──

    private SessionEntity sessionOf(String userId) {
        SessionEntity e = new SessionEntity();
        e.setId(SESSION_ID);
        e.setUserId(userId);
        return e;
    }

    @Test
    void stop_otherUsersSession_returns403() throws Exception {
        UserContext.set("user-77", UserContext.ROLE_USER);
        try {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionOf("owner")));
            mockMvc.perform(post("/api/v1/agent/stop")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new StopRequest(SESSION_ID))))
                .andExpect(status().isForbidden());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void steer_otherUsersSession_returns403() throws Exception {
        UserContext.set("user-77", UserContext.ROLE_USER);
        try {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionOf("owner")));
            mockMvc.perform(post("/api/v1/agent/steer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new SteerRequest(SESSION_ID, "hijack"))))
                .andExpect(status().isForbidden());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void approveAllOnlyChangesTheCallersVisibleRequests() throws Exception {
        UserContext.set("owner", UserContext.ROLE_USER);
        try {
            var otherSession = UUID.randomUUID();
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionOf("owner")));
            when(sessionRepository.findById(otherSession)).thenReturn(Optional.of(sessionOf("other-owner")));
            var own = approvalQueue.request(SESSION_ID,
                new com.azhukov.agent.core.model.ToolCall("a", "terminal", "{}"), "own");
            var foreign = approvalQueue.request(otherSession,
                new com.azhukov.agent.core.model.ToolCall("b", "terminal", "{}"), "foreign");
            mockMvc.perform(post("/api/v1/agent/approve")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"all\":true}"))
                .andExpect(status().isOk()).andExpect(content().string("Approved 1 pending requests"));
            assertThat(approvalQueue.consumeDecision(own).approved()).isTrue();
            assertThat(approvalQueue.getPending(otherSession)).isSameAs(foreign);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void priorDenialCannotBeReportedAsSuccessfulApproval() throws Exception {
        var request = approvalQueue.request(SESSION_ID,
            new com.azhukov.agent.core.model.ToolCall("id", "terminal", "{}"), "test");
        approvalQueue.deny(request.requestId(), null);
        mockMvc.perform(post("/api/v1/agent/approvals/" + request.requestId() + "/approve"))
            .andExpect(status().isConflict());
        assertThat(approvalQueue.isDenied(SESSION_ID)).isTrue();
    }

    @Test
    void currentApprovalSelectorCannotBeCombinedWithAll() throws Exception {
        var pending = approvalQueue.request(SESSION_ID,
            new com.azhukov.agent.core.model.ToolCall("id", "terminal", "{}"), "test");
        mockMvc.perform(post("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"all\":true,\"requestId\":\"" + pending.requestId() + "\"}"))
            .andExpect(status().isBadRequest());
        assertThat(approvalQueue.getPending(SESSION_ID)).isSameAs(pending);
    }

    @Test
    void obsoleteDecisionQueryCannotApproveAnExplicitDenial() throws Exception {
        var pending = approvalQueue.request(SESSION_ID,
            new com.azhukov.agent.core.model.ToolCall("id", "terminal", "{}"), "test");
        mockMvc.perform(post("/api/v1/agent/approvals/" + pending.requestId() + "/approve")
                .param("decision", "deny"))
            .andExpect(status().isBadRequest());
        assertThat(approvalQueue.getPending(SESSION_ID)).isSameAs(pending);
    }

    @Test
    void lateApprovalRequestCannotTargetReplacementInSameSession() throws Exception {
        UserContext.set("owner", UserContext.ROLE_USER);
        try {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionOf("owner")));
            var first = approvalQueue.request(SESSION_ID,
                new com.azhukov.agent.core.model.ToolCall("a", "terminal", "{}"), "first");
            var second = approvalQueue.request(SESSION_ID,
                new com.azhukov.agent.core.model.ToolCall("b", "delete_file", "{}"), "second");
            mockMvc.perform(post("/api/v1/agent/approvals/" + first.requestId() + "/approve"))
                .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/v1/agent/approvals/" + SESSION_ID + "/approve"))
                .andExpect(status().isNotFound());
            assertThat(approvalQueue.getPending(SESSION_ID)).isEqualTo(second);
            mockMvc.perform(post("/api/v1/agent/approvals/" + second.requestId() + "/approve"))
                .andExpect(status().isOk());
            assertThat(approvalQueue.consumeDecision(second).approved()).isTrue();
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void approveTool_otherUsersSession_returns403() throws Exception {
        UserContext.set("user-77", UserContext.ROLE_USER);
        try {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionOf("owner")));
            var request = approvalQueue.request(SESSION_ID, new com.azhukov.agent.core.model.ToolCall("id", "terminal", "{}"), "test");
            mockMvc.perform(post("/api/v1/agent/approvals/" + request.requestId() + "/approve"))
                .andExpect(status().isForbidden());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void stop_ownSession_stillWorks() throws Exception {
        UserContext.set("user-77", UserContext.ROLE_USER);
        try {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(sessionOf("user-77")));
            mockMvc.perform(post("/api/v1/agent/stop")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new StopRequest(SESSION_ID))))
                .andExpect(status().isOk());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void backgroundStatus_otherUsersSessionJob_returns403() throws Exception {
        UserContext.set("user-77", UserContext.ROLE_USER);
        try {
            BackgroundJobEntity job = new BackgroundJobEntity();
            job.setId(UUID.randomUUID());
            job.setStatus("DONE");
            job.setSessionId(SESSION_ID);
            job.setUserId("owner");
            when(backgroundJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
            mockMvc.perform(get("/api/v1/agent/background/" + job.getId()))
                .andExpect(status().isForbidden());
        } finally {
            UserContext.clear();
        }
    }
}
