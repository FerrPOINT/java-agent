package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.OpenAiChatRequest;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatCompletionsControllerTest {

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
        OpenAiMapper openAiMapper = Mappers.getMapper(OpenAiMapper.class);

        ChatCompletionsController controller = new ChatCompletionsController(
            agentRuntime,
            toolRegistry,
            promptBuilder,
            modelClient,
            objectMapper,
            openAiMapper
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        lenient().when(promptBuilder.buildSystemMessage(any()))
            .thenReturn(Message.system(SYSTEM_PROMPT));
        lenient().when(toolRegistry.getDefinitions())
            .thenReturn(List.of());
    }

    private OpenAiChatRequest.OpenAiFunction function(String name, String description, Map<String, Object> params) {
        return new OpenAiChatRequest.OpenAiFunction(name, description, params);
    }

    private Map<String, Object> objectSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "search query"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Test
    void nonStreamingRequestReturnsOpenAiCompatibleResponse() throws Exception {
        when(agentRuntime.run(anyList(), anyList()))
            .thenReturn(ChatResponse.text("Hello from the test agent"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [
                {"role": "system", "content": "Be concise."},
                {"role": "user", "content": "Hi!"}
              ],
              "temperature": 0.7,
              "max_tokens": 128
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.object").value("chat.completion"))
            .andExpect(jsonPath("$.model").value(MODEL))
            .andExpect(jsonPath("$.choices[0].index").value(0))
            .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
            .andExpect(jsonPath("$.choices[0].message.content").value("Hello from the test agent"))
            .andExpect(jsonPath("$.choices[0].finishReason").value("stop"))
            .andExpect(jsonPath("$.usage.promptTokens").exists())
            .andExpect(jsonPath("$.usage.completionTokens").exists())
            .andExpect(jsonPath("$.usage.totalTokens").exists())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.created").isNumber());
    }

    @Test
    void streamingRequestEmitsSseChunks() throws Exception {
        doAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            List<ToolDefinition> tools = invocation.getArgument(1);
            StreamingResponseHandler handler = invocation.getArgument(2);

            assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
            assertThat(tools).isNotNull();

            handler.onToken("Hello");
            handler.onToken(" ");
            handler.onToken("world");
            handler.onComplete();
            return null;
        }).when(modelClient).stream(anyList(), anyList(), any(StreamingResponseHandler.class));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Say hello"}],
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
        assertThat(dataLines).hasSizeGreaterThanOrEqualTo(4);

        List<JsonNode> chunks = dataLines.stream()
            .map(line -> parseJson(line.substring(5).trim()))
            .toList();

        List<String> tokens = chunks.stream()
            .filter(node -> node.has("choices") && node.get("choices").get(0).has("delta"))
            .map(node -> {
                JsonNode contentNode = node.get("choices").get(0).get("delta").path("content");
                return contentNode.isNull() ? null : contentNode.asText();
            })
            .toList();
        assertThat(tokens).containsExactly("Hello", " ", "world", null);

        JsonNode finishChunk = chunks.get(chunks.size() - 1);
        assertThat(finishChunk.get("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(finishChunk.get("choices").get(0).get("finishReason").asText()).isEqualTo("stop");
        assertThat(finishChunk.get("choices").get(0).get("delta").path("content").isNull()).isTrue();
    }

    @Test
    void requestWithToolsIncludesToolDefinitions() throws Exception {
        ArgumentCaptor<List<ToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        when(agentRuntime.run(anyList(), toolsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Search the web"}],
              "tools": [
                {
                  "type": "function",
                  "function": {
                    "name": "web_search",
                    "description": "Searches the web",
                    "parameters": {
                      "type": "object",
                      "properties": {
                        "query": {"type": "string", "description": "search query"}
                      },
                      "required": ["query"]
                    }
                  }
                }
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        List<ToolDefinition> tools = toolsCaptor.getValue();
        assertThat(tools).hasSize(1);
        ToolDefinition tool = tools.get(0);
        assertThat(tool.name()).isEqualTo("web_search");
        assertThat(tool.description()).isEqualTo("Searches the web");
        assertThat(tool.parameters()).containsEntry("type", "object");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.parameters().get("properties");
        assertThat(properties).containsKey("query");
        assertThat(tool.parameters().get("required")).isEqualTo(List.of("query"));
    }

    @Test
    void toolResponseReturnsToolCallsInOpenAiFormat() throws Exception {
        ToolCall toolCall = new ToolCall("call-abc", "web_search", "{\"query\":\"Java 25\"}");
        when(agentRuntime.run(anyList(), anyList()))
            .thenReturn(ChatResponse.toolCalls(List.of(toolCall)));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Search"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
            .andExpect(jsonPath("$.choices[0].message.content").doesNotExist())
            .andExpect(jsonPath("$.choices[0].message.toolCalls[0].id").value("call-abc"))
            .andExpect(jsonPath("$.choices[0].message.toolCalls[0].type").value("function"))
            .andExpect(jsonPath("$.choices[0].message.toolCalls[0].function.name").value("web_search"))
            .andExpect(jsonPath("$.choices[0].message.toolCalls[0].function.arguments")
                .value("{\"query\":\"Java 25\"}"));
    }

    @Test
    void errorDuringCompletionReturns500() throws Exception {
        when(agentRuntime.run(anyList(), anyList()))
            .thenThrow(new RuntimeException("model service unavailable"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Hello"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.type").value("internal"))
            .andExpect(jsonPath("$.error").value("Internal error: model service unavailable"));
    }

    @Test
    void whenNoToolsProvidedFallsBackToRegistryDefinitions() throws Exception {
        ToolDefinition registryTool = new ToolDefinition(
            "read_file",
            "Reads a file",
            objectSchema()
        );
        when(toolRegistry.getDefinitions()).thenReturn(List.of(registryTool));
        when(agentRuntime.run(anyList(), anyList())).thenReturn(ChatResponse.text("fallback ok"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Read a file"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("fallback ok"));

        verify(toolRegistry).getDefinitions();
    }

    @Test
    void requestWithToolRoleMessageIsAccepted() throws Exception {
        when(agentRuntime.run(anyList(), anyList())).thenReturn(ChatResponse.text("ack"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [
                {"role": "user", "content": "Call a tool"},
                {"role": "assistant", "content": "", "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "echo", "arguments": "{}"}}]},
                {"role": "tool", "content": "result", "tool_call_id": "c1"}
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("ack"));
    }

    @Test
    void missingModelReturnsValidationError() throws Exception {
        String requestBody = """
            {
              "messages": [{"role": "user", "content": "Hello"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors.model").exists());
    }

    @Test
    void emptyMessagesReturnsValidationError() throws Exception {
        String requestBody = """
            {
              "model": "gpt-test",
              "messages": []
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.errors.messages").exists());
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse SSE data line: " + raw, e);
        }
    }
}
