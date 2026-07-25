package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundMessageProcessorTest {

    private static final String CHAT_ID = "123456";
    private static final String USER_ID = "789012";
    private static final String USERNAME = "jdoe";
    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String MODEL_PROVIDER = "openai-compatible";
    private static final String MODEL_NAME = "gpt-4";
    private static final String USER_TEXT = "Hello, agent";
    private static final String REPLY_TEXT = "Hi, user";

    private SessionRepository sessionRepository;
    private AgentRuntime agentRuntime;
    private GatewayRoutingService routingService;
    private ObjectProvider<GatewayRoutingService> routingServiceProvider;
    private AgentProperties properties;
    private InboundMessageProcessor processor;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        agentRuntime = mock(AgentRuntime.class);
        routingService = mock(GatewayRoutingService.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayRoutingService> provider = mock(ObjectProvider.class);
        routingServiceProvider = provider;
        when(routingServiceProvider.getIfAvailable()).thenReturn(routingService);

        properties = new AgentProperties();
        properties.getModel().setProvider(MODEL_PROVIDER);
        properties.getModel().setModelName(MODEL_NAME);

        processor = new InboundMessageProcessor(sessionRepository, agentRuntime, routingServiceProvider, properties);
    }

    @Test
    void acceptCreatesNewSessionWhenNoneExistsInvokesRuntimeAndSendsReply() {
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        when(sessionRepository.findByUserId(USER_ID)).thenReturn(null);

        SessionEntity savedEntity = new SessionEntity();
        savedEntity.setId(SESSION_ID);
        savedEntity.setUserId(USER_ID);
        savedEntity.setTitle("Telegram " + USERNAME);
        savedEntity.setModelProvider(MODEL_PROVIDER);
        savedEntity.setModelName(MODEL_NAME);
        savedEntity.setCreatedAt(Instant.EPOCH);
        savedEntity.setUpdatedAt(Instant.EPOCH);

        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(savedEntity);

        Session capturedSession = new Session(SESSION_ID, USER_ID, "Telegram " + USERNAME,
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));

        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-1", null)));

        processor.accept(event);

        ArgumentCaptor<SessionEntity> newEntityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(newEntityCaptor.capture());
        SessionEntity newEntity = newEntityCaptor.getValue();
        assertThat(newEntity.getUserId()).isEqualTo(USER_ID);
        assertThat(newEntity.getTitle()).isEqualTo("Telegram " + USERNAME);
        assertThat(newEntity.getModelProvider()).isEqualTo(MODEL_PROVIDER);
        assertThat(newEntity.getModelName()).isEqualTo(MODEL_NAME);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_TEXT), eq(List.of()));
        Session runtimeSession = sessionCaptor.getValue();
        assertThat(runtimeSession.userId()).isEqualTo(USER_ID);
        assertThat(runtimeSession.title()).isEqualTo("Telegram " + USERNAME);
        assertThat(runtimeSession.modelProvider()).isEqualTo(MODEL_PROVIDER);
        assertThat(runtimeSession.modelName()).isEqualTo(MODEL_NAME);

        verify(routingService).send(Platform.TELEGRAM, source, REPLY_TEXT);
    }

    @Test
    void acceptReusesExistingSessionByChatId() {
        String existingUserId = CHAT_ID;
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, null, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        SessionEntity existing = new SessionEntity();
        existing.setId(SESSION_ID);
        existing.setUserId(existingUserId);
        existing.setTitle("Telegram existinguser");
        existing.setModelProvider(MODEL_PROVIDER);
        existing.setModelName("existing-model");
        existing.setCreatedAt(Instant.EPOCH);
        existing.setUpdatedAt(Instant.EPOCH);

        when(sessionRepository.findByUserId(existingUserId)).thenReturn(existing);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-2", null)));

        processor.accept(event);

        verify(sessionRepository, never()).save(any(SessionEntity.class));

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_TEXT), eq(List.of()));
        Session runtimeSession = sessionCaptor.getValue();
        assertThat(runtimeSession.id()).isEqualTo(SESSION_ID);
        assertThat(runtimeSession.userId()).isEqualTo(existingUserId);
        assertThat(runtimeSession.title()).isEqualTo("Telegram existinguser");
        assertThat(runtimeSession.modelName()).isEqualTo("existing-model");

        verify(routingService).send(Platform.TELEGRAM, source, REPLY_TEXT);
    }

    @Test
    void acceptSkipsProcessingForUnauthorizedGatewaySource() {
        SessionSource source = new SessionSource(Platform.UNKNOWN, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        processor.accept(event);

        verify(sessionRepository, never()).findByUserId(any());
        verify(agentRuntime, never()).runTurn(any(), any(), any());
        verify(routingService, never()).send(any(), any(), any());
    }

    @Test
    void runtimeErrorStillTriesToSendErrorText() {
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        SessionEntity existing = new SessionEntity();
        existing.setId(SESSION_ID);
        existing.setUserId(USER_ID);
        existing.setTitle("Telegram " + USERNAME);
        existing.setModelProvider(MODEL_PROVIDER);
        existing.setModelName(MODEL_NAME);
        existing.setCreatedAt(Instant.EPOCH);
        existing.setUpdatedAt(Instant.EPOCH);

        when(sessionRepository.findByUserId(USER_ID)).thenReturn(existing);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(TurnResult.error("model timeout"));

        String expectedErrorText = "Error: model timeout";
        when(routingService.send(Platform.TELEGRAM, source, expectedErrorText))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-3", null)));

        processor.accept(event);

        verify(routingService).send(Platform.TELEGRAM, source, expectedErrorText);
    }

    @Test
    void sessionTitleIsDerivedFromUserName() {
        String customUsername = "alice_smith";
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, customUsername, customUsername);
        MessageEvent event = messageEvent(source, USER_TEXT);

        when(sessionRepository.findByUserId(USER_ID)).thenReturn(null);

        SessionEntity savedEntity = new SessionEntity();
        savedEntity.setId(SESSION_ID);
        savedEntity.setUserId(USER_ID);
        savedEntity.setTitle("Telegram " + customUsername);
        savedEntity.setModelProvider(MODEL_PROVIDER);
        savedEntity.setModelName(MODEL_NAME);
        savedEntity.setCreatedAt(Instant.EPOCH);
        savedEntity.setUpdatedAt(Instant.EPOCH);

        when(sessionRepository.save(any(SessionEntity.class))).thenReturn(savedEntity);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-4", null)));

        processor.accept(event);

        ArgumentCaptor<SessionEntity> entityCaptor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getTitle()).isEqualTo("Telegram " + customUsername);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_TEXT), eq(List.of()));
        assertThat(sessionCaptor.getValue().title()).isEqualTo("Telegram " + customUsername);
    }

    private MessageEvent messageEvent(SessionSource source, String text) {
        return new MessageEvent(
            "evt-1",
            source,
            MessageType.TEXT,
            text,
            List.of(),
            Map.of(),
            Instant.parse("2026-01-01T00:00:00Z")
        );
    }
}
