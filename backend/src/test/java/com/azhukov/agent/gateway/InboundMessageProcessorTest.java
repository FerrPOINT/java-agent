package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.TurnResult;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.service.MessagePersistenceService;
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
import static org.mockito.Mockito.timeout;

class InboundMessageProcessorTest {

    private static final String CHAT_ID = "123456";
    private static final String USER_ID = "789012";
    private static final String USERNAME = "jdoe";
    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String MODEL_PROVIDER = "openai-compatible";
    private static final String MODEL_NAME = "gpt-4";
    private static final String USER_TEXT = "Hello, agent";
    private static final String REPLY_TEXT = "Hi, user";

    private SessionResolver sessionResolver;
    private AgentRuntime agentRuntime;
    private GatewayRoutingService routingService;
    private ObjectProvider<GatewayRoutingService> routingServiceProvider;
    private MessagePersistenceService messagePersistenceService;
    private AgentProperties agentProperties;
    private InboundMessageProcessor processor;

    @BeforeEach
    void setUp() {
        sessionResolver = mock(SessionResolver.class);
        agentRuntime = mock(AgentRuntime.class);
        routingService = mock(GatewayRoutingService.class);
        messagePersistenceService = mock(MessagePersistenceService.class);
        agentProperties = new AgentProperties();

        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayRoutingService> provider = mock(ObjectProvider.class);
        routingServiceProvider = provider;
        when(routingServiceProvider.getIfAvailable()).thenReturn(routingService);

        processor = new InboundMessageProcessor(sessionResolver, agentRuntime, routingServiceProvider, messagePersistenceService, null, agentProperties, new com.azhukov.agent.core.agent.SteerBuffer(), null);
    }

