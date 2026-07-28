package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.WriteApprovalGate;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolCall;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
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

        agentRuntimeService = new AgentRuntimeService(
            agentRuntime,
            sessionRepository,
            messageRepository,
            sessionTitleService,
            memoryProvider,
            memoryRepository,
            writeApprovalGate,
            conversationCompressor,
            usageTracker,
            turnUsageCollector
        );
    }

    @Test
    void runTurnCreatesNewSessionWhenSessionIdIsNull() {
        ChatRequest request = new ChatRequest(null, USER_MESSAGE, null, null);

        SessionEntity savedEntity = newSessionEntity(SESSION_ID, USER_ID, "New chat");
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(savedEntity);

        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 1)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE))).thenReturn(result);

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

        verify(agentRuntime).runTurn(any(Session.class), eq(USER_MESSAGE));
        verify(sessionTitleService).maybeUpdateTitle(SESSION_ID, result.messages(), true);
    }

    @Test
    void runTurnLoadsExistingSessionByUuid() {
        ChatRequest request = new ChatRequest(EXISTING_SESSION_ID, USER_MESSAGE, null, null);

        SessionEntity existing = newSessionEntity(EXISTING_SESSION_ID, USER_ID, "Existing chat");
        existing.setModelProvider(MODEL_PROVIDER);
        existing.setModelName("gpt-4");
        when(sessionRepository.findById(EXISTING_SESSION_ID)).thenReturn(Optional.of(existing));

        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 2)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE))).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runTurn(request);

        assertThat(response.sessionId()).isEqualTo(EXISTING_SESSION_ID);
        assertThat(response.content()).isEqualTo(ASSISTANT_REPLY);

        verify(sessionRepository, never()).save(any(SessionEntity.class));
        verify(agentRuntime).runTurn(any(Session.class), eq(USER_MESSAGE));
        verify(sessionTitleService).maybeUpdateTitle(EXISTING_SESSION_ID, result.messages(), false);
    }

    @Test
    void runDelegateCreatesSessionWithDelegationDepthMetadata() {
        ChatRequest request = new ChatRequest(null, USER_MESSAGE, 3, null);

        SessionEntity savedEntity = newSessionEntity(SESSION_ID, USER_ID, "New chat");
        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(savedEntity);

        TurnResult result = new TurnResult(
            List.of(Message.user(USER_MESSAGE), Message.assistant(ASSISTANT_REPLY, 1)),
            true,
            null
        );
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE))).thenReturn(result);

        ChatResponseDto response = agentRuntimeService.runDelegate(request);

        assertThat(response.sessionId()).isEqualTo(SESSION_ID);
        assertThat(response.content()).isEqualTo(ASSISTANT_REPLY);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_MESSAGE));
        assertThat(sessionCaptor.getValue().metadata()).containsEntry("delegation_depth", "3");

        verify(sessionRepository).save(any(SessionEntity.class));
        verify(sessionTitleService, never()).maybeUpdateTitle(any(UUID.class), any(List.class), eq(true));
    }

    @Test
    void persistsUserAssistantAndToolMessages() {
        ChatRequest request = new ChatRequest(EXISTING_SESSION_ID, USER_MESSAGE, null, null);

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
        when(agentRuntime.runTurn(any(Session.class), eq(USER_MESSAGE))).thenReturn(result);

        agentRuntimeService.runTurn(request);

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
    }

    @Test
    void throwsExceptionWhenSessionIdUnknown() {
        ChatRequest request = new ChatRequest(UNKNOWN_SESSION_ID, USER_MESSAGE, null, null);
        when(sessionRepository.findById(UNKNOWN_SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> agentRuntimeService.runTurn(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Session not found: " + UNKNOWN_SESSION_ID);

        verify(agentRuntime, never()).runTurn(any(), any());
        verify(messageRepository, never()).save(any());
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
