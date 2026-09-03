package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.core.skill.SkillBundleService;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import org.mapstruct.factory.Mappers;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.api.mapper.OpenAiMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeServiceTest {

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID EXISTING_SESSION_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID UNKNOWN_SESSION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String USER_ID = "user-1";
    private static final String MODEL_PROVIDER = "openai-compatible";
    private static final String MODEL_NAME = "";
    private static final String USER_MESSAGE = "Hello, agent";
    private static final String ASSISTANT_REPLY = "Hi, how can I help?";
    private static final String TOOL_RESULT = "Paris";

    private AgentRuntime agentRuntime;
    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private SessionTitleService sessionTitleService;
    private MemoryProvider memoryProvider;
    private MemoryRepository memoryRepository;
    private WriteApprovalGate writeApprovalGate;
    private ConversationCompressor conversationCompressor;
    private UsageTracker usageTracker;
    private TurnUsageCollector turnUsageCollector;
    private AgentRuntimeService agentRuntimeService;
    private AgentProperties properties;
    private SkillBundleService skillBundleService;
    private com.azhukov.agent.core.skill.SkillManager skillManager;
    private com.azhukov.agent.client.mcp.McpLifecycleManager mcpLifecycleManager;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        agentRuntime = mock(AgentRuntime.class);
        sessionRepository = mock(SessionRepository.class);
        messageRepository = mock(MessageRepository.class);
        sessionTitleService = mock(SessionTitleService.class);
        memoryProvider = mock(MemoryProvider.class);
        memoryRepository = mock(MemoryRepository.class);
        writeApprovalGate = mock(WriteApprovalGate.class);
        conversationCompressor = mock(ConversationCompressor.class);
        usageTracker = mock(UsageTracker.class);
        turnUsageCollector = mock(TurnUsageCollector.class);
        properties = mock(AgentProperties.class);
        AgentProperties.ModelProperties modelProps = mock(AgentProperties.ModelProperties.class);
        when(modelProps.getModelName()).thenReturn(MODEL_NAME);
        when(properties.getModel()).thenReturn(modelProps);
        skillBundleService = mock(SkillBundleService.class);
        skillManager = mock(com.azhukov.agent.core.skill.SkillManager.class);
        mcpLifecycleManager = mock(com.azhukov.agent.client.mcp.McpLifecycleManager.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // TransactionTemplate executes the callback immediately
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });

        agentRuntimeService = new AgentRuntimeService(
            agentRuntime,
            org.mockito.Mockito.mock(com.azhukov.agent.persistence.repository.BackgroundJobRepository.class),
            sessionRepository,
            messageRepository,
            sessionTitleService,
            memoryProvider,
            memoryRepository,
            writeApprovalGate,
            conversationCompressor,
            usageTracker,
            turnUsageCollector,
            properties,
            Mappers.getMapper(SessionEntityMapper.class),
            Mappers.getMapper(MessageMapper.class),
            Mappers.getMapper(DomainDtoMapper.class),
            skillBundleService,
            skillManager,
            mcpLifecycleManager,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            new RuntimeConfigService(),
            transactionTemplate,
            new AgentSessionResolver(sessionRepository, Mappers.getMapper(SessionEntityMapper.class), transactionTemplate, messageRepository, mock(com.azhukov.agent.core.agent.SessionLineageService.class)),
            new CliStateApplier(),
            new SessionCompressionHelper(messageRepository, Mappers.getMapper(MessageMapper.class), conversationCompressor, sessionRepository),
            mock(com.azhukov.agent.core.context.ContextCompressor.class),
            mock(com.azhukov.agent.core.metadata.ModelMetadataService.class), null,
            null, null
        );
    }

    @Test
    void runTurnCreatesNewSessionWhenSessionIdIsNull() {
        ChatRequest request = ChatRequest.simple(null, USER_MESSAGE, null, null);

        SessionEntity savedEntity = newSessionEntity(SESSION_ID, USER_ID, "New chat");
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(savedEntity);

        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 1)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runTurn(request);

        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.content()).isEqualTo(ASSISTANT_REPLY);
        assertThat(response.completed()).isTrue();

        ArgumentCaptor<SessionEntity> sessionCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        SessionEntity created = sessionCaptor.getValue();
        assertThat(created.getUserId()).isEqualTo(USER_ID);
        assertThat(created.getModelProvider()).isEqualTo(MODEL_PROVIDER);
        assertThat(created.getModelName()).isEqualTo(MODEL_NAME);
        assertThat(created.getTitle()).isEqualTo("New chat");

        verify(agentRuntime).runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any());
        verify(sessionTitleService).maybeUpdateTitle(SESSION_ID, result.messages(), true);
    }

    @Test
    void runTurnLoadsExistingSessionByUuid() {
        ChatRequest request = ChatRequest.simple(EXISTING_SESSION_ID, USER_MESSAGE, null, null);

        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "Existing chat");
        existing.setModelProvider(MODEL_PROVIDER);
        existing.setModelName("gpt-4");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));

        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 2)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runTurn(request);

        assertThat(response.sessionId()).isEqualTo(EXISTING_SESSION_ID);
        assertThat(response.content()).isEqualTo(ASSISTANT_REPLY);

        verify(sessionRepository, never()).save(any(SessionEntity.class));
        verify(agentRuntime).runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any());
        verify(sessionTitleService).maybeUpdateTitle(EXISTING_SESSION_ID, result.messages(), false);
    }

    @Test
    void runTurnPassesHermesSessionRuntimeOptionsAndSystemPromptOverride() {
        ChatRequest request = new ChatRequest(
            EXISTING_SESSION_ID,
            USER_MESSAGE,
            null,
            null,
            "MiniMax-M3",
            "minimax",
            "https://minimax.example/v1",
            "sk-route-secret",
            "medium",
            true,
            true,
            "concise",
            null,
            null,
            null,
            null,
            555,
            "Stay in session scope.",
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "Existing chat");
        existing.setModelProvider(MODEL_PROVIDER);
        existing.setModelName("gpt-4");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));
        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 2)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        agentRuntimeService.runTurn(request);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_MESSAGE), eq(List.of()), optionsCaptor.capture());
        assertThat(sessionCaptor.getValue().metadata())
            .containsEntry("system_prompt_override", "Stay in session scope.");
        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo("MiniMax-M3");
        assertThat(options.provider()).isEqualTo("minimax");
        assertThat(options.baseUrl()).isEqualTo("https://minimax.example/v1");
        assertThat(options.apiKey()).isEqualTo("sk-route-secret");
        assertThat(options.reasoningEffort()).isEqualTo("medium");
        assertThat(options.fastMode()).isTrue();
        assertThat(options.voiceMode()).isTrue();
        assertThat(options.personality()).isEqualTo("concise");
        assertThat(options.maxCompletionTokens()).isEqualTo(555);
    }

    @Test
    void runTurnResolvesStoredSessionModelRouteAliasLikeHermes() {
        AgentProperties.ApiProperties api = new AgentProperties.ApiProperties();
        AgentProperties.ApiProperties.ModelRouteProperties route =
            new AgentProperties.ApiProperties.ModelRouteProperties();
        route.setModel("route/model");
        route.setProvider("openrouter");
        route.setBaseUrl("https://openrouter.example/v1");
        route.setApiKey("sk-route-secret");
        api.getModelRoutes().put("alias", route);
        when(properties.getApi()).thenReturn(api);

        ChatRequest request = ChatRequest.simple(EXISTING_SESSION_ID, USER_MESSAGE, null, null);
        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "Route alias chat");
        existing.setModelName("alias");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));
        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 2)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runTurn(request);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_MESSAGE), eq(List.of()), optionsCaptor.capture());
        ModelRequestOptions options = optionsCaptor.getValue();
        assertThat(options.modelName()).isEqualTo("route/model");
        assertThat(options.provider()).isEqualTo("openrouter");
        assertThat(options.baseUrl()).isEqualTo("https://openrouter.example/v1");
        assertThat(options.apiKey()).isEqualTo("sk-route-secret");
        assertThat(sessionCaptor.getValue().modelName()).isEqualTo("route/model");
        assertThat(sessionCaptor.getValue().modelProvider()).isEqualTo("openrouter");
        assertThat(response.modelUsed()).isEqualTo("route/model");
    }

    @Test
    void runTurnUsesStoredRawSessionModelWhenNoRequestModelLikeHermes() {
        ChatRequest request = ChatRequest.simple(EXISTING_SESSION_ID, USER_MESSAGE, null, null);
        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "Raw model chat");
        existing.setModelName("gpt-4.1");
        existing.setModelProvider("openai-compatible");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));
        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 2)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runTurn(request);

        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntime).runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().modelName()).isEqualTo("gpt-4.1");
        assertThat(optionsCaptor.getValue().provider()).isEqualTo("openai-compatible");
        assertThat(response.modelUsed()).isEqualTo("gpt-4.1");
    }

    @Test
    void runTurnTreatsStoredAdvertisedApiModelAsGlobalDefaultLikeHermes() {
        AgentProperties.ApiProperties api = new AgentProperties.ApiProperties();
        api.setModelName("hermes-agent");
        when(properties.getApi()).thenReturn(api);
        when(properties.getModel().getModelName()).thenReturn("global/model");

        ChatRequest request = ChatRequest.simple(EXISTING_SESSION_ID, USER_MESSAGE, null, null);
        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "Poisoned alias chat");
        existing.setModelName("hermes-agent");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));
        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 2)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runTurn(request);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        ArgumentCaptor<ModelRequestOptions> optionsCaptor = ArgumentCaptor.forClass(ModelRequestOptions.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_MESSAGE), eq(List.of()), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().modelName()).isNull();
        assertThat(sessionCaptor.getValue().modelName()).isEqualTo("global/model");
        assertThat(response.modelUsed()).isEqualTo("global/model");
    }

    @Test
    void runApiTurnWithImageMessagePreservesImageCountForRuntime() {
        Session session = new Session(EXISTING_SESSION_ID, USER_ID, "API run", MODEL_PROVIDER, MODEL_NAME, null, Map.of(), null);
        Message input = Message.userWithImages("Describe.\n[image_url: https://example.com/a.png]", 1);
        TurnResult result = new TurnResult(
            List.of(input, Message.assistant(ASSISTANT_REPLY, 1)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), any(Message.class), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runApiTurn(session, input, ModelRequestOptions.empty());

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(agentRuntime).runTurn(any(Session.class), messageCaptor.capture(), eq(List.of()), any());
        assertThat(messageCaptor.getValue().content()).isEqualTo(input.content());
        assertThat(messageCaptor.getValue().imageCount()).isEqualTo(1);
        assertThat(response.content()).isEqualTo(ASSISTANT_REPLY);
    }

    @Test
    void runDelegateCreatesSessionWithDelegationDepthMetadata() {
        ChatRequest request = ChatRequest.simple(null, USER_MESSAGE, 3, null);

        SessionEntity savedEntity = newSessionEntity(SESSION_ID, USER_ID, "New chat");
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(savedEntity);

        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 1)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runDelegate(request);

        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.content()).isEqualTo(ASSISTANT_REPLY);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_MESSAGE), eq(List.of()), any());
        assertThat(sessionCaptor.getValue().metadata()).containsEntry("delegation_depth", "3");

        verify(sessionRepository).save(any(SessionEntity.class));
        verify(sessionTitleService, never()).maybeUpdateTitle(any(UUID.class), any(List.class), eq(true));
    }

    @Test
    void persistsUserAssistantAndToolMessages() {
        ChatRequest request = ChatRequest.simple(EXISTING_SESSION_ID, USER_MESSAGE, null, null);

        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "Tool chat");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));

        ToolCall toolCall = new ToolCall("call-1", "weather", "{\"city\":\"Paris\"}");
        Message assistantWithTool = Message.assistantWithToolCalls(
            "Let me check the weather.",
            List.of(toolCall),
            1
        );
        Message toolResponse = Message.toolResult("call-1", TOOL_RESULT, 1);
        Message finalAssistant = Message.assistant("The weather in Paris is sunny.", 1);

        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), assistantWithTool, toolResponse, finalAssistant),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runTurn(request);

        ArgumentCaptor<MessageEntity> messageCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, times(4)).save(messageCaptor.capture());

        List<MessageEntity> saved = messageCaptor.getAllValues();
        assertThat(saved).hasSize(4);

        assertThat(saved.get(0).getSessionId()).isEqualTo(EXISTING_SESSION_ID);
        assertThat(saved.get(0).getRole()).isEqualTo("user");
        assertThat(saved.get(0).getContent()).isEqualTo(USER_MESSAGE);

        assertThat(saved.get(1).getRole()).isEqualTo("assistant");
        assertThat(saved.get(1).getContent()).isEqualTo("Let me check the weather.");
        assertThat(saved.get(1).getToolCallName()).isEqualTo("weather");
        assertThat(saved.get(1).getToolCallArguments()).isEqualTo("{\"city\":\"Paris\"}");

        assertThat(saved.get(2).getRole()).isEqualTo("tool");
        assertThat(saved.get(2).getContent()).isEqualTo(TOOL_RESULT);
        assertThat(saved.get(2).getToolCallId()).isEqualTo("call-1");

        assertThat(saved.get(3).getRole()).isEqualTo("assistant");
        assertThat(saved.get(3).getContent()).isEqualTo("The weather in Paris is sunny.");

        assertThat(response.messages()).hasSize(3);
        assertThat(response.messages().get(0))
            .containsEntry("role", "assistant")
            .containsEntry("content", "Let me check the weather.");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls =
            (List<Map<String, Object>>) response.messages().get(0).get("tool_calls");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.get(0)).containsEntry("id", "call-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
        assertThat(function)
            .containsEntry("name", "weather")
            .containsEntry("arguments", "{\"city\":\"Paris\"}");
        assertThat(response.messages().get(1))
            .containsEntry("role", "tool")
            .containsEntry("tool_call_id", "call-1")
            .containsEntry("tool_name", "weather")
            .containsEntry("content", TOOL_RESULT);
        assertThat(response.messages().get(2))
            .containsEntry("role", "assistant")
            .containsEntry("content", "The weather in Paris is sunny.");
    }

    @Test
    void createsNewSessionWhenSessionIdNotFoundInBackend() {
        ChatRequest request = ChatRequest.simple(UNKNOWN_SESSION_ID, USER_MESSAGE, null, null);
        when(sessionRepository.findById(UNKNOWN_SESSION_ID)).thenReturn(Optional.empty());
        SessionEntity savedEntity = newSessionEntity(SESSION_ID, USER_ID, "New chat");
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(savedEntity);

        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 1)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any())).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runTurn(request);

        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.content()).isEqualTo(ASSISTANT_REPLY);
        verify(sessionRepository).save(any(SessionEntity.class));
        verify(agentRuntime).runTurn(any(Session.class), eq(USER_MESSAGE), eq(List.of()), any());
    }

    @Test
    void installBundleDelegatesToSkillBundleService() {
        agentRuntimeService.installBundle("my-bundle");
        verify(skillBundleService).install("my-bundle");
    }

    @Test
    void uninstallBundleDelegatesToSkillBundleService() {
        agentRuntimeService.uninstallBundle("my-bundle");
        verify(skillBundleService).uninstall("my-bundle");
    }

    @Test
    void switchModelCanPersistSessionModelOptions() {
        SessionEntity entity = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "Runtime lock");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(entity));
        when(sessionRepository.save(entity)).thenReturn(entity);

        agentRuntimeService.switchModel(EXISTING_SESSION_ID, "gpt-5", "browser", Map.of(
            "reasoning_effort", "high",
            "fast_mode", true,
            "max_completion_tokens", 2048
        ));

        assertThat(entity.getModelName()).isEqualTo("gpt-5");
        assertThat(entity.getModelProvider()).isEqualTo("browser");
        assertThat(entity.getCliStateValue("reasoningEffort")).isEqualTo("high");
        assertThat(entity.getCliStateValue("fastMode")).isEqualTo("true");
        assertThat(entity.getCliStateValue("maxTokens")).isEqualTo("2048");
        verify(sessionRepository).save(entity);
    }

    @Test
    void branchSessionPersistsLineageAndMarksSourceAsBranched() {
        SessionEntity source = newSessionEntity(SESSION_ID, USER_ID, "Parent chat");
        source.setSource("api_server");
        source.setPreview("Parent preview");
        source.setMessageCount(99);
        source.setSystemPrompt("Stay focused.");

        MessageEntity parentMessage = new MessageEntity();
        parentMessage.setSessionId(SESSION_ID);
        parentMessage.setRole("user");
        parentMessage.setContent("parent");
        parentMessage.setTurnIndex(1);
        parentMessage.setCreatedAt(Instant.parse("2026-08-28T10:00:00Z"));
        parentMessage.setActive(false);
        parentMessage.setCompacted(true);

        MessageEntity assistantMessage = new MessageEntity();
        assistantMessage.setSessionId(SESSION_ID);
        assistantMessage.setRole("assistant");
        assistantMessage.setToolCallId("call-1");
        assistantMessage.setToolCallName("web_search");
        assistantMessage.setToolCallArguments("{\"query\":\"java\"}");
        assistantMessage.setToolCalls("""
            [{"id":"call-1","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"java\\"}"}},
             {"id":"call-2","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]
            """);
        assistantMessage.setTurnIndex(2);
        assistantMessage.setImageCount(1);
        assistantMessage.setCreatedAt(Instant.parse("2026-08-28T10:01:00Z"));
        assistantMessage.setActive(true);
        assistantMessage.setCompacted(false);

        MessageEntity toolMessage = new MessageEntity();
        toolMessage.setSessionId(SESSION_ID);
        toolMessage.setRole("tool");
        toolMessage.setContent("file content");
        toolMessage.setToolCallId("call-2");
        toolMessage.setToolCallName("read_file");
        toolMessage.setTurnIndex(2);
        toolMessage.setCreatedAt(Instant.parse("2026-08-28T10:02:00Z"));

        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(source));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(parentMessage, assistantMessage, toolMessage));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> {
            SessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(EXISTING_SESSION_ID);
            }
            return entity;
        });

        var branch = agentRuntimeService.branchSession(SESSION_ID, "Forked chat");

        assertThat(branch.id()).isEqualTo(EXISTING_SESSION_ID);
        assertThat(branch.title()).isEqualTo("Forked chat");
        assertThat(branch.parentSessionId()).isEqualTo(SESSION_ID);

        ArgumentCaptor<SessionEntity> sessionCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository, times(2)).save(sessionCaptor.capture());
        List<SessionEntity> savedSessions = sessionCaptor.getAllValues();
        assertThat(savedSessions.get(0).getParentSessionId()).isEqualTo(SESSION_ID);
        assertThat(savedSessions.get(0).getSource()).isEqualTo("api_server");
        assertThat(savedSessions.get(0).getSystemPrompt()).isEqualTo("Stay focused.");
        assertThat(savedSessions.get(0).getMessageCount()).isEqualTo(2);
        assertThat(savedSessions.get(0).getPreview()).isEqualTo("Parent preview");
        assertThat(savedSessions.get(1).getEndReason()).isEqualTo("branched");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<MessageEntity>> messagesCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(messageRepository).saveAll(messagesCaptor.capture());
        java.util.List<MessageEntity> copiedMessages = new java.util.ArrayList<>();
        messagesCaptor.getValue().forEach(copiedMessages::add);
        assertThat(copiedMessages).hasSize(2);
        assertThat(copiedMessages.get(0).getSessionId()).isEqualTo(EXISTING_SESSION_ID);
        assertThat(copiedMessages.get(0).getActive()).isTrue();
        assertThat(copiedMessages.get(0).getCompacted()).isFalse();
        assertThat(copiedMessages.get(0).getToolCallName()).isEqualTo("web_search");
        assertThat(copiedMessages.get(0).getToolCalls())
            .contains("\"id\":\"call-1\"")
            .contains("\"id\":\"call-2\"")
            .contains("\"name\":\"read_file\"");
        assertThat(copiedMessages.get(0).getImageCount()).isEqualTo(1);
        assertThat(copiedMessages.get(1).getToolCallId()).isEqualTo("call-2");
        assertThat(copiedMessages.get(1).getToolCallName()).isEqualTo("read_file");
    }

    @Test
    void branchSessionCanUseRequestedBranchId() {
        SessionEntity source = newSessionEntity(SESSION_ID, USER_ID, "Parent chat");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(source));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID)).thenReturn(List.of());
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var branch = agentRuntimeService.branchSession(SESSION_ID, UNKNOWN_SESSION_ID, "Requested fork");

        assertThat(branch.id()).isEqualTo(UNKNOWN_SESSION_ID);
        ArgumentCaptor<SessionEntity> sessionCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository, times(2)).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getAllValues().get(0).getId()).isEqualTo(UNKNOWN_SESSION_ID);
        assertThat(sessionCaptor.getAllValues().get(0).getParentSessionId()).isEqualTo(SESSION_ID);
    }

    // ── BUG 1: getContext() must populate goal / goalPaused / subgoals ──

    @Test
    void getContextReturnsGoalFieldsFromSessionCliState() {
        SessionEntity entity = newSessionEntity(SESSION_ID, USER_ID, "goal session");
        entity.setCliStateValue("goal", "fix all bugs");
        entity.setCliStateValue("goalPaused", "false");
        entity.setCliStateValue("subgoals", "bug1\nbug2\nbug3");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of());

        com.azhukov.agent.api.dto.ContextInfoDto ctx = agentRuntimeService.getContext(SESSION_ID);

        assertThat(ctx.goal()).isEqualTo("fix all bugs");
        assertThat(ctx.goalPaused()).isFalse();
        assertThat(ctx.subgoals()).isEqualTo("bug1\nbug2\nbug3");
    }

    @Test
    void getContextReturnsNullGoalFieldsWhenNotSet() {
        SessionEntity entity = newSessionEntity(SESSION_ID, USER_ID, "no-goal session");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of());

        com.azhukov.agent.api.dto.ContextInfoDto ctx = agentRuntimeService.getContext(SESSION_ID);

        assertThat(ctx.goal()).isNull();
        assertThat(ctx.goalPaused()).isNull();
        assertThat(ctx.subgoals()).isNull();
    }

    @Test
    void getContextReturnsNullGoalFieldsWhenSessionNotFound() {
        when(sessionRepository.findById(UNKNOWN_SESSION_ID)).thenReturn(Optional.empty());
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(UNKNOWN_SESSION_ID))
            .thenReturn(List.of());

        com.azhukov.agent.api.dto.ContextInfoDto ctx = agentRuntimeService.getContext(UNKNOWN_SESSION_ID);

        assertThat(ctx.goal()).isNull();
        assertThat(ctx.goalPaused()).isNull();
        assertThat(ctx.subgoals()).isNull();
    }

    @Test
    void getContextGoalPausedTrueWhenCliStateSaysTrue() {
        SessionEntity entity = newSessionEntity(SESSION_ID, USER_ID, "paused goal");
        entity.setCliStateValue("goal", "fix all bugs");
        entity.setCliStateValue("goalPaused", "true");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of());

        com.azhukov.agent.api.dto.ContextInfoDto ctx = agentRuntimeService.getContext(SESSION_ID);

        assertThat(ctx.goal()).isEqualTo("fix all bugs");
        assertThat(ctx.goalPaused()).isTrue();
    }

    @Test
    void getContextCountsOnlyActiveMessagesAfterCompactionArchive() {
        SessionEntity entity = newSessionEntity(SESSION_ID, USER_ID, "active context");
        MessageEntity archived = messageEntity("user", "archived text", 1);
        archived.setActive(false);
        archived.setCompacted(true);
        archived.setToolCallName("archived_tool");
        MessageEntity live = messageEntity("tool", "live result", 2);
        live.setToolCallName("web_search");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(entity));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(archived, live));

        com.azhukov.agent.api.dto.ContextInfoDto ctx = agentRuntimeService.getContext(SESSION_ID);

        assertThat(ctx.messageCount()).isEqualTo(1);
        assertThat(ctx.toolsUsed()).containsExactly("web_search");
        assertThat(ctx.tokenEstimate()).isEqualTo("live result".length() / 4);
    }

    @Test
    void resetSessionBulkDeletesMessagesWithoutLoadingTranscriptLikeHermes() {
        agentRuntimeService.resetSession(SESSION_ID);

        verify(messageRepository).deleteBySessionId(SESSION_ID);
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(SESSION_ID);
        verify(messageRepository, never()).deleteAll(any());
    }

    @Test
    void undoTurnsIgnoresArchivedCompactionRows() {
        MessageEntity archived = messageEntity("user", "archived text", 9);
        archived.setActive(false);
        archived.setCompacted(true);
        MessageEntity live = messageEntity("assistant", "live answer", 2);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(SESSION_ID))
            .thenReturn(List.of(archived, live));

        int deleted = agentRuntimeService.undoTurns(SESSION_ID, 1);

        assertThat(deleted).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MessageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).deleteAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(live);
    }

    @Test
    void runBackgroundUsesProvidedSessionIdWhenPresent() {
        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "attached cron");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));
        TurnResult result = new TurnResult(
            List.of(Message.user("cron prompt"), Message.assistant("done", 1)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq("cron prompt"))).thenReturn(result);

        String sessionId = agentRuntimeService.runBackground(
            "cron prompt", EXISTING_SESSION_ID.toString(), true);

        assertThat(sessionId).isEqualTo(EXISTING_SESSION_ID.toString());
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq("cron prompt"));
        assertThat(sessionCaptor.getValue().id()).isEqualTo(EXISTING_SESSION_ID);
        assertThat(sessionCaptor.getValue().metadata()).containsEntry("skip_background_review", "true");
        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    private SessionEntity newSessionEntity(UUID id, String userId, String title) {
        SessionEntity entity = new SessionEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setModelProvider(MODEL_PROVIDER);
        entity.setModelName(MODEL_NAME);
        entity.setCreatedAt(Instant.EPOCH);
        entity.setUpdatedAt(Instant.EPOCH);
        return entity;
    }

    private MessageEntity messageEntity(String role, String content, int turnIndex) {
        MessageEntity entity = new MessageEntity();
        entity.setSessionId(SESSION_ID);
        entity.setRole(role);
        entity.setContent(content);
        entity.setTurnIndex(turnIndex);
        entity.setCreatedAt(Instant.EPOCH);
        return entity;
    }
}
