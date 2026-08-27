package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.auth.SlashAccessPolicy;
import com.azhukov.agent.bot.batch.PhotoBatchDebouncer;
import com.azhukov.agent.bot.batch.TextBatchDebouncer;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.RuntimeFooter;
import com.azhukov.agent.bot.formatting.ResponseFilter;
import com.azhukov.agent.bot.goal.GoalAutoContinueService;
import com.azhukov.agent.bot.group.GroupMessageFilter;
import com.azhukov.agent.bot.keyboard.CallbackQueryHandler;
import com.azhukov.agent.bot.media.InboundMediaHandler;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.core.ReactionManager;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.session.EditCaptureService;
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.typing.TypingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@code sendBusyAck} in {@link BotMessageProcessor} (S-4 gap).
 * <p>
 * {@code sendBusyAck} is private and invoked indirectly through
 * {@code handleBusyMessage} when the chat is busy. These tests trigger the
 * busy-ack path by marking the chat busy, sending a message, and verifying
 * the ack message content via {@link TelegramClient#sendMessage} mock
 * verification.
 * <p>
 * Uses real {@link BusySessionHandler} and real {@link BotProperties},
 * matching the pattern from {@link BotMessageProcessorTest}.
 */
class BotMessageProcessorBusyAckTest {

    private TelegramClient telegramClient;
    private AuthorizationService authorizationService;
    private BotSessionStore sessionStore;
    private BusySessionHandler busyHandler;
    private TypingManager typingManager;
    private AgentBackendClient backendClient;
    private CommandRegistry commandRegistry;
    private CallbackQueryHandler callbackQueryHandler;
    private BotProperties properties;
    private StreamEditor streamEditor;
    private InboundMediaHandler inboundMediaHandler;
    private MediaDeliveryService mediaDeliveryService;
    private RuntimeFooter runtimeFooter;
    private ReactionManager reactionManager;
    private TextBatchDebouncer textBatchDebouncer;
    private PhotoBatchDebouncer photoBatchDebouncer;
    private GroupMessageFilter groupMessageFilter;
    private SlashAccessPolicy slashAccessPolicy;
    private ResponseFilter responseFilter;
    private GoalAutoContinueService goalAutoContinueService;
    private EditCaptureService editCaptureService;

    private BotMessageProcessor processor;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        authorizationService = mock(AuthorizationService.class);
        sessionStore = mock(BotSessionStore.class);
        properties = new BotProperties();
        properties.setDefaultModel("test-model");
        properties.setParseMode("MarkdownV2");
        // busyAckEnabled defaults to true
        busyHandler = new BusySessionHandler(properties);
        typingManager = mock(TypingManager.class);
        backendClient = mock(AgentBackendClient.class);
        commandRegistry = mock(CommandRegistry.class);
        callbackQueryHandler = mock(CallbackQueryHandler.class);
        streamEditor = mock(StreamEditor.class);
        inboundMediaHandler = mock(InboundMediaHandler.class);
        mediaDeliveryService = new MediaDeliveryService();
        runtimeFooter = mock(RuntimeFooter.class);
        reactionManager = mock(ReactionManager.class);
        textBatchDebouncer = mock(TextBatchDebouncer.class);
        photoBatchDebouncer = mock(PhotoBatchDebouncer.class);
        groupMessageFilter = mock(GroupMessageFilter.class);
        slashAccessPolicy = mock(SlashAccessPolicy.class);
        responseFilter = mock(ResponseFilter.class);
        goalAutoContinueService = mock(GoalAutoContinueService.class);
        editCaptureService = mock(EditCaptureService.class);

        // Default stubs
        when(authorizationService.isAuthorized(any(UpdateEvent.class))).thenReturn(true);
        when(authorizationService.isAuthorized(anyLong(), anyString(), anyLong())).thenReturn(true);
        when(groupMessageFilter.shouldProcess(any())).thenReturn(true);
        when(groupMessageFilter.shouldObserveUnmentioned()).thenReturn(false);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("");
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.empty());
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(Optional.of(1L));
        when(streamEditor.editStream(anyLong(), anyLong(), anyString())).thenReturn(true);
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString())).thenReturn(true);
        doNothing().when(streamEditor).clearStream(anyLong());
        when(responseFilter.shouldFilter(anyString())).thenReturn(false);
        when(slashAccessPolicy.canRun(anyLong(), anyString())).thenReturn(true);
        when(editCaptureService.getCapture(anyLong())).thenReturn(null);

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        when(textBatchDebouncer.offer(any())).thenReturn(false);
        when(photoBatchDebouncer.offer(any())).thenReturn(false);

        // Stub streaming result so the first message processes normally
        stubStreamingResult("response", true);

        // c5: construct the extracted collaborators with the same mocked deps
        UpdateDispatcher updateDispatcher = new UpdateDispatcher(
            properties, editCaptureService, textBatchDebouncer, photoBatchDebouncer);
        StreamingOrchestrator streamingOrchestrator = new StreamingOrchestrator(
            backendClient, streamEditor, busyHandler, runtimeFooter, properties, mediaDeliveryService,
            mock(com.azhukov.agent.bot.client.TelegramClient.class));

        processor = new BotMessageProcessor(
            telegramClient, authorizationService, sessionStore, busyHandler,
            typingManager, backendClient, commandRegistry, callbackQueryHandler,
            properties, streamEditor, inboundMediaHandler, mediaDeliveryService,
            runtimeFooter, reactionManager, textBatchDebouncer, photoBatchDebouncer,
            groupMessageFilter, slashAccessPolicy, responseFilter, goalAutoContinueService,
            editCaptureService, updateDispatcher, streamingOrchestrator);
    }

    @SuppressWarnings("unchecked")
    private void stubStreamingResult(String content, boolean streamFinalized) {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                tokenConsumer.accept(content);
                if (streamFinalized) {
                    Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(8);
                    onComplete.accept(new AgentBackendClient.ChatResult(content, "test-model", 100, 1000, true));
                }
                return new AgentBackendClient.ChatResult(content, "test-model", 100, 1000, streamFinalized, false);
            });
    }

    private UpdateEvent textEvent(long updateId, long chatId, String text) {
        return new UpdateEvent(updateId, UpdateEvent.Type.TEXT, chatId, 200L,
            "testuser", text, null, null, null, null, null, null,
            false, null, null, 100 + (int) updateId, null, 0);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Busy-ack message content tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1. sendBusyAck sends steer message when mode=steer and steered=true")
    void busyAckSendsSteerMessage() {
        properties.setBusyInputMode("steer");

        // Set up session with backend session ID for steer to work
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        // Stub steer to succeed
        when(backendClient.steer(anyString(), anyString())).thenReturn(true);

        // Process first message to mark busy → free
        processor.accept(textEvent(1, 100L, "first"));

        // L4: Clear invocations so the verify below only checks the busy-ack message
        clearInvocations(telegramClient);

        // Now mark busy and send second message → should trigger steer ack
        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "steer this"));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(eq(100L), msgCaptor.capture(), anyString(), any(), any(), anyBoolean());
        assertThat(msgCaptor.getValue()).contains("⏩ Steered");
    }

    @Test
    @DisplayName("2. sendBusyAck sends queue message when mode=queue")
    void busyAckSendsQueueMessage() {
        properties.setBusyInputMode("queue");

        // Process first message
        processor.accept(textEvent(1, 100L, "first"));

        // L4: Clear invocations so the verify below only checks the busy-ack message
        clearInvocations(telegramClient);

        // Mark busy and send second message
        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "second message"));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(eq(100L), msgCaptor.capture(), anyString(), any(), any(), anyBoolean());
        assertThat(msgCaptor.getValue()).contains("⏳ Queued");
    }

    @Test
    @DisplayName("3. sendBusyAck sends interrupt message when mode=interrupt")
    void busyAckSendsInterruptMessage() {
        properties.setBusyInputMode("interrupt");

        // Process first message
        processor.accept(textEvent(1, 100L, "first"));

        // L4: Clear invocations so the verify below only checks the busy-ack message
        clearInvocations(telegramClient);

        // Mark busy and send second message
        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "interrupt this"));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(eq(100L), msgCaptor.capture(), anyString(), any(), any(), anyBoolean());
        assertThat(msgCaptor.getValue()).contains("⚡ Interrupting");
    }

    @Test
    @DisplayName("4. sendBusyAck sends subagent message when demotedForSubagents=true")
    void busyAckSendsSubagentMessage() {
        properties.setBusyInputMode("interrupt");

        // Set up session with backend session ID so subagent check runs
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setBackendSessionId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        // Stub backendClient to return active subagents (non-null, is array, has different session ID)
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode agentsArray = om.createArrayNode();
        com.fasterxml.jackson.databind.node.ObjectNode agentNode = om.createObjectNode();
        agentNode.put("sessionId", "different-session-id");
        agentsArray.add(agentNode);
        when(backendClient.listActiveAgents()).thenReturn(agentsArray);

        // Process first message
        processor.accept(textEvent(1, 100L, "first"));

        // L4: Clear invocations so the verify below only checks the busy-ack message
        clearInvocations(telegramClient);

        // Mark busy and send second message
        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "interrupt during subagent"));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(eq(100L), msgCaptor.capture(), anyString(), any(), any(), anyBoolean());
        assertThat(msgCaptor.getValue()).contains("⏳ Subagent working");
    }

    @Test
    @DisplayName("5. sendBusyAck does NOT send when busyAckEnabled=false")
    void busyAckNotSentWhenDisabled() {
        properties.setBusyInputMode("queue");
        properties.setBusyAckEnabled(false);

        // Process first message
        processor.accept(textEvent(1, 100L, "first"));

        // L4: Clear invocations so the verify below only checks the busy-ack message
        clearInvocations(telegramClient);

        // Mark busy and send second message
        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "second"));

        // sendMessage should NOT be called for the busy-ack
        // (it may be called for other purposes like stream finalization, but not with "⏳ Queued")
        verify(telegramClient, never()).sendMessage(eq(100L), contains("⏳"), anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("6. sendBusyAck does NOT send when debounce window is active")
    void busyAckNotSentWhenDebounced() {
        properties.setBusyInputMode("queue");

        // Process first message
        processor.accept(textEvent(1, 100L, "first"));

        // Mark busy and send first busy message — should get ack
        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "second"));

        // Reset the mock to clear the first ack verification
        clearInvocations(telegramClient);

        // Re-mark busy and send another message immediately — should be debounced
        busyHandler.markBusy(100L);
        processor.accept(textEvent(3, 100L, "third"));

        // The second busy-ack should NOT have been sent (debounced within 30s window)
        verify(telegramClient, never()).sendMessage(eq(100L), contains("⏳"), anyString(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("7. sendBusyAck includes onboarding hint on first send")
    void busyAckIncludesOnboardingHintOnFirstSend() {
        properties.setBusyInputMode("queue");

        // Process first message
        processor.accept(textEvent(1, 100L, "first"));

        // L4: Clear invocations so the verify below only checks the busy-ack message
        clearInvocations(telegramClient);

        // Mark busy and send second message
        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "second"));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramClient).sendMessage(eq(100L), msgCaptor.capture(), anyString(), any(), any(), anyBoolean());
        assertThat(msgCaptor.getValue()).contains("💡 You can change how messages are handled");
    }
}