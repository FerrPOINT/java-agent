package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.AgentStreamingService;
import com.azhukov.agent.service.CheckpointManager;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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

    @Mock
    private AgentRuntimeService agentRuntimeService;

    @Mock
    private AgentStreamingService streamingService;

    @Mock
    private MemoryProvider memoryProvider;

    @Mock
    private SkillManager skillManager;

    @Mock
    private CheckpointManager checkpointManager;

    @Mock
    private com.azhukov.agent.service.tts.TtsService ttsService;

    @Mock
    private com.azhukov.agent.service.transcription.TranscriptionService transcriptionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        AgentController controller = new AgentController(agentRuntimeService, streamingService,
            memoryProvider, skillManager, checkpointManager, ttsService, transcriptionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void chatReturnsChatResponseDtoJson() throws Exception {
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
    void sessionsListsSessions() throws Exception {
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
    void invalidRequestReturns400() throws Exception {
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
}
