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
import com.azhukov.agent.persistence.MessagePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for InboundMessageProcessor fix — non-Telegram platforms should be
 * allowed through (not rejected).
 */
class InboundMessageProcessorNonTelegramTest {

    private SessionResolver sessionResolver;
    private AgentRuntime agentRuntime;
    private GatewayRoutingService routingService;
    @SuppressWarnings("unchecked")
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

        processor = new InboundMessageProcessor(sessionResolver, agentRuntime, routingServiceProvider,
            messagePersistenceService, null, agentProperties, new com.azhukov.agent.core.agent.SteerBuffer());
    }

    @Test
    void nonTelegramPlatformIsProcessed() {
        // Non-Telegram platforms should be let through (return true from isAuthorized)
        UUID sessionId = UUID.randomUUID();
        SessionSource source = new SessionSource(Platform.UNKNOWN, "chat-1", "user-1", "name", "display");
        MessageEvent event = new MessageEvent("evt-1", source, MessageType.TEXT, "hello",
            List.of(), Map.of(), Instant.parse("2026-01-01T00:00:00Z"));

        Session session = new Session(sessionId, "user-1", "Test",
            "openai-compatible", "test-model", null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(session);
        when(agentRuntime.runTurn(any(Session.class), eq("hello"), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant("reply", 1)), true, null));
        when(routingService.send(Platform.UNKNOWN, source, "reply"))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-1", null)));

        processor.accept(event);

        verify(sessionResolver).resolve(source);
        verify(agentRuntime).runTurn(any(Session.class), eq("hello"), eq(List.of()));
        verify(routingService).send(Platform.UNKNOWN, source, "reply");
    }

    @Test
    void discordPlatformIsProcessed() {
        // If there were other platforms, they should also be allowed through
        UUID sessionId = UUID.randomUUID();
        SessionSource source = new SessionSource(Platform.UNKNOWN, "d-1", "u-1", "user", "User");
        MessageEvent event = new MessageEvent("evt-1", source, MessageType.TEXT, "test",
            List.of(), Map.of(), Instant.parse("2026-01-01T00:00:00Z"));

        Session session = new Session(sessionId, "u-1", "Discord user",
            "openai-compatible", "test-model", null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(session);
        when(agentRuntime.runTurn(any(Session.class), eq("test"), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant("ok", 1)), true, null));
        when(routingService.send(Platform.UNKNOWN, source, "ok"))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "msg-ok", null)));

        processor.accept(event);

        verify(agentRuntime).runTurn(any(Session.class), eq("test"), eq(List.of()));
    }
}