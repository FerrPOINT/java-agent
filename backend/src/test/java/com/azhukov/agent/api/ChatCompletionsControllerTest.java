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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatCompletionsControllerTest {

    private static final String SYSTEM_PROMPT = "You are a helpful assistant.";
    private static final String MODEL = "gpt-test";
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private AgentProperties properties;
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
        properties = new AgentProperties();
        properties.getModel().setModelName(MODEL);
        runAdmissionService = new ApiRunAdmissionService(properties);
        OpenAiMapper openAiMapper = Mappers.getMapper(OpenAiMapper.class);

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

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        lenient().when(promptBuilder.buildSystemMessage(any()))
            .thenReturn(Message.system(SYSTEM_PROMPT));
        lenient().when(promptBuilder.buildSystemMessage(any(), anyString()))
            .thenAnswer(invocation -> {
                String override = invocation.getArgument(1);
                return Message.system(override == null || override.isBlank()
                    ? SYSTEM_PROMPT
                    : SYSTEM_PROMPT + "\n\n" + override);
            });
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
        lenient().when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(invocation -> agentRuntime.run(invocation.getArgument(0), invocation.getArgument(1)));
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
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.object").value("chat.completion"))
            .andExpect(jsonPath("$.model").value(MODEL))
            .andExpect(jsonPath("$.choices[0].index").value(0))
            .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
            .andExpect(jsonPath("$.choices[0].message.content").value("Hello from the test agent"))
            .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
            .andExpect(jsonPath("$.usage.prompt_tokens").exists())
            .andExpect(jsonPath("$.usage.completion_tokens").exists())
            .andExpect(jsonPath("$.usage.total_tokens").exists())
            .andExpect(jsonPath("$.id").exists())
            .andExpect(jsonPath("$.created").isNumber());
    }

    @Test
    void nonStreamingRequestReturns429WhenApiRunLimitIsReachedLikeHermes() throws Exception {
        properties.getApi().setMaxConcurrentRuns(1);
        try (ApiRunAdmissionService.Reservation ignored = runAdmissionService.tryAcquire().orElseThrow()) {
            mockMvc.perform(post("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "model": "gpt-test",
                          "messages": [{"role": "user", "content": "Hi"}]
                        }
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.error.message").value("Too many concurrent runs (max 1)"))
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"))
                .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
        }

        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void profilePrefixedChatCompletionsRouteMirrorsHermesMultiplexAlias() throws Exception {
        when(agentRuntime.run(anyList(), anyList()))
            .thenReturn(ChatResponse.text("profile ok"));

        mockMvc.perform(post("/p/work/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Hi"}]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("chat.completion"))
            .andExpect(jsonPath("$.choices[0].message.content").value("profile ok"));
    }

    @Test
    void idempotencyKeyReusesNonStreamingCompletionWithoutRerunningAgent() throws Exception {
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(
                List.of(Message.assistant("cached answer", 1)),
                true,
                null
            ));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "retry me"}]
            }
            """;

        MvcResult first = mockMvc.perform(post("/v1/chat/completions")
                .header("Idempotency-Key", "idem-chat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("cached answer"))
            .andReturn();
        MvcResult second = mockMvc.perform(post("/v1/chat/completions")
                .header("Idempotency-Key", "idem-chat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("cached answer"))
            .andReturn();

        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondBody.get("id").asText()).isEqualTo(firstBody.get("id").asText());
        verify(agentRuntime, times(1)).runMessages(anyList(), anyList(), any(ModelRequestOptions.class));
        verify(openAiSessionService, times(1)).persistTurn(any(), anyList(), any(ChatResponse.class), anyList());
    }

    @Test
    void idempotencyFingerprintIncludesSessionKeyScope() throws Exception {
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(List.of(Message.assistant("first scope", 1)), true, null))
            .thenReturn(new TurnResult(List.of(Message.assistant("second scope", 1)), true, null));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "same body"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .header("Idempotency-Key", "idem-chat-scope")
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "scope-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("first scope"));
        mockMvc.perform(post("/v1/chat/completions")
                .header("Idempotency-Key", "idem-chat-scope")
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "scope-b")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("second scope"));

        verify(agentRuntime, times(2)).runMessages(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void idempotencyFingerprintIncludesToolChoiceLikeHermes() throws Exception {
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(List.of(Message.assistant("auto choice", 1)), true, null))
            .thenReturn(new TurnResult(List.of(Message.assistant("forced choice", 1)), true, null));

        String autoRequest = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "same body"}],
              "tool_choice": "auto"
            }
            """;
        String forcedRequest = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "same body"}],
              "tool_choice": {"type": "function", "function": {"name": "lookup"}}
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .header("Idempotency-Key", "idem-chat-tool-choice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(autoRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("auto choice"));
        mockMvc.perform(post("/v1/chat/completions")
                .header("Idempotency-Key", "idem-chat-tool-choice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(forcedRequest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("forced choice"));

        verify(agentRuntime, times(2)).runMessages(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void nonStreamingHardFailureReturnsOpenAiErrorEnvelope() throws Exception {
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(TurnResult.error("provider outage"));

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Hi!"}]
                    }
                    """))
            .andExpect(status().isBadGateway())
            .andExpect(header().string("X-Hermes-Completed", "false"))
            .andExpect(header().string("X-Hermes-Partial", "true"))
            .andExpect(jsonPath("$.error.type").value("server_error"))
            .andExpect(jsonPath("$.error.code").value("agent_incomplete"))
            .andExpect(jsonPath("$.error.message").value("provider outage"))
            .andExpect(jsonPath("$.error.hermes.completed").value(false))
            .andExpect(jsonPath("$.error.hermes.failed").value(true));
    }

    @Test
    void nonStreamingHardFailureRedactsSecretLikeHermes() throws Exception {
        String rawSecret = "sk-api-server-leak-1234567890";
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(TurnResult.error("provider auth failed OPENAI_API_KEY=" + rawSecret));

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Hi!"}]
                    }
                    """))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error.type").value("server_error"))
            .andExpect(jsonPath("$.error.code").value("agent_incomplete"))
            .andExpect(jsonPath("$.error.message").value("provider auth failed OPENAI_API_KEY=[REDACTED]"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        String header = result.getResponse().getHeader("X-Hermes-Error");
        assertThat(body).doesNotContain(rawSecret);
        assertThat(header).doesNotContain(rawSecret);
        assertThat(body).contains("OPENAI_API_KEY=");
    }

    @Test
    void nonStreamingPartialTruncationUsesLengthFinishReason() throws Exception {
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(
                List.of(Message.assistant("partial answer", 1)),
                false,
                "Response truncated due to output length limit"
            ));

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Hi!"}]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Hermes-Completed", "false"))
            .andExpect(header().string("X-Hermes-Partial", "true"))
            .andExpect(jsonPath("$.choices[0].message.content").value("partial answer"))
            .andExpect(jsonPath("$.choices[0].finish_reason").value("length"));
    }

    @Test
    void nonStreamingRequestIgnoresBareModelByDefaultAndPassesMaxTokensToRuntimeOptions() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-5-mini",
              "messages": [{"role": "user", "content": "Hi!"}],
              "max_tokens": 256
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("ok"));

        assertThat(optionsCaptor.getValue().modelName()).isNull();
        assertThat(optionsCaptor.getValue().maxCompletionTokens()).isEqualTo(256);
    }

    @Test
    void nonStreamingRequestRoutesConfiguredModelAliasToRuntimeModel() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/fast-model");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("fast-agent", route);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "fast-agent",
              "messages": [{"role": "user", "content": "Hi!"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("fast-agent"))
            .andExpect(jsonPath("$.choices[0].message.content").value("ok"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo("openrouter/fast-model");
        assertThat(options.provider()).isEqualTo("openrouter");
        assertThat(options.baseUrl()).isEqualTo("https://openrouter.example/v1");
        assertThat(options.apiKey()).isEqualTo("sk-route-secret");
        assertThat(options.toString()).doesNotContain("sk-route-secret");
    }

    @Test
    void nonStreamingRequestRejectsMismatchedRouteProviderLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/fast-model");
        route.setProvider("openrouter");
        properties.getApi().getModelRoutes().put("fast-agent", route);

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "fast-agent",
                      "provider": "minimax",
                      "messages": [{"role": "user", "content": "Hi!"}]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value(
                "Model route 'fast-agent' is pinned to provider 'openrouter'. "
                    + "Remove 'provider' or use 'openrouter'."));
    }

    @Test
    void directModelRequestsOptInPassesBareModelToRuntime() throws Exception {
        properties.getApi().setDirectModelRequests(true);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-5-mini",
              "messages": [{"role": "user", "content": "Hi!"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        assertThat(optionsCaptor.getValue().modelName()).isEqualTo("gpt-5-mini");
    }

    @Test
    void providerPrefixedModelDoesNotOverrideOpenAiCompatibleRuntimeByDefaultLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/fast-model");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("fast-agent", route);

        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "openrouter::fast-agent",
                      "messages": [{"role": "user", "content": "Hi!"}]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("openrouter::fast-agent"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isNull();
        assertThat(options.provider()).isNull();
        assertThat(options.baseUrl()).isNull();
        assertThat(options.apiKey()).isNull();
    }

    @Test
    void directModelRequestsOptInPassesProviderPrefixedModelRawLikeHermes() throws Exception {
        properties.getApi().setDirectModelRequests(true);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "openrouter::MiniMax-M3",
                      "messages": [{"role": "user", "content": "Hi!"}]
                    }
                    """))
            .andExpect(status().isOk());

        assertThat(optionsCaptor.getValue().modelName()).isEqualTo("openrouter::MiniMax-M3");
        assertThat(optionsCaptor.getValue().provider()).isNull();
    }

    @Test
    void explicitProviderPassesBareModelWithoutDirectModelOptIn() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "MiniMax-M3",
              "provider": "minimax",
              "messages": [{"role": "user", "content": "Hi!"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        assertThat(optionsCaptor.getValue().modelName()).isEqualTo("MiniMax-M3");
        assertThat(optionsCaptor.getValue().provider()).isEqualTo("minimax");
    }

    @Test
    void modelOptionsPopulateRuntimeRequestOptions() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "hermes-agent",
              "messages": [{"role": "user", "content": "Hi!"}],
              "model_options": {
                "reasoning": {"enabled": true, "effort": "xhigh"},
                "fast": "yes",
                "voice": true,
                "personality": "concise",
                "subgoal": "ship parity",
                "max_tokens": "321"
              }
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("ok"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo(MODEL);
        assertThat(options.reasoningEffort()).isEqualTo("xhigh");
        assertThat(options.fastMode()).isTrue();
        assertThat(options.voiceMode()).isTrue();
        assertThat(options.personality()).isEqualTo("concise");
        assertThat(options.subgoal()).isEqualTo("ship parity");
        assertThat(options.maxCompletionTokens()).isEqualTo(321);
    }

    @Test
    void nonObjectModelOptionsAreIgnoredLikeHermes() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "hermes-agent",
                      "messages": [{"role": "user", "content": "Hi!"}],
                      "model_options": "ignored"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("ok"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo(MODEL);
        assertThat(options.reasoningEffort()).isNull();
        assertThat(options.maxCompletionTokens()).isNull();
    }

    @Test
    void nonStringModelAndProviderAreIgnoredLikeHermes() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": {"id": "gpt-5"},
                      "provider": ["minimax"],
                      "messages": [{"role": "user", "content": "Hi!"}]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("hermes-agent"))
            .andExpect(jsonPath("$.choices[0].message.content").value("ok"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo(MODEL);
        assertThat(options.provider()).isNull();
    }

    @Test
    void advertisedApiAliasResolvesToConfiguredRuntimeModel() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "hermes-agent",
              "messages": [{"role": "user", "content": "Hi!"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("hermes-agent"));

        assertThat(optionsCaptor.getValue().modelName()).isEqualTo(MODEL);
    }

    @Test
    void streamingRequestPassesModelAndMaxTokensToModelClientOptions() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-5-mini",
              "messages": [{"role": "user", "content": "Hi!"}],
              "max_tokens": 512,
              "stream": true
            }
            """;

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk());

        verify(agentRuntime).run(anyList(), anyList(), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().modelName()).isNull();
        assertThat(optionsCaptor.getValue().maxCompletionTokens()).isEqualTo(512);
        verify(modelClient, never()).stream(anyList(), anyList(), any(ModelRequestOptions.class), any(StreamingResponseHandler.class));
    }

    @Test
    void streamingRequestEmitsSseChunks() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("Hello world"));

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
        assertThat(response).doesNotContain("event:");
        assertThat(response).doesNotContain("id:");

        List<String> payloads = dataPayloads(response);
        assertThat(payloads).hasSizeGreaterThanOrEqualTo(4);
        assertThat(payloads.get(payloads.size() - 1)).isEqualTo("[DONE]");

        List<JsonNode> chunks = payloads.stream()
            .filter(payload -> !"[DONE]".equals(payload))
            .map(this::parseJson)
            .toList();

        assertThat(chunks.get(0).get("choices").get(0).get("delta").get("role").asText()).isEqualTo("assistant");
        assertThat(chunks.get(0).get("choices").get(0).get("delta").has("content")).isFalse();
        assertThat(chunks.get(1).get("choices").get(0).get("delta").get("content").asText()).isEqualTo("Hello world");

        JsonNode finishChunk = chunks.get(chunks.size() - 1);
        assertThat(finishChunk.get("object").asText()).isEqualTo("chat.completion.chunk");
        assertThat(finishChunk.get("choices").get(0).get("finish_reason").asText()).isEqualTo("stop");
        assertThat(finishChunk.get("choices").get(0).get("delta").size()).isZero();
        verify(agentRuntime).run(anyList(), anyList(), any(ModelRequestOptions.class));
        verify(modelClient, never()).stream(anyList(), anyList(), any(ModelRequestOptions.class), any(StreamingResponseHandler.class));
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
            .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
            .andExpect(jsonPath("$.choices[0].message.tool_calls[0].id").value("call-abc"))
            .andExpect(jsonPath("$.choices[0].message.tool_calls[0].type").value("function"))
            .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("web_search"))
            .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.arguments")
                .value("{\"query\":\"Java 25\"}"));
    }

    @Test
    void continuationHeaderLoadsPersistedHistoryAndOnlyAppendsCurrentTail() throws Exception {
        OpenAiSessionContext continuation = new OpenAiSessionContext(
            new Session(SESSION_ID, "user-1", "OpenAI", "openai-compatible", MODEL, null, Map.of(), null),
            true,
            null
        );
        when(openAiSessionService.resolveChatCompletions(eq(SESSION_ID.toString()), any(), eq(MODEL), anyString()))
            .thenReturn(continuation);
        when(openAiSessionService.historyFor(continuation))
            .thenReturn(List.of(Message.user("Earlier"), Message.assistant("Earlier answer", 0)));

        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Message>> persistedIncomingCaptor = ArgumentCaptor.forClass(List.class);
        ChatResponse response = ChatResponse.text("continued");
        when(agentRuntime.run(messagesCaptor.capture(), anyList())).thenReturn(response);

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [
                {"role": "user", "content": "Earlier"},
                {"role": "assistant", "content": "Earlier answer"},
                {"role": "user", "content": "Now"}
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .header(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(jsonPath("$.choices[0].message.content").value("continued"));

        List<Message> messages = messagesCaptor.getValue();
        assertThat(messages).extracting(Message::content)
            .containsExactly(SYSTEM_PROMPT, "Earlier", "Earlier answer", "Now");
        verify(openAiSessionService).persistTurn(eq(continuation), persistedIncomingCaptor.capture(), eq(response), anyList());
        assertThat(persistedIncomingCaptor.getValue()).hasSize(1);
        assertThat(persistedIncomingCaptor.getValue().get(0).content()).isEqualTo("Now");
    }

    @Test
    void continuationHeaderEchoesExternalStringSessionIdLikeHermes() throws Exception {
        String externalSessionId = "cron_job42_20260801_090000";
        OpenAiSessionContext continuation = new OpenAiSessionContext(
            new Session(SESSION_ID, "user-1", "OpenAI", "openai-compatible", MODEL, null, Map.of(), null),
            true,
            null,
            externalSessionId
        );
        when(openAiSessionService.resolveChatCompletions(eq(externalSessionId), any(), eq(MODEL), anyString()))
            .thenReturn(continuation);
        when(agentRuntime.run(anyList(), anyList())).thenReturn(ChatResponse.text("continued"));

        mockMvc.perform(post("/v1/chat/completions")
                .header(OpenAiSessionService.SESSION_ID_HEADER, externalSessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Now"}]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, externalSessionId))
            .andExpect(jsonPath("$.choices[0].message.content").value("continued"));
    }

    @Test
    void sessionKeyHeaderIsEchoedWhenAcceptedBySessionService() throws Exception {
        String sessionKey = "agent:main:webui:42";
        OpenAiSessionContext keyed = new OpenAiSessionContext(
            new Session(SESSION_ID, sessionKey, "OpenAI", "openai-compatible", MODEL, null, Map.of(), null),
            false,
            sessionKey
        );
        when(openAiSessionService.resolveChatCompletions(any(), eq(sessionKey), eq(MODEL), anyString()))
            .thenReturn(keyed);
        when(agentRuntime.run(anyList(), anyList())).thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Hi"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .header(OpenAiSessionService.SESSION_KEY_HEADER, sessionKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(header().string(OpenAiSessionService.SESSION_KEY_HEADER, sessionKey));
    }

    @Test
    void requestSystemMessagesBecomePromptOverrideAndAreNotForwardedAsConversationMessages() throws Exception {
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        when(agentRuntime.run(messagesCaptor.capture(), anyList()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [
                {"role": "system", "content": "Be precise."},
                {"role": "developer", "content": "Use terse output."},
                {"role": "user", "content": "Hi!"}
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        List<Message> messages = messagesCaptor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(com.azhukov.agent.core.model.Role.SYSTEM);
        assertThat(messages.get(0).content()).contains("Be precise.");
        assertThat(messages.get(0).content()).contains("Use terse output.");
        assertThat(messages.subList(1, messages.size()))
            .extracting(Message::role)
            .doesNotContain(com.azhukov.agent.core.model.Role.SYSTEM, com.azhukov.agent.core.model.Role.DEVELOPER);
        assertThat(messages.get(1).content()).isEqualTo("Hi!");
    }

    @Test
    void requestTextContentPartsAreFlattenedBeforeRuntime() throws Exception {
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        when(agentRuntime.run(messagesCaptor.capture(), anyList()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "Hello"},
                    {"type": "input_text", "text": "from"},
                    {"type": "output_text", "text": "OpenAI parts"}
                  ]
                }
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        List<Message> messages = messagesCaptor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content()).isEqualTo("Hello\nfrom\nOpenAI parts");
    }

    @Test
    void imageContentPartIsAcceptedAndMarksImageCount() throws Exception {
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        when(agentRuntime.run(messagesCaptor.capture(), anyList()))
            .thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "image_url", "image_url": {"url": "https://example.com/a.png"}}
                  ]
                }
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.choices[0].message.content").value("ok"));

        List<Message> messages = messagesCaptor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content()).isEqualTo("[image_url: https://example.com/a.png]");
        assertThat(messages.get(1).imageCount()).isEqualTo(1);
    }

    @Test
    void invalidImageContentPartReturnsHermesErrorShape() throws Exception {
        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "image_url", "image_url": {"url": ""}}
                  ]
                }
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Image parts must include a non-empty image URL."))
            .andExpect(jsonPath("$.error.param").value("messages[0].content"))
            .andExpect(jsonPath("$.error.code").value("invalid_image_url"));

        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void invalidMessageContentIsValidatedBeforeSessionKeyAuthLikeHermes() throws Exception {
        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "image_url", "image_url": {"url": ""}}
                  ]
                }
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "desktop-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Image parts must include a non-empty image URL."))
            .andExpect(jsonPath("$.error.param").value("messages[0].content"))
            .andExpect(jsonPath("$.error.code").value("invalid_image_url"));

        verify(openAiSessionService, never()).resolveChatCompletions(any(), any(), anyString(), anyString());
        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void invalidJsonReturnsOpenAiErrorEnvelope() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Invalid JSON in request body"));
    }

    @Test
    void nonListMessagesReturnHermesMissingMessagesErrorBeforeRuntime() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": "hello"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Missing or invalid 'messages' field"));

        verify(openAiSessionService, never()).resolveChatCompletions(any(), any(), anyString(), anyString());
        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
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
            .andExpect(jsonPath("$.error.type").value("server_error"))
            .andExpect(jsonPath("$.error.message").value("Internal server error: model service unavailable"));
    }

    @Test
    void errorDuringCompletionRedactsSecretLikeHermes() throws Exception {
        String rawSecret = "sk-runtime-exception-leak-1234567890";
        when(agentRuntime.run(anyList(), anyList()))
            .thenThrow(new RuntimeException("provider auth failed OPENAI_API_KEY=" + rawSecret));

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "messages": [{"role": "user", "content": "Hello"}]
                    }
                    """))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.type").value("server_error"))
            .andExpect(jsonPath("$.error.message").value(
                "Internal server error: provider auth failed OPENAI_API_KEY=[REDACTED]"))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(rawSecret);
    }

    @Test
    void whenNoToolsProvidedFallsBackToRegistryDefinitions() throws Exception {
        ToolDefinition registryTool = new ToolDefinition(
            "read_file",
            "Reads a file",
            objectSchema()
        );
        when(toolRegistry.getDefinitions(Set.of("hermes-api-server"))).thenReturn(List.of(registryTool));
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

        verify(toolRegistry).getDefinitions(Set.of("hermes-api-server"));
    }

    @Test
    void whenApiToolsetsAreEmptyNoToolsAreExposed() throws Exception {
        properties.getApi().getChatCompletionToolsets().clear();
        ArgumentCaptor<List<ToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        when(agentRuntime.run(anyList(), toolsCaptor.capture())).thenReturn(ChatResponse.text("no tools"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Hello"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        assertThat(toolsCaptor.getValue()).isEmpty();
        verify(openAiSessionService, never()).historyFor(sessionContext);
    }

    @Test
    void malformedRequestToolsAreSkipped() throws Exception {
        ArgumentCaptor<List<ToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        when(agentRuntime.run(anyList(), toolsCaptor.capture())).thenReturn(ChatResponse.text("ok"));

        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "user", "content": "Use tools"}],
              "tools": [
                {"type": "function"},
                {"type": "function", "function": {"name": "  ", "description": "bad"}},
                {"type": "function", "function": {"name": "lookup"}}
              ]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk());

        assertThat(toolsCaptor.getValue()).hasSize(1);
        ToolDefinition tool = toolsCaptor.getValue().get(0);
        assertThat(tool.name()).isEqualTo("lookup");
        assertThat(tool.description()).isEmpty();
        assertThat(tool.parameters()).containsEntry("type", "object");
    }

    @Test
    void requestEndingWithToolRoleIsRejectedLikeHermes() throws Exception {
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
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("No user message found in messages"));

        verify(agentRuntime, never()).run(anyList(), anyList());
    }

    @Test
    void missingModelUsesDefaultModel() throws Exception {
        when(agentRuntime.run(anyList(), anyList())).thenReturn(ChatResponse.text("ack"));

        String requestBody = """
            {
              "messages": [{"role": "user", "content": "Hello"}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value(OpenAiModelRouting.advertisedModel(properties)))
            .andExpect(jsonPath("$.choices[0].message.content").value("ack"));
    }

    @Test
    void emptyMessagesReturnsOpenAiErrorEnvelope() throws Exception {
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
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Missing or invalid 'messages' field"));
    }

    @Test
    void onlySystemMessagesReturnOpenAiErrorEnvelope() throws Exception {
        String requestBody = """
            {
              "model": "gpt-test",
              "messages": [{"role": "system", "content": "Be brief."}]
            }
            """;

        mockMvc.perform(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("No user message found in messages"));

        verify(agentRuntime, never()).run(anyList(), anyList());
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
}
