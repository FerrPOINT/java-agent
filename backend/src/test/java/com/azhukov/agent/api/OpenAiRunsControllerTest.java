package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.InterruptToken;
import com.azhukov.agent.core.agent.RunControlScope;
import com.azhukov.agent.core.agent.SteerBuffer;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.DefaultRedactor;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.OpenAiResponseStore;
import com.azhukov.agent.service.OpenAiResponseStore.StoredResponse;
import com.azhukov.agent.service.OpenAiRunService;
import com.azhukov.agent.service.OpenAiSessionService;
import com.azhukov.agent.service.OpenAiSessionService.OpenAiSessionContext;
import com.azhukov.agent.tools.terminal.ProcessTool;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OpenAiRunsControllerTest {

    private static final String MODEL = "gpt-test";
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private AgentProperties properties;
    private OpenAiResponseStore responseStore;
    private OpenAiRunService runService;
    private SteerBuffer steerBuffer;
    private InterruptToken interruptToken;
    private ApprovalQueue approvalQueue;
    private ProcessTool processTool;
    private OpenAiSessionContext sessionContext;
    private ApiRunAdmissionService runAdmissionService;

    @Mock
    private AgentRuntimeService agentRuntimeService;

    @Mock
    private OpenAiSessionService openAiSessionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        responseStore = new OpenAiResponseStore(objectMapper, null, null);
        steerBuffer = new SteerBuffer();
        interruptToken = new InterruptToken();
        approvalQueue = new ApprovalQueue();
        processTool = mock(ProcessTool.class);
        properties = new AgentProperties();
        properties.getModel().setModelName(MODEL);
        runAdmissionService = new ApiRunAdmissionService(properties);

        runService = new OpenAiRunService(
            agentRuntimeService,
            openAiSessionService,
            steerBuffer,
            interruptToken,
            approvalQueue,
            new DefaultRedactor(properties),
            objectMapper,
            processTool
        );

        OpenAiMapper openAiMapper = Mappers.getMapper(OpenAiMapper.class);
        OpenAiRunsController controller = new OpenAiRunsController(
            runService,
            openAiSessionService,
            responseStore,
            openAiMapper,
            objectMapper,
            properties,
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
        lenient().when(openAiSessionService.resolveRunSession(nullable(UUID.class), any(), anyString()))
            .thenReturn(sessionContext);
        lenient().when(openAiSessionService.resolveRunSession(anyString(), any(), anyString()))
            .thenReturn(sessionContext);
    }

    @Test
    void createRunStartsAsyncTurnAndExposesCompletedStatus() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true, false, MODEL, 12, 4096));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-run",
                      "input": "do it",
                      "instructions": "Be direct.",
                      "max_output_tokens": 77,
                      "model_options": {
                        "reasoning_effort": "high",
                        "fast": true,
                        "voice": "true",
                        "personality": "concise",
                        "sub_goal": "run it"
                      }
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(jsonPath("$.run_id").value(org.hamcrest.Matchers.startsWith("run_")))
            .andExpect(jsonPath("$.status").value("started"))
            .andReturn();

        String runId = readRunId(created);
        JsonNode status = waitForStatus(runId, "completed");
        assertThat(status.path("object").asText()).isEqualTo("hermes.run");
        assertThat(status.path("output").asText()).isEqualTo("done");
        assertThat(status.path("usage").path("input_tokens").asInt()).isEqualTo(12);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntimeService).runApiTurn(sessionCaptor.capture(), eq("do it"), optionsCaptor.capture());
        assertThat(sessionCaptor.getValue().metadata())
            .containsEntry("system_prompt_override", "Be direct.")
            .containsKey(RunControlScope.METADATA_KEY);
        assertThat(optionsCaptor.getValue().modelName()).isEqualTo("gpt-run");
        assertThat(optionsCaptor.getValue().reasoningEffort()).isEqualTo("high");
        assertThat(optionsCaptor.getValue().fastMode()).isTrue();
        assertThat(optionsCaptor.getValue().voiceMode()).isTrue();
        assertThat(optionsCaptor.getValue().personality()).isEqualTo("concise");
        assertThat(optionsCaptor.getValue().subgoal()).isEqualTo("run it");
        assertThat(optionsCaptor.getValue().maxCompletionTokens()).isEqualTo(77);
    }

    @Test
    void createRunAcceptsExternalStringSessionIdLikeHermes() throws Exception {
        String externalSessionId = "cron_job42_20260801_090000";
        OpenAiSessionContext external = new OpenAiSessionContext(
            new Session(SESSION_ID, "user-1", "OpenAI", "openai-compatible", "gpt-run", null, Map.of(), null),
            true,
            null,
            externalSessionId
        );
        when(openAiSessionService.resolveRunSession(eq(externalSessionId), isNull(), eq("gpt-run")))
            .thenReturn(external);
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "external done", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-run",
                      "session_id": "cron_job42_20260801_090000",
                      "input": "do it"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, externalSessionId))
            .andExpect(jsonPath("$.run_id").value(org.hamcrest.Matchers.startsWith("run_")))
            .andReturn();

        JsonNode status = waitForStatus(readRunId(created), "completed");
        assertThat(status.path("output").asText()).isEqualTo("external done");
        verify(openAiSessionService).resolveRunSession(eq(externalSessionId), isNull(), eq("gpt-run"));
    }

    @Test
    void profilePrefixedRunsRouteMirrorsHermesMultiplexAlias() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "profile done", List.of(), true));

        MvcResult created = mockMvc.perform(post("/p/work/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "do it"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(header().string(OpenAiSessionService.SESSION_ID_HEADER, SESSION_ID.toString()))
            .andExpect(jsonPath("$.run_id").value(org.hamcrest.Matchers.startsWith("run_")))
            .andReturn();

        String runId = readRunId(created);
        JsonNode status = waitForProfileStatus(runId, "completed");
        assertThat(status.path("object").asText()).isEqualTo("hermes.run");
        assertThat(status.path("output").asText()).isEqualTo("profile done");
    }

    @Test
    void blankApiModelNameDefaultsRunModelToHermesAliasAndConfiguredRuntimeModel() throws Exception {
        properties.getApi().setModelName(" ");
        lenient().when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "do it"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();

        JsonNode status = waitForStatus(readRunId(created), "completed");
        assertThat(status.path("model").asText()).isEqualTo("java-agent");
        verify(openAiSessionService).resolveRunSession(isNull(UUID.class), isNull(), eq("java-agent"));

        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntimeService).runApiTurn(any(Session.class), eq("do it"), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().modelName()).isEqualTo(MODEL);
    }

    @Test
    void createRunReturns429WhenApiRunLimitIsReachedLikeHermes() throws Exception {
        properties.getApi().setMaxConcurrentRuns(1);

        try (ApiRunAdmissionService.Reservation ignored = runAdmissionService.tryAcquire().orElseThrow()) {
            mockMvc.perform(post("/v1/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "model": "gpt-run",
                          "input": "do it"
                        }
                        """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.error.message").value("Too many concurrent runs (max 1)"))
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"))
                .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.error.code").value("rate_limit_exceeded"));
        }

        verify(agentRuntimeService, never())
            .runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class));
        assertThat(runService.activeRunCount()).isZero();
    }

    @Test
    void createRunIgnoresNonObjectModelOptionsLikeHermes() throws Exception {
        lenient().when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-run",
                      "input": "do it",
                      "model_options": "ignored"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();
        waitForStatus(readRunId(created), "completed");

        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntimeService).runApiTurn(any(Session.class), eq("do it"), optionsCaptor.capture());
        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo("gpt-run");
        assertThat(options.reasoningEffort()).isNull();
        assertThat(options.fastMode()).isNull();
        assertThat(options.voiceMode()).isNull();
        assertThat(options.personality()).isNull();
        assertThat(options.subgoal()).isNull();
        assertThat(options.maxCompletionTokens()).isNull();
    }

    @Test
    void createRunPassesProviderPrefixedModelRawLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/run-fast");
        route.setProvider("openrouter");
        properties.getApi().getModelRoutes().put("run-fast", route);
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "openrouter::run-fast",
                      "input": "do it"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();
        waitForStatus(readRunId(created), "completed");

        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntimeService).runApiTurn(any(Session.class), eq("do it"), optionsCaptor.capture());
        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo("openrouter::run-fast");
        assertThat(options.provider()).isNull();
        assertThat(options.baseUrl()).isNull();
        assertThat(options.apiKey()).isNull();
    }

    @Test
    void createRunIgnoresNonStringModelAndProviderLikeHermes() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": {"id": "gpt-run"},
                      "provider": ["openrouter"],
                      "input": "do it"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();
        waitForStatus(readRunId(created), "completed");

        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntimeService).runApiTurn(any(Session.class), eq("do it"), optionsCaptor.capture());
        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo(MODEL);
        assertThat(options.provider()).isNull();
    }

    @Test
    void createRunRoutesAliasAndRequestProviderToRuntimeOptions() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/run-fast");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        properties.getApi().getModelRoutes().put("run-fast", route);
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "run-fast",
                      "provider": "openrouter",
                      "input": "do it"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();
        waitForStatus(readRunId(created), "completed");

        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntimeService).runApiTurn(any(Session.class), eq("do it"), optionsCaptor.capture());
        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo("openrouter/run-fast");
        assertThat(options.provider()).isEqualTo("openrouter");
        assertThat(options.baseUrl()).isEqualTo("https://openrouter.example/v1");
        assertThat(options.apiKey()).isEqualTo("sk-route-secret");
        assertThat(options.toString()).doesNotContain("sk-route-secret");
    }

    @Test
    void createRunPassesCurrentImageInputToRuntimeWithImageCount() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), any(Message.class), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "described", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": [
                        {
                          "role": "user",
                          "content": [
                            {"type": "input_text", "text": "Describe."},
                            {"type": "input_image", "image_url": "https://example.com/a.png"}
                          ]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();
        waitForStatus(readRunId(created), "completed");

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(agentRuntimeService).runApiTurn(any(Session.class), messageCaptor.capture(), any(ModelRequestOptions.class));
        assertThat(messageCaptor.getValue().content()).isEqualTo("Describe.\n[image_url: https://example.com/a.png]");
        assertThat(messageCaptor.getValue().imageCount()).isEqualTo(1);
    }

    @Test
    void emptyStringInputReturnsMissingInputLikeHermes() throws Exception {
        mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": ""
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Missing 'input' field"));
    }

    @Test
    void emptyInputArrayReturnsMissingInputLikeHermes() throws Exception {
        mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": []
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Missing 'input' field"));
    }

    @Test
    void invalidInputImageContentPartReturnsHermesErrorShape() throws Exception {
        mockMvc.perform(post("/v1/runs")
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
    }

    @Test
    void createRunRejectsMismatchedRouteProviderLikeHermes() throws Exception {
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("openrouter/run-fast");
        route.setProvider("openrouter");
        properties.getApi().getModelRoutes().put("run-fast", route);

        mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "run-fast",
                      "provider": "minimax",
                      "input": "do it"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value(
                "Model route 'run-fast' is pinned to provider 'openrouter'. "
                    + "Remove 'provider' or use 'openrouter'."));
    }

    @Test
    void createRunReturnsBadRequestForInvalidSessionKeyInsteadOfSessionNotFound() throws Exception {
        when(openAiSessionService.resolveRunSession(isNull(UUID.class), eq("bad-key"), eq(MODEL)))
            .thenThrow(new IllegalArgumentException("Invalid session key"));

        mockMvc.perform(post("/v1/runs")
                .header(OpenAiSessionService.SESSION_KEY_HEADER, "bad-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-test",
                      "input": "do it"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Invalid session key"))
            .andExpect(jsonPath("$.error.param").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.error.code").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void createRunMalformedJsonUsesHermesMessage() throws Exception {
        mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Invalid JSON"));
    }

    @Test
    void approvalMalformedJsonUsesHermesMessage() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String runId = createRun("wait for approval json parse");
        waitForStatus(runId, "running");

        mockMvc.perform(post("/v1/runs/{runId}/approval", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Invalid JSON"));

        blockingRun.complete();
        waitForStatus(runId, "completed");
    }

    @Test
    void steerMalformedJsonKeepsReadJsonBodyMessageLikeHermes() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String runId = createRun("wait for steer json parse");
        waitForStatus(runId, "running");

        mockMvc.perform(post("/v1/runs/{runId}/steer", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value("Invalid JSON in request body"));

        blockingRun.complete();
        waitForStatus(runId, "completed");
    }

    @Test
    void approvalUnknownRunReturnsNotFoundBeforeParsingBodyLikeHermes() throws Exception {
        mockMvc.perform(post("/v1/runs/run_missing/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("run_not_found"));
    }

    @Test
    void steerUnknownRunReturnsNotFoundBeforeParsingBodyLikeHermes() throws Exception {
        mockMvc.perform(post("/v1/runs/run_missing/steer")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("run_not_found"));
    }

    @Test
    void steerCompletedRunReturnsConflictBeforeParsingBodyLikeHermes() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));
        String runId = createRun("quick");
        waitForStatus(runId, "completed");

        mockMvc.perform(post("/v1/runs/{runId}/steer", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("run_not_accepting_steer"));
    }

    @Test
    void createRunPersistsExplicitConversationHistoryBeforeTurn() throws Exception {
        lenient().when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "fresh", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "continue",
                      "conversation_history": [
                        {"role": "user", "content": "old question"},
                        {"role": "assistant", "content": "old answer"}
                      ]
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();
        waitForStatus(readRunId(created), "completed");

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(openAiSessionService).persistHistory(eq(SESSION_ID), historyCaptor.capture());
        assertThat(historyCaptor.getValue()).extracting(Message::content)
            .containsExactly("old question", "old answer");
    }

    @Test
    void createRunPreservesConversationHistoryAssistantToolCalls() throws Exception {
        lenient().when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "fresh", List.of(), true));

        mockMvc.perform(post("/v1/runs")
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
            .andExpect(status().isAccepted());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(openAiSessionService).persistHistory(eq(SESSION_ID), historyCaptor.capture());

        List<Message> history = historyCaptor.getValue();
        assertThat(history).extracting(Message::role)
            .containsExactly(Role.USER, Role.ASSISTANT, Role.TOOL);
        assertThat(history.get(1).content()).isEqualTo("I will search.");
        assertThat(history.get(1).toolCalls())
            .containsExactly(new ToolCall("call_search", "web_search", "{\"query\":\"old\"}"));
        assertThat(history.get(2).toolCallId()).isEqualTo("call_search");
        assertThat(history.get(2).content()).isEqualTo("old result");
    }

    @Test
    void createRunPersistsResponseStyleFunctionCallInputLikeHermes() throws Exception {
        String longCallId = "codex_mcp__hermes-tools__web_search_exec-" + "0".repeat(43);
        lenient().when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "fresh", List.of(), true));

        mockMvc.perform(post("/v1/runs")
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
            .andExpect(status().isAccepted());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(openAiSessionService).persistHistory(eq(SESSION_ID), historyCaptor.capture());

        List<Message> history = historyCaptor.getValue();
        assertThat(history).extracting(Message::role)
            .containsExactly(Role.ASSISTANT, Role.TOOL);
        ToolCall parsedCall = history.get(0).toolCalls().get(0);
        assertThat(parsedCall.name()).isEqualTo("exec_command");
        assertThat(parsedCall.arguments()).isEqualTo("{\"query\":\"old\"}");
        assertThat(parsedCall.id()).startsWith("call_");
        assertThat(parsedCall.id().length()).isLessThanOrEqualTo(64);
        assertThat(history.get(1).toolCallId()).isEqualTo(parsedCall.id());
        assertThat(history.get(1).content()).isEqualTo("old result");
    }

    @Test
    void createRunRejectsResponseStyleFunctionCallInputWithoutCallIdLikeHermes() throws Exception {
        mockMvc.perform(post("/v1/runs")
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

        verify(openAiSessionService, never()).persistHistory(any(UUID.class), anyList());
    }

    @Test
    void createRunCanContinueFromStoredResponseSessionAndInstructions() throws Exception {
        responseStore.put("resp_previous", new StoredResponse(
            Map.of("id", "resp_previous"),
            List.of(Message.user("previous question"), Message.assistant("previous answer", 1)),
            "Reuse this style.",
            SESSION_ID
        ), null);
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "next", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-run",
                      "previous_response_id": "resp_previous",
                      "input": "next question"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();

        waitForStatus(readRunId(created), "completed");

        verify(openAiSessionService).resolveRunSession(eq(SESSION_ID), isNull(), eq("gpt-run"));
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntimeService).runApiTurn(sessionCaptor.capture(), eq("next question"), any(ModelRequestOptions.class));
        assertThat(sessionCaptor.getValue().metadata())
            .containsEntry("system_prompt_override", "Reuse this style.");
    }

    @Test
    void stopKnownRunCancelsSession() throws Exception {
        CountDownLatch enteredRuntime = new CountDownLatch(1);
        CountDownLatch releaseRuntime = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredRuntime.countDown();
            releaseRuntime.await(2, TimeUnit.SECONDS);
            return new ChatResponseDto(SESSION_ID, "", List.of(), false);
        }).when(agentRuntimeService).runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class));

        String runId = createRun("wait");
        assertThat(enteredRuntime.await(1, TimeUnit.SECONDS)).isTrue();
        waitForStatus(runId, "running");
        UUID controlSessionId = runService.get(runId).controlSessionId();
        assertThat(controlSessionId).isNotEqualTo(SESSION_ID);

        mockMvc.perform(post("/v1/runs/{runId}/stop", runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.run_id").value(runId))
            .andExpect(jsonPath("$.status").value("stopping"));

        assertThat(interruptToken.isCancelled(controlSessionId)).isTrue();
        verify(processTool).killOwnedBy(controlSessionId);
        releaseRuntime.countDown();
        waitForStatus(runId, "cancelled");
    }

    @Test
    void stopAfterCompletedRunReturnsNotFoundLikeHermes() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));
        String runId = createRun("quick");
        waitForStatus(runId, "completed");

        mockMvc.perform(post("/v1/runs/{runId}/stop", runId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Run not found: " + runId))
            .andExpect(jsonPath("$.error.code").value("run_not_found"));
    }

    @Test
    void failedRunRedactsStatusAndEventErrorLikeHermes() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenThrow(new RuntimeException("provider rejected OPENAI_API_KEY=sk-testsecret123456789012345"));

        String runId = createRun("fail with secret");
        JsonNode failed = waitForStatus(runId, "failed");
        assertThat(failed.path("error").asText())
            .contains("OPENAI_API_KEY=[REDACTED]")
            .doesNotContain("sk-testsecret123456789012345");

        MvcResult result = mockMvc.perform(get("/v1/runs/{runId}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        assertThat(result.getResponse().getContentAsString())
            .contains("run.failed")
            .contains("OPENAI_API_KEY=[REDACTED]")
            .doesNotContain("sk-testsecret123456789012345");
    }

    @Test
    void steerKnownRunningRunBuffersInput() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String runId = createRun("wait");
        waitForStatus(runId, "running");
        UUID controlSessionId = runService.get(runId).controlSessionId();

        mockMvc.perform(post("/v1/runs/{runId}/steer", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"focus on the API contract\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("hermes.run.steer"))
            .andExpect(jsonPath("$.accepted").value(true));

        assertThat(steerBuffer.hasPending(controlSessionId)).isTrue();
        blockingRun.complete();
        JsonNode status = waitForStatus(runId, "completed");
        assertThat(status.path("pending_steer").asText()).isEqualTo("focus on the API contract");
    }

    @Test
    void steerFallsBackFromEmptyInputToMessageLikeHermes() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String runId = createRun("wait");
        waitForStatus(runId, "running");
        UUID controlSessionId = runService.get(runId).controlSessionId();

        mockMvc.perform(post("/v1/runs/{runId}/steer", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "",
                      "message": "fallback message"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true));

        assertThat(steerBuffer.hasPending(controlSessionId)).isTrue();
        blockingRun.complete();
        JsonNode status = waitForStatus(runId, "completed");
        assertThat(status.path("pending_steer").asText()).isEqualTo("fallback message");
    }

    @Test
    void steerSkipsImageOnlyContentLikeHermes() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String runId = createRun("wait");
        waitForStatus(runId, "running");
        UUID controlSessionId = runService.get(runId).controlSessionId();

        mockMvc.perform(post("/v1/runs/{runId}/steer", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": [
                        {"type": "input_image", "image_url": "https://example.com/a.png"}
                      ]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("invalid_steer_input"));

        assertThat(steerBuffer.hasPending(controlSessionId)).isFalse();
        blockingRun.complete();
        waitForStatus(runId, "completed");
    }

    @Test
    void steerRejectsRunWaitingForApprovalLikeHermes() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String runId = createRun("wait for approval");
        waitForStatus(runId, "running");
        UUID controlSessionId = runService.get(runId).controlSessionId();
        approvalQueue.request(
            controlSessionId,
            new ToolCall("call_waiting", "terminal", "{\"cmd\":\"date\"}"),
            "Waiting run approval",
            Duration.ofSeconds(30)
        );
        waitForStatus(runId, "waiting_for_approval");

        mockMvc.perform(post("/v1/runs/{runId}/steer", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"please continue\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("run_not_accepting_steer"));

        assertThat(steerBuffer.hasPending(controlSessionId)).isFalse();
        blockingRun.complete();
        waitForStatus(runId, "completed");
    }

    @Test
    void approvalEndpointResolvesPendingApproval() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String runId = createRun("needs approval");
        waitForStatus(runId, "running");
        UUID controlSessionId = runService.get(runId).controlSessionId();
        approvalQueue.request(
            controlSessionId,
            new ToolCall("call_123", "terminal", "{\"cmd\":\"date\"}"),
            "Terminal execution requires approval",
            Duration.ofSeconds(30)
        );

        mockMvc.perform(post("/v1/runs/{runId}/approval", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"approve\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.object").value("hermes.run.approval_response"))
            .andExpect(jsonPath("$.choice").value("once"))
            .andExpect(jsonPath("$.resolved").value(1));

        assertThat(approvalQueue.isApproved(controlSessionId)).isTrue();
        blockingRun.complete();
        waitForStatus(runId, "completed");
    }

    @Test
    void approvalRequestEventRedactsToolArgumentsLikeHermes() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String runId = createRun("needs approval");
        waitForStatus(runId, "running");
        UUID controlSessionId = runService.get(runId).controlSessionId();
        approvalQueue.request(
            controlSessionId,
            new ToolCall(
                "call_secret",
                "terminal",
                "{\"cmd\":\"OPENAI_API_KEY=sk-approvalsecret123456789012345 npm test\"}"
            ),
            "Terminal execution uses TOKEN=sk-approvalreason123456789012345",
            Duration.ofSeconds(30)
        );
        waitForStatus(runId, "waiting_for_approval");

        MvcResult result = mockMvc.perform(get("/v1/runs/{runId}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        blockingRun.complete();
        waitForStatus(runId, "completed");
        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        assertThat(result.getResponse().getContentAsString())
            .contains("approval.request")
            .contains("OPENAI_API_KEY=[REDACTED]")
            .contains("TOKEN=[REDACTED]")
            .doesNotContain("sk-approvalsecret123456789012345")
            .doesNotContain("sk-approvalreason123456789012345");
    }

    @Test
    void approvalEndpointIsScopedToTargetRunNotSharedSession() throws Exception {
        BlockingRun blockingRun = blockRuntime();
        String firstRunId = createRun("first");
        String secondRunId = createRun("second");
        waitForStatus(firstRunId, "running");
        waitForStatus(secondRunId, "running");
        UUID firstControlSessionId = runService.get(firstRunId).controlSessionId();
        UUID secondControlSessionId = runService.get(secondRunId).controlSessionId();
        assertThat(firstControlSessionId).isNotEqualTo(secondControlSessionId);

        approvalQueue.request(
            firstControlSessionId,
            new ToolCall("call_first", "terminal", "{\"cmd\":\"date\"}"),
            "First run approval",
            Duration.ofSeconds(30)
        );

        mockMvc.perform(post("/v1/runs/{runId}/approval", secondRunId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"approve\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("approval_not_pending"));

        assertThat(approvalQueue.isPending(firstControlSessionId)).isTrue();

        mockMvc.perform(post("/v1/runs/{runId}/approval", firstRunId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"approve\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resolved").value(1));

        assertThat(approvalQueue.isApproved(firstControlSessionId)).isTrue();
        assertThat(approvalQueue.getPending(secondControlSessionId)).isNull();
        blockingRun.complete();
        waitForStatus(firstRunId, "completed");
        waitForStatus(secondRunId, "completed");
    }

    @Test
    void approvalAfterCompletedRunReturnsNotActiveLikeHermes() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));
        String runId = createRun("quick");
        waitForStatus(runId, "completed");

        mockMvc.perform(post("/v1/runs/{runId}/approval", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"choice\":\"approve\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("approval_not_active"));
    }

    @Test
    void eventsEndpointStreamsLifecycleEvents() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "streamed", List.of(), true));
        String runId = createRun("stream events");
        waitForStatus(runId, "completed");

        MvcResult result = mockMvc.perform(get("/v1/runs/{runId}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        assertThat(result.getResponse().getContentAsString())
            .contains("run.started")
            .contains("message.delta")
            .contains("\"delta\":\"streamed\"")
            .contains("run.completed");
    }

    @Test
    void eventsEndpointIsOneShotAfterCloseLikeHermes() throws Exception {
        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "streamed", List.of(), true));
        String runId = createRun("stream events once");
        waitForStatus(runId, "completed");

        MvcResult result = mockMvc.perform(get("/v1/runs/{runId}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

        mockMvc.perform(get("/v1/runs/{runId}/events", runId)
                .accept(MediaType.TEXT_EVENT_STREAM))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.message").value("Run not found: " + runId))
            .andExpect(jsonPath("$.error.code").value("run_not_found"));
    }

    @Test
    void unknownRunReturnsOpenAiErrorEnvelope() throws Exception {
        mockMvc.perform(get("/v1/runs/run_missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.code").value("run_not_found"));
    }

    @Test
    void conversationHistoryWithImageContentIsAcceptedAndPersisted() throws Exception {
        lenient().when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));

        mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "continue",
                      "conversation_history": [
                        {
                          "role": "user",
                          "content": [
                            {"type": "input_text", "text": "old question"},
                            {"type": "input_image", "image_url": "https://example.com/a.png"}
                          ]
                        }
                      ]
                    }
                    """))
            .andExpect(status().isAccepted());

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(openAiSessionService).persistHistory(eq(SESSION_ID), historyCaptor.capture());
        assertThat(historyCaptor.getValue()).hasSize(1);
        assertThat(historyCaptor.getValue().get(0).content())
            .isEqualTo("old question\n[image_url: https://example.com/a.png]");
        assertThat(historyCaptor.getValue().get(0).imageCount()).isEqualTo(1);
    }

    @Test
    void emptyConversationHistoryDoesNotOverridePreviousResponseLikeHermes() throws Exception {
        responseStore.put("resp_previous", new StoredResponse(
            Map.of("id", "resp_previous"),
            List.of(Message.user("old question"), Message.assistant("old answer", 1)),
            "Keep context.",
            SESSION_ID
        ), null);

        when(agentRuntimeService.runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class)))
            .thenReturn(new ChatResponseDto(SESSION_ID, "done", List.of(), true));

        MvcResult created = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "model": "gpt-run",
                      "previous_response_id": "resp_previous",
                      "conversation_history": [],
                      "input": "next question"
                    }
                    """))
            .andExpect(status().isAccepted())
            .andReturn();

        String runId = readRunId(created);
        waitForStatus(runId, "completed");

        verify(openAiSessionService).resolveRunSession(eq(SESSION_ID), isNull(), eq("gpt-run"));
        verify(openAiSessionService, never()).persistHistory(any(), any());
    }

    @Test
    void invalidConversationHistoryImageContentPartReturnsHermesErrorShape() throws Exception {
        mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "continue",
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
    }

    @Test
    void nonArrayConversationHistoryReturnsHermesErrorShape() throws Exception {
        mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "continue",
                      "conversation_history": {"role": "user", "content": "old"}
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
            .andExpect(jsonPath("$.error.message").value(
                "'conversation_history' must be an array of message objects"));
    }

    private BlockingRun blockRuntime() throws Exception {
        CountDownLatch enteredRuntime = new CountDownLatch(1);
        CountDownLatch releaseRuntime = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredRuntime.countDown();
            releaseRuntime.await(2, TimeUnit.SECONDS);
            return new ChatResponseDto(SESSION_ID, "released", List.of(), true);
        }).when(agentRuntimeService).runApiTurn(any(Session.class), anyString(), any(ModelRequestOptions.class));
        return new BlockingRun(enteredRuntime, releaseRuntime);
    }

    private String createRun(String input) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "input": "%s"
                    }
                    """.formatted(input)))
            .andExpect(status().isAccepted())
            .andReturn();
        return readRunId(result);
    }

    private String readRunId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("run_id").asText();
    }

    private JsonNode waitForStatus(String runId, String expectedStatus) throws Exception {
        return waitForStatus(runId, Set.of(expectedStatus));
    }

    private JsonNode waitForProfileStatus(String runId, String expectedStatus) throws Exception {
        JsonNode last = null;
        for (int i = 0; i < 100; i++) {
            MvcResult result = mockMvc.perform(get("/p/work/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn();
            last = objectMapper.readTree(result.getResponse().getContentAsString());
            if (expectedStatus.equals(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Run " + runId + " did not reach " + expectedStatus
            + "; last status was " + (last != null ? last.path("status").asText() : "<none>"));
    }

    private JsonNode waitForStatus(String runId, Set<String> expectedStatuses) throws Exception {
        JsonNode last = null;
        for (int i = 0; i < 100; i++) {
            MvcResult result = mockMvc.perform(get("/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn();
            last = objectMapper.readTree(result.getResponse().getContentAsString());
            if (expectedStatuses.contains(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Run " + runId + " did not reach " + expectedStatuses
            + "; last status was " + (last != null ? last.path("status").asText() : "<none>"));
    }

    private record BlockingRun(CountDownLatch entered, CountDownLatch releaseLatch) {
        void complete() {
            releaseLatch.countDown();
        }
    }
}