    @Test
    void acceptCreatesNewSessionWhenNoneExistsInvokesRuntimeAndSendsReply() {
        agentProperties.getGateway().getTelegram().setAllowByDefault(true);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, USER_ID, "Telegram " + USERNAME,
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);

        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));

        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-1", null)));

        processor.accept(event);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_TEXT), eq(List.of()));
        Session runtimeSession = sessionCaptor.getValue();
        assertThat(runtimeSession.userId()).isEqualTo(USER_ID);
        assertThat(runtimeSession.title()).isEqualTo("Telegram " + USERNAME);

        verify(routingService).send(Platform.TELEGRAM, source, REPLY_TEXT);
    }

    @Test
    void acceptQueuesLateSteerReturnedByRuntimeForNextTurn() {
        agentProperties.getGateway().getTelegram().setAllowByDefault(true);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);
        Session session = new Session(SESSION_ID, USER_ID, "Telegram " + USERNAME,
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(session);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null, "do the late thing"));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-late", null)));

        processor.accept(event);

        // The result handoff is immediately drained through the normal queue path.
        verify(agentRuntime, timeout(1_000)).runTurn(any(Session.class), eq("do the late thing"), eq(List.of()));
    }

    @Test
    void acceptReusesExistingSessionByChatId() {
        agentProperties.getGateway().getTelegram().setAllowByDefault(true);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, null, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, CHAT_ID, "Telegram existinguser",
            MODEL_PROVIDER, "existing-model", null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);

        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-2", null)));

        processor.accept(event);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_TEXT), eq(List.of()));
        Session runtimeSession = sessionCaptor.getValue();
        assertThat(runtimeSession.id()).isEqualTo(SESSION_ID);
        assertThat(runtimeSession.userId()).isEqualTo(CHAT_ID);
        assertThat(runtimeSession.title()).isEqualTo("Telegram existinguser");
        assertThat(runtimeSession.modelName()).isEqualTo("existing-model");

        verify(routingService).send(Platform.TELEGRAM, source, REPLY_TEXT);
    }

    @Test
    void acceptSkipsProcessingForUnauthorizedGatewaySource() {
        // allowByDefault=false, no allowedUserIds, no allowedUsernames → unauthorized
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        processor.accept(event);

        verify(sessionResolver, never()).resolve(any());
        verify(agentRuntime, never()).runTurn(any(), any(), any());
        verify(routingService, never()).send(any(), any(), any());
    }

    @Test
    void acceptProcessesNonTelegramPlatform() {
        // Non-Telegram platforms should be let through (not gated by Telegram config)
        agentProperties.getGateway().getTelegram().setAllowByDefault(true);
        SessionSource source = new SessionSource(Platform.UNKNOWN, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, USER_ID, "Telegram " + USERNAME,
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.UNKNOWN, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-ok", null)));

        processor.accept(event);

        verify(agentRuntime).runTurn(any(Session.class), eq(USER_TEXT), eq(List.of()));
        verify(routingService).send(Platform.UNKNOWN, source, REPLY_TEXT);
    }

    @Test
    void runtimeErrorStillTriesToSendErrorText() {
        agentProperties.getGateway().getTelegram().setAllowByDefault(true);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, USER_ID, "Telegram " + USERNAME,
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenThrow(new RuntimeException("model timeout"));

        processor.accept(event);

        verify(routingService).send(eq(Platform.TELEGRAM), eq(source), any(String.class));
    }

    @Test
    void sessionTitleIsDerivedFromUserName() {
        agentProperties.getGateway().getTelegram().setAllowByDefault(true);
        String customUsername = "alice_smith";
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, customUsername, customUsername);
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, USER_ID, "Telegram " + customUsername,
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-4", null)));

        processor.accept(event);

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(agentRuntime).runTurn(sessionCaptor.capture(), eq(USER_TEXT), eq(List.of()));
        assertThat(sessionCaptor.getValue().title()).isEqualTo("Telegram " + customUsername);
    }

    // ── Authorization logic tests ──────────────────────────────────

    @Test
    void isAuthorized_allowByDefaultTrue_authorizesAnyTelegramUser() {
        agentProperties.getGateway().getTelegram().setAllowByDefault(true);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, "randomUser", "randomName", "Random");
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, "randomUser", "Telegram randomName",
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-ok", null)));

        processor.accept(event);

        verify(agentRuntime).runTurn(any(Session.class), eq(USER_TEXT), eq(List.of()));
    }

    @Test
    void isAuthorized_userIdInAllowedList_authorizes() {
        agentProperties.getGateway().getTelegram().getAllowedUserIds().add(USER_ID);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, "someone", "Someone");
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, USER_ID, "Telegram someone",
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-ok", null)));

        processor.accept(event);

        verify(agentRuntime).runTurn(any(Session.class), eq(USER_TEXT), eq(List.of()));
    }

    @Test
    void isAuthorized_usernameInAllowedList_authorizes() {
        agentProperties.getGateway().getTelegram().getAllowedUsernames().add(USERNAME);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, "otherId", USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, "otherId", "Telegram " + USERNAME,
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-ok", null)));

        processor.accept(event);

        verify(agentRuntime).runTurn(any(Session.class), eq(USER_TEXT), eq(List.of()));
    }

    @Test
    void isAuthorized_userIdNotInList_usernameNotInList_denies() {
        // allowByDefault=false, empty lists → denied
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, "unknownId", "unknownName", "Unknown");
        MessageEvent event = messageEvent(source, USER_TEXT);

        processor.accept(event);

        verify(sessionResolver, never()).resolve(any());
        verify(agentRuntime, never()).runTurn(any(), any(), any());
    }

    @Test
    void isAuthorized_nullUserIdAndUsername_denies() {
        // allowByDefault=false, null userId/username → denied (no NPE)
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, null, null, null);
        MessageEvent event = messageEvent(source, USER_TEXT);

        processor.accept(event);

        verify(sessionResolver, never()).resolve(any());
        verify(agentRuntime, never()).runTurn(any(), any(), any());
    }

    @Test
    void isAuthorized_blankUserIdAndUsername_denies() {
        // allowByDefault=false, blank userId/username → denied
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, "  ", "  ", "  ");
        MessageEvent event = messageEvent(source, USER_TEXT);

        processor.accept(event);

        verify(sessionResolver, never()).resolve(any());
        verify(agentRuntime, never()).runTurn(any(), any(), any());
    }

    @Test
    void isAuthorized_nullUserIdButUsernameInList_authorizes() {
        agentProperties.getGateway().getTelegram().getAllowedUsernames().add(USERNAME);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, null, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, CHAT_ID, "Telegram " + USERNAME,
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-ok", null)));

        processor.accept(event);

        verify(agentRuntime).runTurn(any(Session.class), eq(USER_TEXT), eq(List.of()));
    }

    @Test
    void isAuthorized_nullUsernameButUserIdInList_authorizes() {
        agentProperties.getGateway().getTelegram().getAllowedUserIds().add(USER_ID);
        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, null, null);
        MessageEvent event = messageEvent(source, USER_TEXT);

        Session resolvedSession = new Session(SESSION_ID, USER_ID, "Telegram null",
            MODEL_PROVIDER, MODEL_NAME, null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(resolvedSession);
        when(agentRuntime.runTurn(any(Session.class), eq(USER_TEXT), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant(REPLY_TEXT, 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, REPLY_TEXT))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-ok", null)));

        processor.accept(event);

        verify(agentRuntime).runTurn(any(Session.class), eq(USER_TEXT), eq(List.of()));
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