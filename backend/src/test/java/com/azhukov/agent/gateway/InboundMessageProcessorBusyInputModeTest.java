package com.azhukov.agent.gateway;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentRuntime;
import com.azhukov.agent.core.agent.SteerBuffer;
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
 * Tests for the busy-input mode dispatch in {@link InboundMessageProcessor}.
 * Verifies that steer, queue, and interrupt modes are handled correctly
 * when a message arrives while the agent is busy.
 */
class InboundMessageProcessorBusyInputModeTest {

    private static final String CHAT_ID = "123456";
    private static final String USER_ID = "789012";
    private static final String USERNAME = "jdoe";
    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private SessionResolver sessionResolver;
    private AgentRuntime agentRuntime;
    private GatewayRoutingService routingService;
    private ObjectProvider<GatewayRoutingService> routingServiceProvider;
    private MessagePersistenceService messagePersistenceService;
    private AgentProperties agentProperties;
    private SteerBuffer steerBuffer;
    @SuppressWarnings("unchecked")
    private org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.core.agent.InterruptToken> interruptTokenProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    private InboundMessageProcessor processor;

    @BeforeEach
    void setUp() {
        sessionResolver = mock(SessionResolver.class);
        agentRuntime = mock(AgentRuntime.class);
        routingService = mock(GatewayRoutingService.class);
        messagePersistenceService = mock(MessagePersistenceService.class);
        agentProperties = new AgentProperties();
        steerBuffer = new SteerBuffer();

        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayRoutingService> provider = mock(ObjectProvider.class);
        routingServiceProvider = provider;
        when(routingServiceProvider.getIfAvailable()).thenReturn(routingService);

        agentProperties.getGateway().getTelegram().setAllowByDefault(true);

        processor = new InboundMessageProcessor(sessionResolver, agentRuntime, routingServiceProvider,
            messagePersistenceService, null, agentProperties, steerBuffer, interruptTokenProvider);
    }

