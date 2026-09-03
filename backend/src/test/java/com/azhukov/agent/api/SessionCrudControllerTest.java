package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.MessageListDto;
import com.azhukov.agent.api.dto.MessageDto;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.SessionQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Focused unit tests for {@link SessionCrudController} — covers success,
 * bad input/error/edge cases for list, create, get, update, delete, messages,
 * and session-scoped chat (sync + streaming).
 */
@ExtendWith(MockitoExtension.class)
class SessionCrudControllerTest {

    private static final UUID SESSION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private SessionQueryService sessionQueryService;
    @Mock private AgentRuntimeService agentRuntimeService;
    @Mock private AgentStreamingService streamingService;

    @BeforeEach
    void setUp() {
        SessionCrudController controller = new SessionCrudController(sessionQueryService, agentRuntimeService, streamingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
    }

    // ── List sessions ──

    @Test
    void listSessionsReturnsMapWithDefaults() throws Exception {
        when(sessionQueryService.listSessions(50, 0, null))
            .thenReturn(Map.of("object", "list", "data", List.of()));

        mockMvc.perform(get("/api/v2/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"));
    }

    @Test
    void listSessionsWithCustomPaginationAndUserId() throws Exception {
        when(sessionQueryService.listSessions(10, 20, "user-1"))
            .thenReturn(Map.of("object", "list", "data", List.of(), "limit", 10, "offset", 20));

        mockMvc.perform(get("/api/v2/sessions")
                .param("limit", "10")
                .param("offset", "20")
                .param("userId", "user-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(10))
            .andExpect(jsonPath("$.offset").value(20));
    }

    // ── Create session ──

    @Test
    void createSessionReturns201WithLocation() throws Exception {
        when(sessionQueryService.createSession(null, null, null))
            .thenReturn(Map.of("id", SESSION_ID.toString(), "object", "session"));

        mockMvc.perform(post("/api/v2/sessions"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v2/sessions/" + SESSION_ID))
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()));
    }

    @Test
    void createSessionWithBodyPassesFields() throws Exception {
        when(sessionQueryService.createSession("user-1", "gpt-4", "My Session"))
            .thenReturn(Map.of("id", SESSION_ID.toString()));

        mockMvc.perform(post("/api/v2/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"userId":"user-1","model":"gpt-4","title":"My Session"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()));

        verify(sessionQueryService).createSession("user-1", "gpt-4", "My Session");
    }

    // ── Get session ──

    @Test
    void getSessionReturns200WhenFound() throws Exception {
        when(sessionQueryService.getSession(SESSION_ID))
            .thenReturn(Optional.of(Map.of("id", SESSION_ID.toString(), "title", "Test")));

        mockMvc.perform(get("/api/v2/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.title").value("Test"));
    }

    @Test
    void getSessionReturns404WhenNotFound() throws Exception {
        when(sessionQueryService.getSession(SESSION_ID))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v2/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isNotFound());
    }

    // ── Update session ──

    @Test
    void updateSessionReturns200WhenFound() throws Exception {
        when(sessionQueryService.updateSession(SESSION_ID, "New Title"))
            .thenReturn(Optional.of(Map.of("id", SESSION_ID.toString(), "title", "New Title")));

        mockMvc.perform(patch("/api/v2/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"New Title"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("New Title"));
    }

    @Test
    void updateSessionReturns404WhenNotFound() throws Exception {
        when(sessionQueryService.updateSession(eq(SESSION_ID), any()))
            .thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/v2/sessions/{sessionId}", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"title":"New Title"}
                    """))
            .andExpect(status().isNotFound());
    }

    // ── Delete session ──

    @Test
    void deleteSessionReturns200WithBodyWhenDeleted() throws Exception {
        when(sessionQueryService.deleteSession(SESSION_ID)).thenReturn(true);

        mockMvc.perform(delete("/api/v2/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("session.deleted"))
            .andExpect(jsonPath("$.id").value(SESSION_ID.toString()))
            .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void deleteSessionReturns404WhenNotFound() throws Exception {
        when(sessionQueryService.deleteSession(SESSION_ID)).thenReturn(false);

        mockMvc.perform(delete("/api/v2/sessions/{sessionId}", SESSION_ID))
            .andExpect(status().isNotFound());
    }

    // ── Get session messages ──

    @Test
    void getSessionMessagesReturns200WithMessages() throws Exception {
        MessageDto msg = new MessageDto("m1", SESSION_ID.toString(), "user", "hello", null, null, 0, "2024-01-01T00:00:00Z");
        MessageListDto dto = new MessageListDto("list", SESSION_ID.toString(), List.of(msg), 100, 0);
        when(sessionQueryService.getSessionMessages(SESSION_ID, 100, 0))
            .thenReturn(Optional.of(dto));

        mockMvc.perform(get("/api/v2/sessions/{sessionId}/messages", SESSION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("list"))
            .andExpect(jsonPath("$.data[0].content").value("hello"));
    }

    @Test
    void getSessionMessagesWithPagination() throws Exception {
        MessageListDto dto = new MessageListDto("list", SESSION_ID.toString(), List.of(), 10, 5);
        when(sessionQueryService.getSessionMessages(SESSION_ID, 10, 5))
            .thenReturn(Optional.of(dto));

        mockMvc.perform(get("/api/v2/sessions/{sessionId}/messages", SESSION_ID)
                .param("limit", "10")
                .param("offset", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.limit").value(10))
            .andExpect(jsonPath("$.offset").value(5));
    }

    @Test
    void getSessionMessagesReturns404WhenSessionNotFound() throws Exception {
        when(sessionQueryService.getSessionMessages(eq(SESSION_ID), anyInt(), anyInt()))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v2/sessions/{sessionId}/messages", SESSION_ID))
            .andExpect(status().isNotFound());
    }

    // ── Session-scoped chat (synchronous) ──

    @Test
    void sessionChatReturnsResponseWhenSessionExists() throws Exception {
        when(sessionQueryService.sessionExists(SESSION_ID)).thenReturn(true);
        ChatResponseDto chatResponse = new ChatResponseDto(SESSION_ID, "hello back", List.of(), true);
        when(agentRuntimeService.runTurn(any())).thenReturn(chatResponse);

        mockMvc.perform(post("/api/v2/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"message":"hello","timeoutMs":5000}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("hello back"))
            .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void sessionChatReturns404WhenSessionDoesNotExist() throws Exception {
        when(sessionQueryService.sessionExists(SESSION_ID)).thenReturn(false);

        mockMvc.perform(post("/api/v2/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"message":"hello","timeoutMs":5000}
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void sessionChatWithBlankMessageStillProcessesWhenSessionExists() throws Exception {
        // SessionChatRequest.message has no @NotBlank — blank message is forwarded to the runtime.
        when(sessionQueryService.sessionExists(SESSION_ID)).thenReturn(true);
        ChatResponseDto chatResponse = new ChatResponseDto(SESSION_ID, "", List.of(), true);
        when(agentRuntimeService.runTurn(any())).thenReturn(chatResponse);

        mockMvc.perform(post("/api/v2/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"message":"","timeoutMs":5000}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.completed").value(true));
    }

    @Test
    void sessionChatWithNullMessageReturns404WhenSessionNotFound() throws Exception {
        // SessionChatRequest.message has no @NotBlank — null message is accepted by
        // deserialization and forwarded. sessionExists returns false → 404.
        mockMvc.perform(post("/api/v2/sessions/{sessionId}/chat", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"timeoutMs":5000}
                    """))
            .andExpect(status().isNotFound());
    }

    // ── Session-scoped chat (streaming) ──

    @Test
    void sessionChatStreamReturnsSseWhenSessionExists() throws Exception {
        when(sessionQueryService.sessionExists(SESSION_ID)).thenReturn(true);
        SseEmitter emitter = new SseEmitter();
        when(streamingService.streamTurn(any())).thenReturn(emitter);

        mockMvc.perform(post("/api/v2/sessions/{sessionId}/chat/stream", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"message":"hello","timeoutMs":5000}
                    """))
            .andExpect(status().isOk());
    }

    @Test
    void sessionChatStreamReturns404WhenSessionDoesNotExist() throws Exception {
        when(sessionQueryService.sessionExists(SESSION_ID)).thenReturn(false);

        mockMvc.perform(post("/api/v2/sessions/{sessionId}/chat/stream", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"message":"hello","timeoutMs":5000}
                    """))
            .andExpect(status().isNotFound());
    }

    @Test
    void sessionChatStreamWithBlankMessageStillProcessesWhenSessionExists() throws Exception {
        // SessionChatRequest.message has no @NotBlank — blank message is forwarded.
        when(sessionQueryService.sessionExists(SESSION_ID)).thenReturn(true);
        SseEmitter emitter = new SseEmitter();
        when(streamingService.streamTurn(any())).thenReturn(emitter);

        mockMvc.perform(post("/api/v2/sessions/{sessionId}/chat/stream", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .content("""
                    {"message":"","timeoutMs":5000}
                    """))
            .andExpect(status().isOk());
    }
}
