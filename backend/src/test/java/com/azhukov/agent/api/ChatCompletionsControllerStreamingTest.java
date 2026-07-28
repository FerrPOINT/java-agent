package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ChatCompletionsControllerStreamingTest {

    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";
    private static final String MODEL = "gpt-test";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private AgentRuntime agentRuntime;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private ModelClient modelClient;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        ChatCompletionsController controller = new ChatCompletionsController(
            agentRuntime,
            toolRegistry,
            promptBuilder,
            modelClient,
            objectMapper
        );

        mockMvc = standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        lenient().when(promptBuilder.buildSystemMessage(any()))
            .thenReturn(Message.system(SYSTEM_PROMPT));
        lenient().when(toolRegistry.getDefinitions())
            .thenReturn(List.of());
    }

    @Test
    @DisplayName("SSE emitter sends data chunks for each token")
    void sseEmitterSendsDataChunks() throws Exception {
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("chunk1");
            handler.onToken("chunk2");
            handler.onToken("chunk3");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(anyList(), anyList(), any(StreamingResponseHandler.class));

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
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"));

        String response = result.getResponse().getContentAsString();
        assertThat(response).contains("event:message");

        List<String> dataLines = response.lines()
            .filter(line -> line.startsWith("data:"))
            .toList();

        // 3 token chunks + 1 finish chunk = 4 data lines
        assertThat(dataLines).hasSizeGreaterThanOrEqualTo(4);

        List<JsonNode> chunks = dataLines.stream()
            .map(line -> parseJson(line.substring(5).trim()))
            .toList();

        // Verify token chunks
        List<String> tokens = chunks.stream()
            .filter(node -> node.has("choices") && node.get("choices").get(0).has("delta"))
            .map(node -> {
                JsonNode contentNode = node.get("choices").get(0).get("delta").path("content");
                return contentNode.isNull() ? null : contentNode.asText();
            })
            .toList();

        assertThat(tokens).containsExactly("chunk1", "chunk2", "chunk3", null);
    }

    @Test
    @DisplayName("SSE emitter sends [DONE] at end via finish event with stop reason")
    void sseEmitterSendsDoneAtEnd() throws Exception {
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("hello");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(anyList(), anyList(), any(StreamingResponseHandler.class));

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

        // The last chunk should have finishReason = "stop"
        List<String> dataLines = response.lines()
            .filter(line -> line.startsWith("data:"))
            .toList();

        assertThat(dataLines).isNotEmpty();

        JsonNode lastChunk = parseJson(dataLines.get(dataLines.size() - 1).substring(5).trim());
        assertThat(lastChunk.get("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(lastChunk.get("choices").get(0).get("finishReason").asText()).isEqualTo("stop");
        assertThat(lastChunk.get("choices").get(0).get("delta").path("content").isNull()).isTrue();
    }

    @Test
    @DisplayName("SSE emitter handles errors by sending error event")
    void sseEmitterHandlesErrors() throws Exception {
        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("partial");
            handler.onError(new RuntimeException("streaming failed"));
            return null;
        }).when(modelClient).stream(anyList(), anyList(), any(StreamingResponseHandler.class));

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

        // Verify there's at least the partial token chunk
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
    @DisplayName("SSE emitter sends tool call chunks when onToolCalls is invoked")
    void sseEmitterSendsToolCallChunks() throws Exception {
        ToolCall toolCall = new ToolCall("call-1", "search", "{\"query\":\"test\"}");

        doAnswer(invocation -> {
            StreamingResponseHandler handler = invocation.getArgument(2);
            handler.onToken("thinking...");
            handler.onToolCalls(List.of(toolCall));
            handler.onComplete();
            return null;
        }).when(modelClient).stream(anyList(), anyList(), any(StreamingResponseHandler.class));

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

        List<String> dataLines = response.lines()
            .filter(line -> line.startsWith("data:"))
            .toList();

        // Find the chunk with tool_calls
        boolean hasToolCallChunk = dataLines.stream()
            .map(line -> parseJson(line.substring(5).trim()))
            .anyMatch(node -> {
                JsonNode delta = node.path("choices").get(0).path("delta");
                JsonNode tc = delta.path("toolCalls");
                return !tc.isMissingNode() && !tc.isNull() && tc.size() > 0;
            });

        assertThat(hasToolCallChunk).isTrue();
    }

    @Test
    @DisplayName("SSE emitter handles exception in stream setup")
    void sseEmitterHandlesSetupException() throws Exception {
        doAnswer(invocation -> {
            throw new RuntimeException("setup failed");
        }).when(modelClient).stream(anyList(), anyList(), any(StreamingResponseHandler.class));

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
}