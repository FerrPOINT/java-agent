package com.azhukov.agent.api;

import com.azhukov.agent.api.mapper.OpenAiMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.client.StreamingResponseHandler;
import com.azhukov.agent.core.model.ChatResponse;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.ToolDefinition;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.prompt.PromptBuilder;
import com.azhukov.agent.core.security.DefaultRedactor;
import com.azhukov.agent.core.tool.ToolRegistry;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.OpenAiIdempotencyCache;
import com.azhukov.agent.service.OpenAiResponseStore;
import com.azhukov.agent.service.OpenAiSessionService;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OpenAiResponsesControllerTest {

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

        OpenAiResponsesController controller = new OpenAiResponsesController(
            agentRuntime,
            toolRegistry,
            promptBuilder,
            modelClient,
            objectMapper,
            openAiMapper,
            properties,
            openAiSessionService,
            new OpenAiResponseStore(objectMapper, null, null),
            new OpenAiIdempotencyCache(),
            new DefaultRedactor(properties),
            runAdmissionService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

        sessionContext = new OpenAiSessionContext(
            new Session(SESSION_ID, "user-1", "OpenAI", "openai-compatible", MODEL, null, Map.of(), null),
            false,
            null
        );
        lenient().when(openAiSessionService.resolve(any(), any(), anyString()))
            .thenReturn(sessionContext);
        lenient().when(openAiSessionService.resolveStoredResponseSession(any(UUID.class), any()))
            .thenReturn(sessionContext);
        lenient().when(openAiSessionService.historyFor(any()))
            .thenReturn(List.of());
        lenient().when(promptBuilder.buildSystemMessage(any(), any()))
            .thenAnswer(invocation -> {
                String instructions = invocation.getArgument(1);
                return Message.system(instructions == null || instructions.isBlank()
                    ? SYSTEM_PROMPT
                    : SYSTEM_PROMPT + "\n\n" + instructions);
            });
        lenient().when(toolRegistry.getDefinitions(Set.of("hermes-api-server")))
            .thenReturn(List.of());
    }

    @Test
    void createResponseReturnsOpenAiResponsesShape() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("pong"));

        String requestBody = """
            {
              "model": "gpt-resp",
              "input": "ping",
              "instructions": "Be brief.",
              "max_output_tokens": 42
            }
            """;

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("resp_")))
            .andExpect(jsonPath("$.object").value("response"))
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.model").value("gpt-resp"))
            .andExpect(jsonPath("$.output[0].type").value("message"))
            .andExpect(jsonPath("$.output[0].role").value("assistant"))
            .andExpect(jsonPath("$.output[0].content[0].type").value("output_text"))
            .andExpect(jsonPath("$.output[0].content[0].text").value("pong"))
            .andExpect(jsonPath("$.usage.input_tokens").value(0))
            .andExpect(jsonPath("$.usage.output_tokens").value(0))
            .andExpect(jsonPath("$.usage.total_tokens").value(0));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntime).run(messagesCaptor.capture(), anyList(), optionsCaptor.capture());

        assertThat(messagesCaptor.getValue()).extracting(Message::content)
            .containsExactly(SYSTEM_PROMPT + "\n\nBe brief.", "ping");
        assertThat(optionsCaptor.getValue().modelName()).isNull();
        assertThat(optionsCaptor.getValue().maxCompletionTokens()).isEqualTo(42);
        verify(openAiSessionService).persistTurn(any(), anyList(), any(ChatResponse.class), anyList());
    }

    @Test
    void profilePrefixedResponsesRouteMirrorsHermesMultiplexAlias() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("profile pong"));

        mockMvc.perform(post("/p/work/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "ping"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("response"))
            .andExpect(jsonPath("$.output[0].content[0].text").value("profile pong"));
    }

    @Test
    void responsesHeaderEchoesExternalStringSessionIdLikeHermes() throws Exception {
        String externalSessionId = "session_alice_1";
        OpenAiSessionContext external = new OpenAiSessionContext(
            new Session(SESSION_ID, "user-1", "OpenAI", "openai-compatible", MODEL, null, Map.of(), null),
            true,
            null,
            externalSessionId
        );
        when(openAiSessionService.resolve(eq(externalSessionId), any(), eq("gpt-resp")))
            .thenReturn(external);
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("external pong"));

        mockMvc.perform(post("/v1/responses")
                .header(OpenAiSessionService.SESSION_ID_HEADER, externalSessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "ping"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, externalSessionId))
            .andExpect(jsonPath("$.output[0].content[0].text").value("external pong"));
    }

    @Test
    void responsesFlatFunctionToolsAreMappedLikeOpenAiResponses() throws Exception {
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<ToolDefinition>> toolsCaptor = ArgumentCaptor.forClass((Class) List.class);
        when(agentRuntime.run(anyList(), toolsCaptor.capture(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("ok"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "use lookup",
                      "tools": [
                        {"type": "web_search"},
                        {
                          "type": "function",
                          "name": "lookup",
                          "description": "Lookup data",
                          "parameters": {
                            "type": "object",
                            "properties": {
                              "query": {"type": "string"}
                            },
                            "required": ["query"]
                          }
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("ok"));

        assertThat(toolsCaptor.getValue()).hasSize(1);
        ToolDefinition tool = toolsCaptor.getValue().get(0);
        assertThat(tool.name()).isEqualTo("lookup");
        assertThat(tool.description()).isEqualTo("Lookup data");
        assertThat(tool.parameters()).containsEntry("type", "object");
        assertThat(((Map<?, ?>) tool.parameters().get("properties")).containsKey("query")).isTrue();
        verify(toolRegistry, never()).getDefinitions(any());
    }

    @Test
    void blankApiModelNameDefaultsResponseModelToHermesAliasLikeHermes() throws Exception {
        properties.getApi().setModelName(" ");
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("pong"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "ping"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("hermes-agent"))
            .andExpect(jsonPath("$.output[0].content[0].text").value("pong"));

        assertThat(optionsCaptor.getValue().modelName()).isEqualTo(MODEL);
    }

    @Test
    void createResponseReturns429WhenApiRunLimitIsReachedLikeHermes() throws Exception {
        properties.getApi().setMaxConcurrentRuns(1);

        try (ApiRunAdmissionService.Reservation ignored = runAdmissionService.tryAcquire().orElseThrow()) {
            mockMvc.perform(post("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "model": "gpt-resp",
                          "input": "ping"
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
        verify(openAiSessionService, never()).persistTurn(any(), anyList(), any(ChatResponse.class), anyList());
    }

    @Test
    void resultErrorFallbackIsRedactedLikeHermes() throws Exception {
        String rawSecret = "sk-responses-leak-1234567890";
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(TurnResult.error("provider auth failed OPENAI_API_KEY=" + rawSecret));

        MvcResult result = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "Hello"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value(
                "provider auth failed OPENAI_API_KEY=[REDACTED]"))
            .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(rawSecret);
        assertThat(body).contains("OPENAI_API_KEY=");
    }

    @Test
    void idempotencyKeyReusesNonStreamingResponseWithoutRerunningAgent() throws Exception {
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(
                List.of(Message.assistant("cached response", 1)),
                true,
                null
            ));

        String requestBody = """
            {
              "model": "gpt-resp",
              "input": "retry me"
            }
            """;

        MvcResult first = mockMvc.perform(post("/v1/responses")
                .header("Idempotency-Key", "idem-resp-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("cached response"))
            .andReturn();
        MvcResult second = mockMvc.perform(post("/v1/responses")
                .header("Idempotency-Key", "idem-resp-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("cached response"))
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
            .thenReturn(new TurnResult(List.of(Message.assistant("first response scope", 1)), true, null))
            .thenReturn(new TurnResult(List.of(Message.assistant("second response scope", 1)), true, null));

        String requestBody = """
            {
              "model": "gpt-resp",
              "input": "same body"
            }
            """;

        mockMvc.perform(post("/v1/responses")
                .header("Idempotency-Key", "idem-resp-scope")
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "scope-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("first response scope"));
        mockMvc.perform(post("/v1/responses")
                .header("Idempotency-Key", "idem-resp-scope")
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "scope-b")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("second response scope"));

        verify(agentRuntime, times(2)).runMessages(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void createResponseRoutesConfiguredModelAliasToRuntimeModel() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/resp-fast");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("resp-fast", route);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("pong"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "resp-fast",
                      "input": "ping"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("resp-fast"))
            .andExpect(jsonPath("$.output[0].content[0].text").value("pong"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo("openrouter/resp-fast");
        assertThat(options.provider()).isEqualTo("openrouter");
        assertThat(options.baseUrl()).isEqualTo("https://openrouter.example/v1");
        assertThat(options.apiKey()).isEqualTo("sk-route-secret");
        assertThat(options.toString()).doesNotContain("sk-route-secret");
    }

    @Test
    void createResponseRejectsMismatchedRouteProviderLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/resp-fast");
        route.setProvider("openrouter");
        properties.getApi().getModelRoutes().put("resp-fast", route);

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "resp-fast",
                      "provider": "minimax",
                      "input": "ping"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value(
                "Model route 'resp-fast' is pinned to provider 'openrouter'. "
                    + "Remove 'provider' or use 'openrouter'."));
    }

    @Test
    void explicitProviderPassesBareModelWithoutDirectModelOptIn() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("pong"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "MiniMax-M3",
                      "provider": "minimax",
                      "input": "ping"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("MiniMax-M3"))
            .andExpect(jsonPath("$.output[0].content[0].text").value("pong"));

        assertThat(optionsCaptor.getValue().modelName()).isEqualTo("MiniMax-M3");
        assertThat(optionsCaptor.getValue().provider()).isEqualTo("minimax");
    }

    @Test
    void providerPrefixedModelDoesNotOverrideOpenAiCompatibleRuntimeByDefaultLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/resp-fast");
        route.setProvider("openrouter");
        properties.getApi().getModelRoutes().put("resp-fast", route);

        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("pong"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "openrouter::resp-fast",
                      "input": "ping"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("openrouter::resp-fast"))
            .andExpect(jsonPath("$.output[0].content[0].text").value("pong"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isNull();
        assertThat(options.provider()).isNull();
    }

    @Test
    void modelOptionsPopulateRuntimeRequestOptions() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("pong"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "hermes-agent",
                      "input": "ping",
                      "model_options": {
                        "reasoning_effort": "ultra",
                        "service_tier": "priority",
                        "voice_mode": "true",
                        "personality": "brief",
                        "sub_goal": "respond",
                        "maxCompletionTokens": "777"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("pong"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo(MODEL);
        assertThat(options.reasoningEffort()).isEqualTo("ultra");
        assertThat(options.fastMode()).isNull();
        assertThat(options.voiceMode()).isTrue();
        assertThat(options.personality()).isEqualTo("brief");
        assertThat(options.subgoal()).isEqualTo("respond");
        assertThat(options.maxCompletionTokens()).isEqualTo(777);
        assertThat(options.serviceTier()).isEqualTo("priority");
    }

    @Test
    void nonObjectModelOptionsAreIgnoredLikeHermes() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("pong"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "hermes-agent",
                      "input": "ping",
                      "model_options": "ignored"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("pong"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo(MODEL);
        assertThat(options.reasoningEffort()).isNull();
        assertThat(options.fastMode()).isNull();
        assertThat(options.voiceMode()).isNull();
        assertThat(options.personality()).isNull();
        assertThat(options.subgoal()).isNull();
        assertThat(options.maxCompletionTokens()).isNull();
    }

    @Test
    void nonStringModelAndProviderAreIgnoredLikeHermes() throws Exception {
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        when(agentRuntime.run(anyList(), anyList(), optionsCaptor.capture()))
            .thenReturn(ChatResponse.text("pong"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": {"id": "gpt-5"},
                      "provider": ["minimax"],
                      "input": "ping"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("hermes-agent"))
            .andExpect(jsonPath("$.output[0].content[0].text").value("pong"));

        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo(MODEL);
        assertThat(options.provider()).isNull();
    }

    @Test
    void emptyInputArrayReturnsOpenAiErrorEnvelope() throws Exception {
        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": []
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("No user message found in input"));

        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void invalidInputImageContentPartReturnsHermesErrorShape() throws Exception {
        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": [
                        {
                          "role": "user",
                          "content": [
                            {"type": "input_image", "image_url": ""}
                          ]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Image parts must include a non-empty image URL."))
            .andExpect(jsonPath("$.error.param").value("input[0].content"))
            .andExpect(jsonPath("$.error.code").value("invalid_image_url"));

        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void invalidConversationHistoryImageContentPartReturnsHermesErrorShape() throws Exception {
        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "now",
                      "conversation_history": [
                        {
                          "role": "user",
                          "content": [
                            {"type": "input_image", "image_url": "file:///tmp/a.png"}
                          ]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Image inputs must use http(s) URLs or data:image/... URLs."))
            .andExpect(jsonPath("$.error.param").value("conversation_history[0].content"))
            .andExpect(jsonPath("$.error.code").value("invalid_image_url"));

        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void conversationHistoryPreservesAssistantToolCallsBeforeRun() throws Exception {
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(List.of(Message.assistant("ok", 1)), true, null));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "continue",
                      "conversation_history": [
                        {"role": "user", "content": "old question"},
                        {
                          "role": "assistant",
                          "content": "I will search.",
                          "tool_calls": [
                            {
                              "id": "call_search",
                              "type": "function",
                              "function": {
                                "name": "web_search",
                                "arguments": "{\\"query\\":\\"old\\"}"
                              }
                            }
                          ]
                        },
                        {"role": "tool", "tool_call_id": "call_search", "content": "old result"}
                      ]
                    }
                    """))
            .andExpect(status().isOk());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime).runMessages(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<Message> messages = messagesCaptor.getValue();
        assertThat(messages).extracting(Message::role)
            .containsExactly(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER);
        assertThat(messages.get(2).content()).isEqualTo("I will search.");
        assertThat(messages.get(2).toolCalls())
            .containsExactly(new ToolCall("call_search", "web_search", "{\"query\":\"old\"}"));
        assertThat(messages.get(3).toolCallId()).isEqualTo("call_search");
        assertThat(messages.get(3).content()).isEqualTo("old result");
    }

    @Test
    void responseStyleFunctionCallInputIsAcceptedAndReplaySanitizedLikeHermes() throws Exception {
        String longCallId = "codex_mcp__hermes-tools__web_search_exec-" + "0".repeat(43);
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(new TurnResult(List.of(Message.assistant("ok", 1)), true, null));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": [
                        {
                          "type": "function_call",
                          "call_id": "%s",
                          "name": "exec.command",
                          "arguments": {"query": "old"}
                        },
                        {
                          "type": "function_call_output",
                          "call_id": "%s",
                          "output": "old result"
                        },
                        {"type": "message", "role": "user", "content": "continue"}
                      ]
                    }
                    """.formatted(longCallId, longCallId)))
            .andExpect(status().isOk());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime).runMessages(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<Message> messages = messagesCaptor.getValue();
        assertThat(messages).extracting(Message::role)
            .containsExactly(Role.SYSTEM, Role.ASSISTANT, Role.TOOL, Role.USER);
        ToolCall parsedCall = messages.get(1).toolCalls().get(0);
        assertThat(parsedCall.name()).isEqualTo("exec_command");
        assertThat(parsedCall.arguments()).isEqualTo("{\"query\":\"old\"}");
        assertThat(parsedCall.id()).startsWith("call_");
        assertThat(parsedCall.id().length()).isLessThanOrEqualTo(64);
        assertThat(parsedCall.id()).isEqualTo(messages.get(2).toolCallId());
        assertThat(messages.get(2).content()).isEqualTo("old result");
    }

    @Test
    void responseStyleFunctionCallInputRejectsMissingCallIdLikeHermes() throws Exception {
        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": [
                        {
                          "type": "function_call",
                          "name": "web_search",
                          "arguments": {"query": "old"}
                        },
                        {"type": "message", "role": "user", "content": "continue"}
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("input[0] function_call is missing call_id"));

        verify(agentRuntime, never()).runMessages(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void nonArrayConversationHistoryReturnsHermesErrorShape() throws Exception {
        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "now",
                      "conversation_history": {"role": "user", "content": "old"}
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value(
                "'conversation_history' must be an array of message objects"));

        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
    }

    @Test
    void previousResponseIdChainsStoredHistoryAndSession() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("first answer"))
            .thenReturn(ChatResponse.text("second answer"));

        MvcResult first = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "first"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        String responseId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "previous_response_id": "%s",
                      "input": "second"
                    }
                    """.formatted(responseId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("second answer"));

        verify(openAiSessionService).resolveStoredResponseSession(SESSION_ID, null);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime, times(2)).run(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<Message> secondMessages = messagesCaptor.getAllValues().get(1);
        assertThat(secondMessages).extracting(Message::content)
            .containsExactly(SYSTEM_PROMPT, "first", "first answer", "second");
    }

    @Test
    void emptyConversationHistoryDoesNotOverridePreviousResponseLikeHermes() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("first answer"))
            .thenReturn(ChatResponse.text("second answer"));

        MvcResult first = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "first"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        String responseId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "previous_response_id": "%s",
                      "conversation_history": [],
                      "input": "second"
                    }
                    """.formatted(responseId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("second answer"));

        verify(openAiSessionService).resolveStoredResponseSession(SESSION_ID, null);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime, times(2)).run(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<Message> secondMessages = messagesCaptor.getAllValues().get(1);
        assertThat(secondMessages).extracting(Message::content)
            .containsExactly(SYSTEM_PROMPT, "first", "first answer", "second");
    }

    @Test
    void previousResponseIdStoresCompressedTranscriptReplacementWithoutPriorDuplication() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(invocation -> {
                int call = calls.incrementAndGet();
                List<Message> runtimeMessages = invocation.getArgument(0);
                if (call == 1) {
                    return new TurnResult(List.of(Message.assistant("first answer", 1)), true, null);
                }
                if (call == 2) {
                    return new TurnResult(List.of(
                        runtimeMessages.get(0),
                        Message.system("[CONTEXT COMPACTION] compacted earlier turns"),
                        Message.user("second"),
                        Message.assistant("second answer", 1)
                    ), true, null);
                }
                List<Message> resultMessages = new ArrayList<>(runtimeMessages);
                resultMessages.add(Message.assistant("third answer", 1));
                return new TurnResult(List.copyOf(resultMessages), true, null);
            });

        MvcResult first = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "first"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        String firstResponseId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        MvcResult second = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "previous_response_id": "%s",
                      "input": "second"
                    }
                    """.formatted(firstResponseId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("second answer"))
            .andReturn();
        String secondResponseId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "previous_response_id": "%s",
                      "input": "third"
                    }
                    """.formatted(secondResponseId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("third answer"));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime, times(3)).runMessages(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<Message> thirdMessages = messagesCaptor.getAllValues().get(2);
        assertThat(thirdMessages).extracting(Message::role)
            .containsExactly(Role.SYSTEM, Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER);
        assertThat(thirdMessages).extracting(Message::content)
            .containsExactly(
                SYSTEM_PROMPT,
                "[CONTEXT COMPACTION] compacted earlier turns",
                "second",
                "second answer",
                "third");
        assertThat(thirdMessages).extracting(Message::content)
            .doesNotContain("first", "first answer");
    }

    @Test
    void previousResponseIdChainsGeneratedToolHistoryFromRunMessages() throws Exception {
        ToolCall toolCall = new ToolCall("call_1", "lookup", "{\"q\":\"first\"}");
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(invocation -> {
                List<Message> runtimeMessages = invocation.getArgument(0);
                List<Message> resultMessages = new ArrayList<>(runtimeMessages);
                resultMessages.add(Message.assistantToolCalls(List.of(toolCall), 1));
                resultMessages.add(Message.toolResult("call_1", "tool payload", 1));
                resultMessages.add(Message.assistant("first answer", 1));
                return new TurnResult(List.copyOf(resultMessages), true, null);
            })
            .thenAnswer(invocation -> {
                List<Message> runtimeMessages = invocation.getArgument(0);
                List<Message> resultMessages = new ArrayList<>(runtimeMessages);
                resultMessages.add(Message.assistant("second answer", 1));
                return new TurnResult(List.copyOf(resultMessages), true, null);
            });

        MvcResult first = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "first"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].type").value("function_call"))
            .andExpect(jsonPath("$.output[0].status").value("completed"))
            .andExpect(jsonPath("$.output[0].name").value("lookup"))
            .andExpect(jsonPath("$.output[0].call_id").value("call_1"))
            .andExpect(jsonPath("$.output[1].type").value("function_call_output"))
            .andExpect(jsonPath("$.output[1].status").value("completed"))
            .andExpect(jsonPath("$.output[1].call_id").value("call_1"))
            .andExpect(jsonPath("$.output[1].output").value("tool payload"))
            .andExpect(jsonPath("$.output[2].type").value("message"))
            .andExpect(jsonPath("$.output[2].content[0].text").value("first answer"))
            .andReturn();
        String responseId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "previous_response_id": "%s",
                      "input": "second"
                    }
                    """.formatted(responseId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("second answer"));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime, times(2)).runMessages(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<Message> secondMessages = messagesCaptor.getAllValues().get(1);
        assertThat(secondMessages).extracting(Message::role)
            .containsExactly(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT, Role.USER);
        assertThat(secondMessages.get(2).toolCalls()).containsExactly(toolCall);
        assertThat(secondMessages.get(3).toolCallId()).isEqualTo("call_1");
        assertThat(secondMessages.get(3).content()).isEqualTo("tool payload");
        assertThat(secondMessages).extracting(Message::content)
            .containsExactly(SYSTEM_PROMPT, "first", null, "tool payload", "first answer", "second");
    }

    @Test
    void truncationAutoKeepsMostRecentConversationHistoryBeforeRun() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("ok"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "now",
                      "truncation": "auto",
                      "conversation_history": [%s]
                    }
                    """.formatted(responseHistory(105, -1))))
            .andExpect(status().isOk());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime).run(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<String> contents = messagesCaptor.getValue().stream()
            .map(Message::content)
            .toList();
        assertThat(contents).hasSize(102);
        assertThat(contents.get(0)).isEqualTo(SYSTEM_PROMPT);
        assertThat(contents.get(1)).isEqualTo("old-5");
        assertThat(contents.get(100)).isEqualTo("old-104");
        assertThat(contents.get(101)).isEqualTo("now");
        assertThat(contents).doesNotContain("old-0", "old-4");
    }

    @Test
    void truncationAutoPreservesCompactionSummaryOutsideRecentWindow() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("ok"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "now",
                      "truncation": "auto",
                      "conversation_history": [%s]
                    }
                    """.formatted(responseHistory(105, 1))))
            .andExpect(status().isOk());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime).run(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<String> contents = messagesCaptor.getValue().stream()
            .map(Message::content)
            .toList();
        assertThat(contents).hasSize(102);
        assertThat(contents.get(0)).isEqualTo(SYSTEM_PROMPT);
        assertThat(contents.get(1)).startsWith("[CONTEXT COMPACTION");
        assertThat(contents.get(2)).isEqualTo("old-6");
        assertThat(contents.get(100)).isEqualTo("old-104");
        assertThat(contents.get(101)).isEqualTo("now");
        assertThat(contents).doesNotContain("old-0", "old-5");
    }

    @Test
    void getAndDeleteStoredResponseRoundTrip() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("stored"));

        MvcResult created = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "remember this"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        String responseId = body.get("id").asText();

        mockMvc.perform(get("/v1/responses/{responseId}", responseId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(responseId))
            .andExpect(jsonPath("$.output[0].content[0].text").value("stored"));

        mockMvc.perform(delete("/v1/responses/{responseId}", responseId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(responseId))
            .andExpect(jsonPath("$.object").value("response"))
            .andExpect(jsonPath("$.deleted").value(true));

        mockMvc.perform(get("/v1/responses/{responseId}", responseId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Response not found: " + responseId));
    }

    @Test
    void storeOffStringSkipsStoredResponseLikeHermes() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("not stored"));

        MvcResult created = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "do not remember",
                      "store": "off"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("not stored"))
            .andReturn();

        String responseId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/v1/responses/{responseId}", responseId))
            .andExpect(status().isNotFound());
    }

    @Test
    void storedResponsesAreBoundedLikeHermesResponseStore() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("stored"));

        String firstResponseId = null;
        for (int i = 0; i < 101; i++) {
            MvcResult created = mockMvc.perform(post("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "input": "message-%d"
                        }
                        """.formatted(i)))
                .andExpect(status().isOk())
                .andReturn();
            if (i == 0) {
                firstResponseId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
            }
        }

        mockMvc.perform(get("/v1/responses/{responseId}", firstResponseId))
            .andExpect(status().isNotFound());
    }

    @Test
    void toolCallsAreProjectedAsResponsesFunctionCallItems() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.textAndToolCalls(
                "I need a lookup.",
                List.of(new ToolCall("call_abc", "web_search", "{\"query\":\"Hermes\"}"))));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "search"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].type").value("function_call"))
            .andExpect(jsonPath("$.output[0].status").value("completed"))
            .andExpect(jsonPath("$.output[0].name").value("web_search"))
            .andExpect(jsonPath("$.output[0].arguments").value("{\"query\":\"Hermes\"}"))
            .andExpect(jsonPath("$.output[0].call_id").value("call_abc"))
            .andExpect(jsonPath("$.output[1].type").value("message"))
            .andExpect(jsonPath("$.output[1].content[0].text").value("I need a lookup."));
    }

    @Test
    void streamTrueEmitsResponsesSseEventsAndStoresTerminalResponse() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("hello"));

        MvcResult result = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-resp",
                      "input": "hello",
                      "stream": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult streamed = mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        String response = streamed.getResponse().getContentAsString();
        assertThat(response).contains(
            "event:response.created",
            "event:response.output_item.added",
            "event:response.output_text.delta",
            "event:response.output_text.done",
            "event:response.output_item.done",
            "event:response.completed");

        List<JsonNode> payloads = dataPayloads(response).stream()
            .map(this::parseJson)
            .toList();
        assertThat(payloads.stream().map(node -> node.get("type").asText()).toList())
            .containsExactly(
                "response.created",
                "response.output_item.added",
                "response.output_text.delta",
                "response.output_text.done",
                "response.output_item.done",
                "response.completed");
        assertThat(payloads.stream().map(node -> node.get("sequence_number").asInt()).toList())
            .containsExactly(0, 1, 2, 3, 4, 5);

        JsonNode completed = payloads.get(payloads.size() - 1).get("response");
        String responseId = completed.get("id").asText();
        assertThat(completed.get("status").asText()).isEqualTo("completed");
        assertThat(completed.get("model").asText()).isEqualTo("gpt-resp");
        assertThat(completed.get("output").get(0).get("type").asText()).isEqualTo("message");
        assertThat(completed.get("output").get(0).get("content").get(0).get("text").asText()).isEqualTo("hello");

        mockMvc.perform(get("/v1/responses/{responseId}", responseId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(responseId))
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.output[0].content[0].text").value("hello"));

        verify(agentRuntime).run(anyList(), anyList(), any(ModelRequestOptions.class));
        verify(modelClient, never()).stream(anyList(), anyList(), any(ModelRequestOptions.class), any(StreamingResponseHandler.class));
        verify(openAiSessionService).persistTurn(any(), anyList(), any(ChatResponse.class), anyList());
    }

    @Test
    void streamOnStringEnablesResponsesSseLikeHermes() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("hello"));

        MvcResult result = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "hello",
                      "stream": "on"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult streamed = mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        assertThat(streamed.getResponse().getContentAsString())
            .contains("event:response.created", "event:response.completed");
    }

    @Test
    void streamTrueProjectsToolCallsAsResponsesOutputItemEvents() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.textAndToolCalls(
                "",
                List.of(new ToolCall("call_abc", "web_search", "{\"query\":\"Hermes\"}"))));

        MvcResult result = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "search",
                      "stream": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult streamed = mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        List<JsonNode> payloads = dataPayloads(streamed.getResponse().getContentAsString()).stream()
            .map(this::parseJson)
            .toList();
        assertThat(payloads.stream().map(node -> node.get("type").asText()).toList())
            .containsExactly(
                "response.created",
                "response.output_item.added",
                "response.output_item.done",
                "response.completed");

        JsonNode added = payloads.get(1);
        JsonNode addedItem = added.get("item");
        assertThat(addedItem.get("type").asText()).isEqualTo("function_call");
        assertThat(addedItem.get("status").asText()).isEqualTo("in_progress");
        assertThat(addedItem.get("name").asText()).isEqualTo("web_search");
        assertThat(addedItem.get("arguments").asText()).isEqualTo("{\"query\":\"Hermes\"}");
        assertThat(addedItem.get("call_id").asText()).isEqualTo("call_abc");

        JsonNode doneItem = payloads.get(2).get("item");
        assertThat(doneItem.get("id").asText()).isEqualTo(addedItem.get("id").asText());
        assertThat(doneItem.get("status").asText()).isEqualTo("completed");

        JsonNode completed = payloads.get(3).get("response");
        assertThat(completed.get("output").get(0).get("type").asText()).isEqualTo("function_call");
        assertThat(completed.get("output").get(0).get("name").asText()).isEqualTo("web_search");
        assertThat(completed.get("output").get(0).get("call_id").asText()).isEqualTo("call_abc");
        assertThat(completed.get("output").get(1).get("type").asText()).isEqualTo("message");

        verify(agentRuntime).run(anyList(), anyList(), any(ModelRequestOptions.class));
        verify(modelClient, never()).stream(anyList(), anyList(), any(ModelRequestOptions.class), any(StreamingResponseHandler.class));
    }

    @Test
    void streamTrueProjectsGeneratedToolOutputsAsResponsesOutputItemEvents() throws Exception {
        ToolCall toolCall = new ToolCall("call_stream", "lookup", "{\"q\":\"stream\"}");
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(invocation -> {
                List<Message> runtimeMessages = invocation.getArgument(0);
                List<Message> resultMessages = new ArrayList<>(runtimeMessages);
                resultMessages.add(Message.assistantToolCalls(List.of(toolCall), 1));
                resultMessages.add(Message.toolResult("call_stream", "stream tool payload", 1));
                resultMessages.add(Message.assistant("stream final", 1));
                return new TurnResult(List.copyOf(resultMessages), true, null);
            });

        MvcResult result = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "search",
                      "stream": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult streamed = mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        List<JsonNode> payloads = dataPayloads(streamed.getResponse().getContentAsString()).stream()
            .map(this::parseJson)
            .toList();
        assertThat(payloads.stream().map(node -> node.get("type").asText()).toList())
            .containsExactly(
                "response.created",
                "response.output_item.added",
                "response.output_item.done",
                "response.output_item.added",
                "response.output_item.done",
                "response.output_item.added",
                "response.output_text.delta",
                "response.output_text.done",
                "response.output_item.done",
                "response.completed");
        assertThat(payloads.stream().map(node -> node.get("sequence_number").asInt()).toList())
            .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        JsonNode callAdded = payloads.get(1).get("item");
        assertThat(callAdded.get("type").asText()).isEqualTo("function_call");
        assertThat(callAdded.get("status").asText()).isEqualTo("in_progress");
        assertThat(callAdded.get("name").asText()).isEqualTo("lookup");
        assertThat(callAdded.get("call_id").asText()).isEqualTo("call_stream");

        JsonNode callDone = payloads.get(2).get("item");
        assertThat(callDone.get("id").asText()).isEqualTo(callAdded.get("id").asText());
        assertThat(callDone.get("status").asText()).isEqualTo("completed");

        JsonNode outputAdded = payloads.get(3).get("item");
        assertThat(outputAdded.get("type").asText()).isEqualTo("function_call_output");
        assertThat(outputAdded.get("status").asText()).isEqualTo("completed");
        assertThat(outputAdded.get("call_id").asText()).isEqualTo("call_stream");
        assertThat(outputAdded.get("output").get(0).get("type").asText()).isEqualTo("input_text");
        assertThat(outputAdded.get("output").get(0).get("text").asText()).isEqualTo("stream tool payload");

        JsonNode outputDone = payloads.get(4).get("item");
        assertThat(outputDone.get("id").asText()).isEqualTo(outputAdded.get("id").asText());

        JsonNode completed = payloads.get(9).get("response");
        assertThat(completed.get("output").get(0).get("type").asText()).isEqualTo("function_call");
        assertThat(completed.get("output").get(0).get("call_id").asText()).isEqualTo("call_stream");
        assertThat(completed.get("output").get(1).get("type").asText()).isEqualTo("function_call_output");
        assertThat(completed.get("output").get(1).get("output").asText()).isEqualTo("stream tool payload");
        assertThat(completed.get("output").get(2).get("type").asText()).isEqualTo("message");
        assertThat(completed.get("output").get(2).get("content").get(0).get("text").asText()).isEqualTo("stream final");

        verify(agentRuntime).runMessages(anyList(), anyList(), any(ModelRequestOptions.class));
        verify(agentRuntime, never()).run(anyList(), anyList(), any(ModelRequestOptions.class));
        verify(modelClient, never()).stream(anyList(), anyList(), any(ModelRequestOptions.class), any(StreamingResponseHandler.class));
    }

    @Test
    void streamTruePairsGeneratedToolOutputsByCallIdAlias() throws Exception {
        ToolCall toolCall = new ToolCall("call_stream|response_item_stream", "lookup", "{\"q\":\"stream\"}");
        when(agentRuntime.runMessages(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenAnswer(invocation -> {
                List<Message> runtimeMessages = invocation.getArgument(0);
                List<Message> resultMessages = new ArrayList<>(runtimeMessages);
                resultMessages.add(Message.assistantToolCalls(List.of(toolCall), 1));
                resultMessages.add(Message.toolResult("response_item_stream", "alias payload", 1));
                resultMessages.add(Message.assistant("stream final", 1));
                return new TurnResult(List.copyOf(resultMessages), true, null);
            });

        MvcResult result = mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "search",
                      "stream": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult streamed = mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn();

        List<JsonNode> payloads = dataPayloads(streamed.getResponse().getContentAsString()).stream()
            .map(this::parseJson)
            .toList();

        JsonNode outputAdded = payloads.get(3).get("item");
        assertThat(outputAdded.get("type").asText()).isEqualTo("function_call_output");
        assertThat(outputAdded.get("call_id").asText()).isEqualTo("call_stream|response_item_stream");
        assertThat(outputAdded.get("output").get(0).get("text").asText()).isEqualTo("alias payload");

        JsonNode completed = payloads.get(payloads.size() - 1).get("response");
        assertThat(completed.get("output").get(0).get("call_id").asText())
            .isEqualTo("call_stream|response_item_stream");
        assertThat(completed.get("output").get(1).get("call_id").asText())
            .isEqualTo("call_stream|response_item_stream");
        assertThat(completed.get("output").get(1).get("output").asText()).isEqualTo("alias payload");
    }

    @Test
    void inputImageContentPartIsAcceptedAndMarksImageCount() throws Exception {
        when(agentRuntime.run(anyList(), anyList(), any(ModelRequestOptions.class)))
            .thenReturn(ChatResponse.text("ok"));

        mockMvc.perform(post("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": [
                        {
                          "role": "user",
                          "content": [
                            {"type": "input_text", "text": "Describe."},
                            {"type": "input_image", "image_url": "data:image/png;base64,AAAA"}
                          ]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output[0].content[0].text").value("ok"));

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(agentRuntime).run(messagesCaptor.capture(), anyList(), any(ModelRequestOptions.class));

        List<Message> messages = messagesCaptor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).content()).isEqualTo("Describe.\n[image_url: data:image/png;base64,<redacted>]");
        assertThat(messages.get(1).content()).doesNotContain("AAAA");
        assertThat(messages.get(1).imageCount()).isEqualTo(1);
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse SSE data line: " + raw, e);
        }
    }

    private String responseHistory(int count, int summaryIndex) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String role = i == summaryIndex ? "system" : "user";
            String content = i == summaryIndex
                ? "[CONTEXT COMPACTION] compacted earlier turns"
                : "old-" + i;
            entries.add("""
                {"role":"%s","content":"%s"}""".formatted(role, content));
        }
        return String.join(",", entries);
    }

    private List<String> dataPayloads(String response) {
        return response.lines()
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring(5).trim())
            .toList();
    }
}