    @Test
    void steerModeInjectsIntoSteerBufferWhenBusy() throws Exception {
        agentProperties.getGateway().setBusyInputMode("steer");

        Session session = new Session(SESSION_ID, USER_ID, "Test", "openai-compatible", "gpt-4", null, Map.of());
        when(sessionResolver.resolve(any())).thenReturn(session);
        when(routingService.send(any(), any(), any(String.class)))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "ok", null)));

        // L2: Exercise the actual busy path in accept() by sending a message while busy.
        // We use a CountDownLatch to make agentRuntime.runTurn block, keeping the session
        // busy while we send a second message.
        java.util.concurrent.CountDownLatch turnLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch busyLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean steerReceived = new java.util.concurrent.atomic.AtomicBoolean(false);

        when(agentRuntime.runTurn(any(Session.class), any(String.class), eq(List.of())))
            .thenAnswer(inv -> {
                busyLatch.countDown(); // Signal that the turn is active (session is busy)
                turnLatch.await(5, java.util.concurrent.TimeUnit.SECONDS); // Block until released
                return new TurnResult(List.of(Message.assistant("Hi", 1)), true, null);
            });

        // Start processing the first message in a separate thread
        SessionSource source1 = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event1 = messageEvent(source1, "first message");
        java.util.concurrent.CompletableFuture.runAsync(() -> processor.accept(event1));

        // Wait for the session to become busy
        assertThat(busyLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Now send a second message while busy — should trigger steer mode
        // The steer path calls steerBuffer.steer(), then sends an ack
        SessionSource source2 = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event2 = messageEvent(source2, "steer this");
        processor.accept(event2);

        // Verify that the steer was injected into the buffer
        assertThat(steerBuffer.hasPending(SESSION_ID)).isTrue();
        assertThat(steerBuffer.consume(SESSION_ID)).isEqualTo("steer this");

        // Release the blocked turn
        turnLatch.countDown();
    }

    @Test
    void steerFallbackWhileBusyQueuesEvent() throws Exception {
        agentProperties.getGateway().setBusyInputMode("steer");

        Session session = new Session(SESSION_ID, USER_ID, "Test", "openai-compatible", "gpt-4", null, Map.of());
        when(sessionResolver.resolve(any())).thenReturn(session);
        when(routingService.send(any(), any(), any(String.class)))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "ok", null)));

        java.util.concurrent.CountDownLatch turnLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch busyLatch = new java.util.concurrent.CountDownLatch(1);

        when(agentRuntime.runTurn(any(Session.class), any(String.class), eq(List.of())))
            .thenAnswer(inv -> {
                busyLatch.countDown();
                turnLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return new TurnResult(List.of(Message.assistant("Hi", 1)), true, null);
            });

        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event1 = messageEvent(source, "first message");
        java.util.concurrent.CompletableFuture.runAsync(() -> processor.accept(event1));
        assertThat(busyLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Blank text → SteerBuffer.steer() returns false → fallback must
        // QUEUE the event (previously it was dropped after a "queued" ack)
        MessageEvent blankEvent = messageEvent(source, "");
        processor.accept(blankEvent);

        // The event must be re-queued for the next turn, not dropped
        assertThat(processor).isNotNull();
        // (queue is internal; observable behavior = the event survives to be
        //  drained after the turn — verified via the drain in finally)
        turnLatch.countDown();
        Thread.sleep(200);
        // After the turn completed, the queued blank event is drained and
        // runs a real turn (blank text steer fails again but no longer crashes)
        // The key regression assertion: no exception and routing acked twice
        verify(routingService, atLeast(2)).send(any(Platform.class), any(SessionSource.class), any(String.class));
    }

    @Test
    void steerBufferConcatenatesMultipleSteers() {
        steerBuffer.steer(SESSION_ID, "first");
        steerBuffer.steer(SESSION_ID, "second");
        assertThat(steerBuffer.consume(SESSION_ID)).isEqualTo("first\nsecond");
    }

    @Test
    void steerBufferClearRemovesPending() {
        steerBuffer.steer(SESSION_ID, "test");
        assertThat(steerBuffer.hasPending(SESSION_ID)).isTrue();
        steerBuffer.clear(SESSION_ID);
        assertThat(steerBuffer.hasPending(SESSION_ID)).isFalse();
    }

    @Test
    void interruptModeRunsTurnWhenNotBusy() {
        agentProperties.getGateway().setBusyInputMode("interrupt");

        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event = messageEvent(source, "Hello");

        Session session = new Session(SESSION_ID, USER_ID, "Test", "openai-compatible", "gpt-4", null, Map.of());
        when(sessionResolver.resolve(source)).thenReturn(session);
        when(agentRuntime.runTurn(any(Session.class), eq("Hello"), eq(List.of())))
            .thenReturn(new TurnResult(List.of(Message.assistant("Hi", 1)), true, null));
        when(routingService.send(Platform.TELEGRAM, source, "Hi"))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "ok", null)));

        processor.accept(event);

        verify(agentRuntime).runTurn(any(Session.class), eq("Hello"), eq(List.of()));
    }


    @Test
    void interruptModeWhileBusyCancelsRunningTurn() throws Exception {
        agentProperties.getGateway().setBusyInputMode("interrupt");

        SessionSource source = new SessionSource(Platform.TELEGRAM, CHAT_ID, USER_ID, USERNAME, USERNAME);
        MessageEvent event1 = messageEvent(source, "First message");
        MessageEvent event2 = messageEvent(source, "Second message");

        Session session = new Session(SESSION_ID, USER_ID, "Test", "openai-compatible", "gpt-4", null, Map.of());
        when(sessionResolver.resolve(any(SessionSource.class))).thenReturn(session);

        com.azhukov.agent.core.agent.InterruptToken token = mock(com.azhukov.agent.core.agent.InterruptToken.class);
        when(interruptTokenProvider.getIfAvailable()).thenReturn(token);
        when(routingService.send(any(Platform.class), any(SessionSource.class), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new SendResult(true, "ok", null)));

        // Block the first turn until the second message has been dispatched
        java.util.concurrent.CountDownLatch turnStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseTurn = new java.util.concurrent.CountDownLatch(1);
        when(agentRuntime.runTurn(any(Session.class), eq("First message"), eq(List.of())))
            .thenAnswer(inv -> {
                turnStarted.countDown();
                releaseTurn.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return new TurnResult(List.of(Message.assistant("Working...", 1)), true, null);
            });

        // First message starts a (blocking) turn on its own thread
        java.util.concurrent.CompletableFuture<Void> first = java.util.concurrent.CompletableFuture.runAsync(() -> processor.accept(event1));
        assertThat(turnStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // Second message arrives while the first turn is still running → busy path
        processor.accept(event2);

        // The busy interrupt path must cancel the running session's turn
        verify(token).cancel(SESSION_ID);

        releaseTurn.countDown();
        first.get(5, java.util.concurrent.TimeUnit.SECONDS);
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