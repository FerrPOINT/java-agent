package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.auth.SlashAccessPolicy;
import com.azhukov.agent.bot.batch.PhotoBatchDebouncer;
import com.azhukov.agent.bot.batch.TextBatchDebouncer;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.footer.RuntimeFooter;
import com.azhukov.agent.bot.formatting.ResponseFilter;
import com.azhukov.agent.bot.goal.GoalAutoContinueService;
import com.azhukov.agent.bot.group.GroupMessageFilter;
import com.azhukov.agent.bot.keyboard.CallbackQueryHandler;
import com.azhukov.agent.bot.media.InboundMediaHandler;
import com.azhukov.agent.bot.media.MediaDeliveryService;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.reaction.ReactionManager;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import com.azhukov.agent.bot.session.BusySessionHandler;
import com.azhukov.agent.bot.session.EditCaptureService;
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.typing.TypingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M28: Test that drainQueueLocked processes queued messages inside
 * the per-chat lock to prevent race conditions.
 */
class BotMessageProcessorDrainQueueLockTest {

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
        busyHandler = new BusySessionHandler(new BotProperties());
        typingManager = mock(TypingManager.class);
        backendClient = mock(AgentBackendClient.class);
        commandRegistry = mock(CommandRegistry.class);
        callbackQueryHandler = mock(CallbackQueryHandler.class);
        properties = new BotProperties();
        streamEditor = mock(StreamEditor.class);
        inboundMediaHandler = mock(InboundMediaHandler.class);
        mediaDeliveryService = mock(MediaDeliveryService.class);
        when(mediaDeliveryService.extractMediaTags(anyString()))
            .thenAnswer(inv -> new MediaDeliveryService.ExtractionResult(
                java.util.List.of(), inv.getArgument(0)));
        when(mediaDeliveryService.stripMediaTagsForDisplay(anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        runtimeFooter = mock(RuntimeFooter.class);
        reactionManager = mock(ReactionManager.class);
        textBatchDebouncer = mock(TextBatchDebouncer.class);
        photoBatchDebouncer = mock(PhotoBatchDebouncer.class);
        groupMessageFilter = mock(GroupMessageFilter.class);
        slashAccessPolicy = mock(SlashAccessPolicy.class);
        responseFilter = mock(ResponseFilter.class);
        goalAutoContinueService = mock(GoalAutoContinueService.class);
        editCaptureService = mock(EditCaptureService.class);

        when(authorizationService.isAuthorized(any())).thenReturn(true);
        when(groupMessageFilter.shouldProcess(any())).thenReturn(true);
        when(responseFilter.shouldFilter(anyString())).thenReturn(false);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("");
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(Optional.of(1L));
        when(editCaptureService.getCapture(anyLong())).thenReturn(null);

        BotSessionEntity session = mock(BotSessionEntity.class);
        when(session.getBackendSessionId()).thenReturn(null);
        when(session.getUserId()).thenReturn("123");
        when(sessionStore.resolveOrCreate(anyString(), anyString(), any())).thenReturn(session);

        // c5: construct the extracted collaborators with the same mocked deps
        UpdateDispatcher updateDispatcher = new UpdateDispatcher(
            properties, editCaptureService, textBatchDebouncer, photoBatchDebouncer);
        StreamingOrchestrator streamingOrchestrator = new StreamingOrchestrator(
            backendClient, streamEditor, busyHandler, runtimeFooter, properties, mediaDeliveryService);

        processor = new BotMessageProcessor(
            telegramClient, authorizationService, sessionStore, busyHandler,
            typingManager, backendClient, commandRegistry, callbackQueryHandler,
            properties, streamEditor, inboundMediaHandler, mediaDeliveryService, runtimeFooter,
            reactionManager, textBatchDebouncer, photoBatchDebouncer,
            groupMessageFilter, slashAccessPolicy, responseFilter, goalAutoContinueService,
            editCaptureService, updateDispatcher, streamingOrchestrator
        );
        processor.init();
    }

    @Test
    void queuedMessagesAreProcessedAfterInitialMessage() {
        long chatId = 500L;
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                // Queue a message during the first processing
                busyHandler.queueMessage(chatId, textEvent(2, chatId, "queued"));
                return new AgentBackendClient.ChatResult("reply", "model", 10, 100, true);
            });
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenReturn(new AgentBackendClient.ChatResult("reply", "model", 10, 100, false));

        processor.accept(textEvent(1, chatId, "first"));

        // The queued message should have been processed (drained inside the lock)
        // Verify backendClient.chatStream was called at least twice (once for initial, once for queued)
        verify(backendClient, atLeast(2)).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    private UpdateEvent textEvent(long updateId, long chatId, String text) {
        return new UpdateEvent(
            updateId, UpdateEvent.Type.TEXT, chatId, 200L, "test", null, null, text,
            null, null, null, null, null, null, false, null, null,
            0L, null, 0L, null
        );
    }
}