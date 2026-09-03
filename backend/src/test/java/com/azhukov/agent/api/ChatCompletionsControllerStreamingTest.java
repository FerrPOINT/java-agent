package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.security.DefaultRedactor;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.OpenAiIdempotencyCache;
import com.azhukov.agent.service.OpenAiSessionService;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ChatCompletionsControllerStreamingTest {

    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";
    private static final String MODEL = "gpt-test";
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private OpenAiSessionContext sessionContext;
    private ApiRunAdmissionService runAdmissionService;

    @Mock
    private AgentRuntime agentRuntime;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private ModelClient modelClient;

    @Mock
    private OpenAiSessionService openAiSessionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        OpenAiMapper openAiMapper = Mappers.getMapper(OpenAiMapper.class);
        AgentProperties properties = new AgentProperties();
        runAdmissionService = new ApiRunAdmissionService(properties);

        ChatCompletionsController controller = new ChatCompletionsController(
            agentRuntime,
            toolRegistry,
            promptBuilder,
            modelClient,
            objectMapper,
            openAiMapper,
            properties,
            openAiSessionService,
            new OpenAiIdempotencyCache(),
            new DefaultRedactor(properties),
            runAdmissionService
        );

        mockMvc = standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        lenient().when(promptBuilder.buildSystemMessage(any()))
            .thenReturn(Message.system(SYSTEM_PROMPT));
        lenient().when(promptBuilder.buildSystemMessage(any(), anyString()))
            .thenReturn(Message.system(SYSTEM_PROMPT));
        lenient().when(toolRegistry.getDefinitions())
            .thenReturn(List.of());
        lenient().when(toolRegistry.getDefinitions(Set.of("hermes-api-server")))
            .thenReturn(List.of());
        sessionContext = new OpenAiSessionContext(
            new Session(SESSION_ID, "user-1", "OpenAI", "openai-compatible", MODEL, null, Map.of(), null),
            false,
            null
        );
        lenient().when(openAiSessionService.resolveChatCompletions(any(), any(), anyString(), anyString()))
            .thenReturn(sessionContext);
        lenient().when(openAiSessionService.historyFor(any()))
            .thenReturn(List.of());
    }

    @Test
    @DisplayName("SSE emitter sends data chunks for each token")
    void sseEmitterSendsDataChunks() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("chunk1chunk2chunk3"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Tell me a story"}],
              "stream": true
            }
            """;

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"));

        String response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("event:");
        assertThat(response).doesNotContain("id:");

        List<String> payloads = dataPayloads(response);

        // Role chunk + agent-result chunk + finish chunk + OpenAI-compatible [DONE] marker.
        assertThat(payloads).hasSizeGreaterThanOrEqualTo(4);
        assertThat(payloads.get(payloads.size() - 1)).isEqualTo("[DONE]");

        List<JsonNode> chunks = payloads.stream()
            .filter(payload -> !"[DONE]".equals(payload))
            .map(this::parseJson)
            .toList();

        assertThat(chunks.get(0).get("choices").get(0).get("delta").get("role").asText()).isEqualTo("assistant");
        assertThat(chunks.get(0).get("choices").get(0).get("delta").has("content")).isFalse();
        assertThat(chunks.get(1).get("choices").get(0).get("delta").get("content").asText())
            .isEqualTo("chunk1chunk2chunk3");
        JsonNode finishDelta = chunks.get(chunks.size() - 1).get("choices").get(0).get("delta");
        assertThat(finishDelta.size()).isZero();
        verify(agentRuntime).run(anyList(), anyList(), any(ModelRequestOptions.class));
        verify(modelClient, never()).stream(anyList(), anyList(), any(ModelRequestOptions.class), any(StreamingResponseHandler.class));
    }

    @Test
    @DisplayName("SSE emitter accepts bool-ish stream string")
    void sseEmitterAcceptsBoolishStreamStringLikeHermes() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("hello"));

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Hi"}],
                      "stream": "on"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"));

        assertThat(dataPayloads(result.getResponse().getContentAsString()))
            .contains("[DONE]");
        verify(agentRuntime).run(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    @DisplayName("SSE emitter sends [DONE] at end via finish event with stop reason")
    void sseEmitterSendsDoneAtEnd() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("hello"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Hi"}],
              "stream": true
            }
            """;

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk());

        String response = result.getResponse().getContentAsString();

        // The penultimate payload is the JSON finish chunk; the last one is [DONE].
        List<String> payloads = dataPayloads(response);

        assertThat(payloads).hasSizeGreaterThanOrEqualTo(4);
        assertThat(payloads.get(payloads.size() - 1)).isEqualTo("[DONE]");

        JsonNode lastChunk = parseJson(payloads.get(payloads.size() - 2));
        assertThat(lastChunk.get("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(lastChunk.get("choices").get(0).get("finish_reason").asText()).isEqualTo("stop");
        assertThat(lastChunk.get("choices").get(0).get("delta").size()).isZero();
    }

    @Test
    @DisplayName("SSE finish chunk reports error when agent returns failed turn")
    void sseFinishChunkReportsErrorForFailedTurn() throws Exception {
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(TurnResult.error("provider outage"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Hi"}],
              "stream": true
            }
            """;

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk());

        List<String> payloads = dataPayloads(result.getResponse().getContentAsString());
        assertThat(payloads.get(payloads.size() - 1)).isEqualTo("[DONE]");

        JsonNode finishChunk = parseJson(payloads.get(payloads.size() - 2));
        assertThat(finishChunk.get("choices").get(0).get("finish_reason").asText()).isEqualTo("error");
        verify(agentRuntime).runMessages(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    @DisplayName("SSE emitter handles errors by sending error event")
    void sseEmitterHandlesErrors() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenThrow(new RuntimeException("streaming failed"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Cause an error"}],
              "stream": true
            }
            """;

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        // The async dispatch should complete (possibly with error)
        mockMvc.perform(asyncDispatch(result));

        String response = result.getResponse().getContentAsString();

        // Verify there's an error event
        List<String> dataLines = response.lines()
            .filter(line -> line.startsWith("data:"))
            .toList();

        assertThat(dataLines).isNotEmpty();

        // The error event should be present — it's an OpenAiStreamError with type "streaming_error"
        JsonNode errorChunk = parseJson(dataLines.get(dataLines.size() - 1).substring(5).trim());
        // The last data line could be the error event or the error + the partial token
        // The error event has "type" field = "streaming_error"
        boolean hasErrorEvent = dataLines.stream()
            .map(line -> parseJson(line.substring(5).trim()))
            .anyMatch(node -> node.has("type") && "streaming_error".equals(node.get("type").asText()));
        assertThat(hasErrorEvent).isTrue();
    }

    @Test
    @DisplayName("SSE emitter streams agent runtime result instead of raw model tool calls")
    void sseEmitterStreamsAgentRuntimeResultInsteadOfRawModelToolCalls() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("search result"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Search for something"}],
              "stream": true
            }
            """;

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk());

        String response = result.getResponse().getContentAsString();

        List<String> payloads = dataPayloads(response);

        boolean hasToolCallChunk = payloads.stream()
            .filter(payload -> !"[DONE]".equals(payload))
            .map(this::parseJson)
            .anyMatch(node -> {
                JsonNode delta = node.path("choices").get(0).path("delta");
                JsonNode tc = delta.path("tool_calls");
                return !tc.isMissingNode() && !tc.isNull() && tc.size() > 0;
            });

        assertThat(hasToolCallChunk).isFalse();
        assertThat(payloads.stream()
            .filter(payload -> !"[DONE]".equals(payload))
            .map(this::parseJson)
            .map(JsonNode::toString)
            .toList()).anyMatch(payload -> payload.contains("search result"));
        verify(agentRuntime).run(anyList(), anyList(), any(ModelRequestOptions.class));
        verify(modelClient, never()).stream(anyList(), anyList(), any(ModelRequestOptions.class), any(StreamingResponseHandler.class));
    }

    @Test
    @DisplayName("SSE emitter mirrors Hermes tool progress events from generated transcript")
    void sseEmitterEmitsHermesToolProgressFromGeneratedTranscript() throws Exception {
        ToolCall toolCall = new ToolCall("call_terminal_1|response_item_1", "terminal", "{\"command\":\"echo ready\"}");
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(List.of(
                Message.system(SYSTEM_PROMPT),
                Message.user("Run a check"),
                Message.assistantToolCalls(List.of(toolCall), 1),
                Message.toolResult("response_item_1", "ready", 1),
                Message.assistant("done.", 2)
            ), true, null));

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Run a check"}],
                      "stream": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk());

        String response = result.getResponse().getContentAsString();
        List<JsonNode> progress = namedEventPayloads(response, "hermes.tool.progress").stream()
            .map(this::parseJson)
            .toList();

        assertThat(progress).hasSize(2);
        assertThat(progress.get(0).get("status").asText()).isEqualTo("running");
        assertThat(progress.get(0).get("toolCallId").asText()).isEqualTo("call_terminal_1|response_item_1");
        assertThat(progress.get(0).get("tool").asText()).isEqualTo("terminal");
        assertThat(progress.get(0).get("label").asText()).isEqualTo("echo ready");
        assertThat(progress.get(0).has("emoji")).isTrue();
        assertThat(progress.get(1).get("status").asText()).isEqualTo("completed");
        assertThat(progress.get(1).get("toolCallId").asText()).isEqualTo("call_terminal_1|response_item_1");

        List<String> payloads = dataPayloads(response);
        assertThat(payloads.get(payloads.size() - 1)).isEqualTo("[DONE]");
        assertThat(payloads.stream()
            .filter(payload -> !"[DONE]".equals(payload))
            .map(this::parseJson)
            .map(JsonNode::toString)
            .toList()).anyMatch(payload -> payload.contains("done."));
        assertThat(payloads.stream()
            .filter(payload -> !"[DONE]".equals(payload))
            .map(this::parseJson)
            .anyMatch(node -> !node.path("choices").isMissingNode()
                && !node.path("choices").isEmpty()
                && node.path("choices").get(0).path("delta").has("tool_calls"))).isFalse();
    }

    @Test
    @DisplayName("SSE emitter suppresses internal and orphan tool progress events")
    void sseEmitterSuppressesInternalAndOrphanToolProgressEvents() throws Exception {
        ToolCall internal = new ToolCall("call_internal", "_thinking", "{\"note\":\"hidden\"}");
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(List.of(
                Message.system(SYSTEM_PROMPT),
                Message.user("Think quietly"),
                Message.assistantToolCalls(List.of(internal), 1),
                Message.toolResult("call_internal", "hidden", 1),
                Message.toolResult("call_orphan", "orphan", 1),
                Message.assistant("done.", 2)
            ), true, null));

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Think quietly"}],
                      "stream": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk());

        String response = result.getResponse().getContentAsString();
        assertThat(namedEventPayloads(response, "hermes.tool.progress")).isEmpty();
        assertThat(response).doesNotContain("call_internal", "call_orphan");
    }

    @Test
    @DisplayName("SSE emitter handles exception in stream setup")
    void sseEmitterHandlesSetupException() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenThrow(new RuntimeException("setup failed"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Trigger setup error"}],
              "stream": true
            }
            """;

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        // The async dispatch should complete (with error from setup exception)
        mockMvc.perform(asyncDispatch(result));

        String response = result.getResponse().getContentAsString();

        List<String> dataLines = response.lines()
            .filter(line -> line.startsWith("data:"))
            .toList();

        // Should have an error event
        boolean hasErrorEvent = dataLines.stream()
            .map(line -> parseJson(line.substring(5).trim()))
            .anyMatch(node -> node.has("type") && "streaming_error".equals(node.get("type").asText()));
        assertThat(hasErrorEvent).isTrue();
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse SSE data line: " + raw, e);
        }
    }

    private List<String> dataPayloads(String response) {
        return response.lines()
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim())
            .toList();
    }

    private List<String> namedEventPayloads(String response, String eventName) {
        List<String> payloads = new java.util.ArrayList<>();
        String currentEvent = null;
        for (String line : response.lines().toList()) {
            if (line.startsWith("event:")) {
                currentEvent = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (eventName.equals(currentEvent)) {
                    payloads.add(line.substring(5).trim());
                }
                currentEvent = null;
            } else if (line.isBlank()) {
                currentEvent = null;
            }
        }
        return payloads;
    }
}
