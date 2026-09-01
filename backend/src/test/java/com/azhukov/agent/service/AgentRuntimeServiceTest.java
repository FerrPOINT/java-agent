package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.CliStateApplier;
import com.azhukov.agent.core.agent.AgentSessionResolver;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
            new SessionCompressionHelper(messageRepository, Mappers.getMapper(MessageMapper.class), conversationCompressor, org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class, org.mockito.Mockito.RETURNS_SELF)),
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

        agentRuntimeService.runTurn(request);

        ArgumentCaptor<java.util.List<MessageEntity>> messageCaptor = ArgumentCaptor.forClass(java.util.List.class);
        verify(messageRepository).saveAll(messageCaptor.capture());

        List<MessageEntity> saved = messageCaptor.getValue();
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
    void concurrentSameSessionRequestsSerializePersistenceAndRuntime() throws Exception {
        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "concurrent chat");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));
        TurnResult first = new TurnResult(List.of(
            Message.user("first"), Message.assistant("FIRST", 1)), true, null);
        TurnResult second = new TurnResult(List.of(
            Message.user("second"), Message.assistant("SECOND", 2)), true, null);
        CountDownLatch firstRuntimeEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRuntime = new CountDownLatch(1);
        AtomicInteger concurrentRuntimeCalls = new AtomicInteger();
        AtomicInteger maxConcurrentRuntimeCalls = new AtomicInteger();
        when(agentRuntime.runTurn(any(Session.class), anyString(), eq(List.of()), any()))
            .thenAnswer(invocation -> {
                int active = concurrentRuntimeCalls.incrementAndGet();
                maxConcurrentRuntimeCalls.accumulateAndGet(active, Math::max);
                try {
                    if ("first".equals(invocation.getArgument(1))) {
                        firstRuntimeEntered.countDown();
                        releaseFirstRuntime.await(5, TimeUnit.SECONDS);
                        return first;
                    }
                    return second;
                } finally {
                    concurrentRuntimeCalls.decrementAndGet();
                }
            });

        ChatResponseDto[] responses = new ChatResponseDto[2];
        Thread firstThread = new Thread(() -> responses[0] = agentRuntimeService.runTurn(
            ChatRequest.simple(EXISTING_SESSION_ID, "first", null, null)));
        Thread secondThread = new Thread(() -> responses[1] = agentRuntimeService.runTurn(
            ChatRequest.simple(EXISTING_SESSION_ID, "second", null, null)));

        firstThread.start();
        assertThat(firstRuntimeEntered.await(5, TimeUnit.SECONDS)).isTrue();
        secondThread.start();
        Thread.sleep(100);
        assertThat(maxConcurrentRuntimeCalls.get()).isEqualTo(1);
        releaseFirstRuntime.countDown();
        firstThread.join(5_000);
        secondThread.join(5_000);

        assertThat(responses[0].content()).isEqualTo("FIRST");
        assertThat(responses[1].content()).isEqualTo("SECOND");
        assertThat(maxConcurrentRuntimeCalls.get()).isEqualTo(1);
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
}
