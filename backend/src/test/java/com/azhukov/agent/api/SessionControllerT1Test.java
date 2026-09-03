package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.CompressRequest;
import com.azhukov.agent.api.dto.ContextInfoDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.dto.SnapshotRequest;
import com.azhukov.agent.api.dto.TodoDto;
import com.azhukov.agent.api.dto.UndoRequest;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.CheckpointManager;
import com.azhukov.agent.service.TodoService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 phase A — focused NEW unit tests for {@link SessionController}.
 * Covers success, bad input, error, and edge paths via MockMvc + Mockito services.
 * Does NOT modify any existing tests.
 */
@ExtendWith(MockitoExtension.class)
class SessionControllerT1Test {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant FIXED_TIME = Instant.parse("2024-01-01T00:00:00Z");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private AgentRuntimeService agentRuntimeService;
    private final DomainDtoMapper domainDtoMapper = Mappers.getMapper(DomainDtoMapper.class);
    @Mock private AgentProperties properties;
    @Mock private AgentProperties.ModelProperties modelProperties;
    @Mock private AgentProperties.CoreProperties coreProperties;
    @Mock private AgentProperties.BudgetProperties budgetProperties;
    @Mock private CheckpointManager checkpointManager;
    @Mock private TodoService todoService;
    @Mock private MessageRepository messageRepository;
    @Mock private SessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getModel()).thenReturn(modelProperties);
        lenient().when(properties.getCore()).thenReturn(coreProperties);
        lenient().when(properties.getBudget()).thenReturn(budgetProperties);
        SessionController controller = new SessionController(
            agentRuntimeService, domainDtoMapper, properties, checkpointManager,
            todoService, messageRepository, sessionRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    // ── sessions() ──

    @Test
    void sessions_returnsList() throws Exception {
        SessionSummaryDto dto = new SessionSummaryDto(SESSION_ID, "user-1", "t", "p", "m", FIXED_TIME, FIXED_TIME);
        when(agentRuntimeService.listSessions()).thenReturn(List.of(dto));
        mockMvc.perform(get("/api/v1/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$[0].userId").value("user-1"));
    }

    @Test
    void sessions_emptyList() throws Exception {
        when(agentRuntimeService.listSessions()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ── createSession() ──

    @Test
    void createSession_withUserId_returns201() throws Exception {
        Session session = new Session(SESSION_ID, "alice", null, "openai-compatible", "gpt-4o", null, Map.of(), null);
        when(agentRuntimeService.createSession(eq("alice"), eq("openai-compatible"), any())).thenReturn(session);
        when(modelProperties.getModelName()).thenReturn("gpt-4o");

        mockMvc.perform(post("/api/v1/agent/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"alice\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.userId").value("alice"));
    }

    @Test
    void createSession_nullBody_usesDefaultUser() throws Exception {
        Session session = new Session(SESSION_ID, AgentProperties.DEFAULT_USER_ID, null, "openai-compatible", "gpt-4o", null, Map.of(), null);
        when(agentRuntimeService.createSession(eq(AgentProperties.DEFAULT_USER_ID), eq("openai-compatible"), any())).thenReturn(session);
        when(modelProperties.getModelName()).thenReturn("gpt-4o");

        mockMvc.perform(post("/api/v1/agent/session"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userId").value(AgentProperties.DEFAULT_USER_ID));
    }

    @Test
    void createSession_nullUserId_usesDefaultUser() throws Exception {
        Session session = new Session(SESSION_ID, AgentProperties.DEFAULT_USER_ID, null, "openai-compatible", "gpt-4o", null, Map.of(), null);
        when(agentRuntimeService.createSession(eq(AgentProperties.DEFAULT_USER_ID), eq("openai-compatible"), any())).thenReturn(session);
        when(modelProperties.getModelName()).thenReturn("gpt-4o");

        mockMvc.perform(post("/api/v1/agent/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":null}"))
            .andExpect(status().isCreated());
    }

    @Test
    void createSession_serviceThrows_returns500() throws Exception {
        when(agentRuntimeService.createSession(any(), any(), any()))
            .thenThrow(new RuntimeException("boom"));
        when(modelProperties.getModelName()).thenReturn("gpt-4o");

        mockMvc.perform(post("/api/v1/agent/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isInternalServerError());
    }

    // ── history() ──

    @Test
    void history_returnsMappedMessages() throws Exception {
        MessageEntity m = new MessageEntity();
        m.setRole("user");
        m.setContent("hello");
        m.setTurnIndex(2);
        m.setCreatedAt(FIXED_TIME);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(m));

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/history", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].role").value("user"))
            .andExpect(jsonPath("$[0].content").value("hello"))
            .andExpect(jsonPath("$[0].turnIndex").value(2));
    }

    @Test
    void history_emptyRepository_returnsEmptyArray() throws Exception {
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/history", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void history_nullFields_replacedWithDefaults() throws Exception {
        MessageEntity m = new MessageEntity();
        m.setRole(null);
        m.setContent(null);
        m.setTurnIndex(null);
        m.setCreatedAt(null);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(m));

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/history", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].role").value("?"))
            .andExpect(jsonPath("$[0].content").value(""))
            .andExpect(jsonPath("$[0].turnIndex").value(0))
            .andExpect(jsonPath("$[0].createdAt").value(""));
    }

    // ── getContext() ──

    @Test
    void getContext_success() throws Exception {
        ContextInfoDto dto = new ContextInfoDto(SESSION_ID, 5, 120, List.of("web_search"));
        when(agentRuntimeService.getContext(SESSION_ID)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/context", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messageCount").value(5))
            .andExpect(jsonPath("$.tokenEstimate").value(120));
    }

    @Test
    void getContext_serviceThrows_returnsBadRequest() throws Exception {
        when(agentRuntimeService.getContext(SESSION_ID))
            .thenThrow(new IllegalArgumentException("not found"));

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/context", SESSION_ID))
            .andExpect(status().isBadRequest());
    }

    // ── resetSession() ──

    @Test
    void resetSession_success() throws Exception {
        doNothing().when(agentRuntimeService).resetSession(SESSION_ID);

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/reset", SESSION_ID))
            .andExpect(status().isOk());
        verify(agentRuntimeService).resetSession(SESSION_ID);
    }

    @Test
    void resetSession_serviceThrows_returnsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("no session")).when(agentRuntimeService).resetSession(SESSION_ID);

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/reset", SESSION_ID))
            .andExpect(status().isBadRequest());
    }

    // ── getUsage() ──

    @Test
    void getUsage_success() throws Exception {
        UsageDto dto = new UsageDto(SESSION_ID, 3, 200, 0.5, List.of("gpt-4o"));
        when(agentRuntimeService.getUsage(SESSION_ID)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/usage", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messageCount").value(3))
            .andExpect(jsonPath("$.cost").value(0.5));
    }

    // ── sessionsByUserId() ──

    @Test
    void sessionsByUserId_returnsList() throws Exception {
        SessionSummaryDto dto = new SessionSummaryDto(SESSION_ID, "u", "t", "p", "m", FIXED_TIME, FIXED_TIME);
        when(agentRuntimeService.listSessionsByUserId("u")).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/agent/sessions/{userId}", "u"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].userId").value("u"));
    }

    // ── compressSession() (path-variable form) ──

    @Test
    void compressSession_withFocusTopicAndKeepLastN() throws Exception {
        doNothing().when(agentRuntimeService).compressSession(eq(SESSION_ID), eq("topic"), eq(5));

        String body = objectMapper.writeValueAsString(new CompressRequest(SESSION_ID, "topic", 5));
        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/compress", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Focus: topic")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Kept last 5")));
    }

    @Test
    void compressSession_nullBody_focusDefaultsToNull() throws Exception {
        doNothing().when(agentRuntimeService).compressSession(eq(SESSION_ID), eq(null), eq(null));

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/compress", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(content().string("Context compressed."));
    }

    @Test
    void compressSession_focusFallbackToFocusAlias() throws Exception {
        // focusTopic null but request present -> controller uses request.focus() which returns focusTopic (null)
        // Test the path where request is non-null but focusTopic is null: focus stays null, no "Focus:" in output
        lenient().doNothing().when(agentRuntimeService).compressSession(eq(SESSION_ID), isNull(), isNull());

        String body = "{\"sessionId\":\"" + SESSION_ID + "\"}";
        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/compress", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("Context compressed."));
    }

    @Test
    void compressSession_serviceThrows_returnsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("bad")).when(agentRuntimeService)
            .compressSession(any(), any(), any());

        String body = objectMapper.writeValueAsString(new CompressRequest(SESSION_ID, "topic", 5));
        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/compress", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    // ── undoTurns() (path-variable form) ──

    @Test
    void undoTurns_defaultOne() throws Exception {
        when(agentRuntimeService.undoTurns(SESSION_ID, 1)).thenReturn(1);

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/undo", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(content().string("1"));
    }

    @Test
    void undoTurns_customTurns() throws Exception {
        when(agentRuntimeService.undoTurns(SESSION_ID, 3)).thenReturn(3);

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/undo", SESSION_ID)
                .param("turns", "3"))
            .andExpect(status().isOk())
            .andExpect(content().string("3"));
    }

    // ── compressSessionBody() (convenience form) ──

    @Test
    void compressSessionBody_noSessionId_returns400() throws Exception {
        // body with null sessionId -> IllegalArgumentException -> 400
        mockMvc.perform(post("/api/v1/agent/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void compressSessionBody_success() throws Exception {
        doNothing().when(agentRuntimeService).compressSession(eq(SESSION_ID), eq("topic"), eq(2));

        String body = objectMapper.writeValueAsString(new CompressRequest(SESSION_ID, "topic", 2));
        mockMvc.perform(post("/api/v1/agent/compress")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Focus: topic")));
    }

    @Test
    void compressSessionBody_nullBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/agent/compress"))
            .andExpect(status().isBadRequest());
    }

    // ── undoTurnsBody() ──

    @Test
    void undoTurnsBody_success() throws Exception {
        when(agentRuntimeService.undoTurns(SESSION_ID, 2)).thenReturn(2);

        String body = objectMapper.writeValueAsString(new UndoRequest(SESSION_ID, 2));
        mockMvc.perform(post("/api/v1/agent/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("2"));
    }

    @Test
    void undoTurnsBody_nullTurns_defaultsToOne() throws Exception {
        when(agentRuntimeService.undoTurns(SESSION_ID, 1)).thenReturn(1);

        String body = "{\"sessionId\":\"" + SESSION_ID + "\"}";
        mockMvc.perform(post("/api/v1/agent/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("1"));
    }

    @Test
    void undoTurnsBody_zeroTurns_defaultsToOne() throws Exception {
        when(agentRuntimeService.undoTurns(SESSION_ID, 1)).thenReturn(1);

        String body = "{\"sessionId\":\"" + SESSION_ID + "\",\"turns\":0}";
        mockMvc.perform(post("/api/v1/agent/undo")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("1"));
    }

    // ── switchModel() ──

    @Test
    void switchModel_success() throws Exception {
        doNothing().when(agentRuntimeService).switchModel(SESSION_ID, "gpt-4o", "openai");

        String body = "{\"sessionId\":\"" + SESSION_ID + "\",\"model\":\"gpt-4o\",\"provider\":\"openai\"}";
        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.model").value("gpt-4o"));
    }

    @Test
    void switchModel_nullSessionId_returnsOkFalse() throws Exception {
        String body = "{\"model\":\"gpt-4o\"}";
        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void switchModel_blankModel_returnsOkFalse() throws Exception {
        String body = "{\"sessionId\":\"" + SESSION_ID + "\",\"model\":\"\"}";
        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false));
        verify(agentRuntimeService, never()).switchModel(any(), any(), any());
    }

    @Test
    void switchModel_nullProvider_includedAsEmptyString() throws Exception {
        doNothing().when(agentRuntimeService).switchModel(SESSION_ID, "gpt-4o", null);

        String body = "{\"sessionId\":\"" + SESSION_ID + "\",\"model\":\"gpt-4o\"}";
        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.provider").value(""));
    }

    @Test
    void switchModel_serviceThrows_returnsOkFalse() throws Exception {
        doThrow(new RuntimeException("model unavailable"))
            .when(agentRuntimeService).switchModel(SESSION_ID, "bad", null);

        String body = "{\"sessionId\":\"" + SESSION_ID + "\",\"model\":\"bad\"}";
        mockMvc.perform(post("/api/v1/agent/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.error").value("model unavailable"));
    }

    // ── getCurrentModel() ──

    @Test
    void getCurrentModel_noSessionId_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/agent/model"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("sessionId required"));
    }

    @Test
    void getCurrentModel_withSessionId_entityFound() throws Exception {
        ContextInfoDto ctx = new ContextInfoDto(SESSION_ID, 4, 50, List.of());
        when(agentRuntimeService.getContext(SESSION_ID)).thenReturn(ctx);

        SessionEntity entity = new SessionEntity();
        entity.setModelName("claude-3");
        entity.setModelProvider("anthropic");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));

        mockMvc.perform(get("/api/v1/agent/model").param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("claude-3"))
            .andExpect(jsonPath("$.provider").value("anthropic"))
            .andExpect(jsonPath("$.messageCount").value(4));
    }

    @Test
    void getCurrentModel_withSessionId_entityNotFound_fallsBackToDefault() throws Exception {
        ContextInfoDto ctx = new ContextInfoDto(SESSION_ID, 1, 10, List.of());
        when(agentRuntimeService.getContext(SESSION_ID)).thenReturn(ctx);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());
        when(modelProperties.getModelName()).thenReturn("default-model");

        mockMvc.perform(get("/api/v1/agent/model").param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("default-model"));
    }

    @Test
    void getCurrentModel_defaultModelNameNull() throws Exception {
        ContextInfoDto ctx = new ContextInfoDto(SESSION_ID, 1, 10, List.of());
        when(agentRuntimeService.getContext(SESSION_ID)).thenReturn(ctx);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());
        when(modelProperties.getModelName()).thenReturn(null);

        mockMvc.perform(get("/api/v1/agent/model").param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("unknown"));
    }

    @Test
    void getCurrentModel_defaultModelNameBlank() throws Exception {
        ContextInfoDto ctx = new ContextInfoDto(SESSION_ID, 1, 10, List.of());
        when(agentRuntimeService.getContext(SESSION_ID)).thenReturn(ctx);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());
        when(modelProperties.getModelName()).thenReturn("  ");

        mockMvc.perform(get("/api/v1/agent/model").param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("unknown"));
    }

    @Test
    void getCurrentModel_serviceThrows_returnsErrorMap() throws Exception {
        when(agentRuntimeService.getContext(SESSION_ID))
            .thenThrow(new RuntimeException("fail"));

        mockMvc.perform(get("/api/v1/agent/model").param("sessionId", SESSION_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error").value("fail"));
    }

    // ── createSnapshot() ──

    @Test
    void createSnapshot_success() throws Exception {
        when(checkpointManager.snapshot(org.mockito.ArgumentMatchers.nullable(String.class), eq("desc"))).thenReturn(null);

        String body = objectMapper.writeValueAsString(new SnapshotRequest("desc"));
        mockMvc.perform(post("/api/v1/agent/snapshot")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        verify(checkpointManager).snapshot(org.mockito.ArgumentMatchers.isNull(), eq("desc"));
    }

    @Test
    void createSnapshot_nullDescription_defaultsToEmpty() throws Exception {
        when(checkpointManager.snapshot(org.mockito.ArgumentMatchers.nullable(String.class), eq(""))).thenReturn(null);

        String body = "{}";
        mockMvc.perform(post("/api/v1/agent/snapshot")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        verify(checkpointManager).snapshot(org.mockito.ArgumentMatchers.isNull(), eq(""));
    }

    // ── branchSession() ──

    @Test
    void branchSession_success() throws Exception {
        SessionSummaryDto dto = new SessionSummaryDto(SESSION_ID, "user-1", "branch", "p", "m", FIXED_TIME, FIXED_TIME);
        when(agentRuntimeService.branchSession(SESSION_ID, "branch")).thenReturn(dto);

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/branch", SESSION_ID)
                .param("name", "branch"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("branch"));
    }

    @Test
    void branchSession_noNameParam() throws Exception {
        SessionSummaryDto dto = new SessionSummaryDto(SESSION_ID, "user-1", null, "p", "m", FIXED_TIME, FIXED_TIME);
        when(agentRuntimeService.branchSession(SESSION_ID, null)).thenReturn(dto);

        mockMvc.perform(post("/api/v1/agent/session/{sessionId}/branch", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()));
    }

    // ── getPlan() ──

    @Test
    void getPlan_success() throws Exception {
        TodoDto todo = new TodoDto(UUID.randomUUID(), SESSION_ID, "user-1", "task", "pending", "high", FIXED_TIME);
        when(todoService.listBySessionId(SESSION_ID)).thenReturn(List.of(todo));

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/plan", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session_id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.todos[0].title").value("task"));
    }

    @Test
    void getPlan_emptyList() throws Exception {
        when(todoService.listBySessionId(SESSION_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agent/session/{sessionId}/plan", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.todos").isArray())
            .andExpect(jsonPath("$.todos.length()").value(0));
    }
}