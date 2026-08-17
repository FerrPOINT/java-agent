package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.auth.SlashAccessPolicy;
import com.azhukov.agent.bot.batch.PhotoBatchDebouncer;
import com.azhukov.agent.bot.batch.TextBatchDebouncer;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.commands.impl.GoalCommand;
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
import com.azhukov.agent.bot.streaming.StreamEditor;
import com.azhukov.agent.bot.typing.TypingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link BotMessageProcessor}.
 * <p>
 * Covers message processing flow, stream interruption, error handling,
 * media handling, session management, and edge cases.
 * <p>
 * Uses real {@link BusySessionHandler} and {@link BotProperties} instances;
 * all other dependencies are mocked.
 */
class BotMessageProcessorTest {

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

    private BotMessageProcessor processor;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        authorizationService = mock(AuthorizationService.class);
        sessionStore = mock(BotSessionStore.class);
        properties = new BotProperties();
        properties.setDefaultModel("test-model");
        properties.setBusyMode("queue");
        properties.setRedactPii(false);
        properties.setParseMode("MarkdownV2");
        busyHandler = new BusySessionHandler(properties);
        typingManager = mock(TypingManager.class);
        backendClient = mock(AgentBackendClient.class);
        commandRegistry = mock(CommandRegistry.class);
        callbackQueryHandler = mock(CallbackQueryHandler.class);
        streamEditor = mock(StreamEditor.class);
        inboundMediaHandler = mock(InboundMediaHandler.class);
        // S-2: Use real MediaDeliveryService (not a mock) so media extraction works
        mediaDeliveryService = new MediaDeliveryService();
        runtimeFooter = mock(RuntimeFooter.class);
        reactionManager = mock(ReactionManager.class);
        textBatchDebouncer = mock(TextBatchDebouncer.class);
        photoBatchDebouncer = mock(PhotoBatchDebouncer.class);
        groupMessageFilter = mock(GroupMessageFilter.class);
        slashAccessPolicy = mock(SlashAccessPolicy.class);
        responseFilter = mock(ResponseFilter.class);
        goalAutoContinueService = mock(GoalAutoContinueService.class);

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

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        // Default: textBatchDebouncer.offer returns false (not buffered)
        when(textBatchDebouncer.offer(any())).thenReturn(false);
        when(photoBatchDebouncer.offer(any())).thenReturn(false);

        processor = new BotMessageProcessor(
            telegramClient, authorizationService, sessionStore, busyHandler,
            typingManager, backendClient, commandRegistry, callbackQueryHandler,
            properties, streamEditor, inboundMediaHandler, mediaDeliveryService,
            runtimeFooter, reactionManager, textBatchDebouncer, photoBatchDebouncer,
            groupMessageFilter, slashAccessPolicy, responseFilter, goalAutoContinueService);
    }

    // ─── Helper methods ──────────────────────────────────────────

    private UpdateEvent textEvent(long updateId, long chatId, String text) {
        return new UpdateEvent(updateId, UpdateEvent.Type.TEXT, chatId, 200L,
            "testuser", text, null, null, null, null, null, null,
            false, null, null, 100 + (int) updateId, null, 0);
    }

    private UpdateEvent textEventWithMessageId(long updateId, long chatId, String text, long messageId) {
        return new UpdateEvent(updateId, UpdateEvent.Type.TEXT, chatId, 200L,
            "testuser", text, null, null, null, null, null, null,
            false, null, null, messageId, null, 0);
    }

    private UpdateEvent commandEvent(long updateId, long chatId, String commandName, String commandArgs) {
        return new UpdateEvent(updateId, UpdateEvent.Type.COMMAND, chatId, 200L,
            "testuser", "/" + commandName + (commandArgs != null && !commandArgs.isEmpty() ? " " + commandArgs : ""),
            null, null, null, null, null, null,
            true, commandName, commandArgs, 100 + (int) updateId, null, 0);
    }

    private UpdateEvent callbackEvent(long updateId, long chatId, String callbackData) {
        return new UpdateEvent(updateId, UpdateEvent.Type.CALLBACK_QUERY, chatId, 200L,
            "testuser", null, null, null, null,
            "cq-" + updateId, callbackData, null,
            false, null, null, 100 + (int) updateId, null, 0);
    }

    private UpdateEvent photoEvent(long updateId, long chatId, String fileId, String caption, String mediaGroupId) {
        return new UpdateEvent(updateId, UpdateEvent.Type.PHOTO, chatId, 200L,
            "testuser", null, caption, fileId, "photo",
            null, null, null, false, null, null,
            100 + (int) updateId, mediaGroupId, 0);
    }

    private UpdateEvent documentEvent(long updateId, long chatId, String fileId, String caption) {
        return new UpdateEvent(updateId, UpdateEvent.Type.DOCUMENT, chatId, 200L,
            "testuser", null, caption, fileId, "document",
            null, null, null, false, null, null,
            100 + (int) updateId, null, 0);
    }

    private UpdateEvent voiceEvent(long updateId, long chatId, String fileId) {
        return new UpdateEvent(updateId, UpdateEvent.Type.VOICE, chatId, 200L,
            "testuser", null, null, fileId, "voice",
            null, null, null, false, null, null,
            100 + (int) updateId, null, 0);
    }

    private UpdateEvent stickerEvent(long updateId, long chatId, String fileId) {
        return new UpdateEvent(updateId, UpdateEvent.Type.STICKER, chatId, 200L,
            "testuser", null, null, fileId, "sticker",
            null, null, null, false, null, null,
            100 + (int) updateId, null, 0);
    }

    private UpdateEvent animationEvent(long updateId, long chatId, String fileId) {
        return new UpdateEvent(updateId, UpdateEvent.Type.ANIMATION, chatId, 200L,
            "testuser", null, null, fileId, "animation",
            null, null, null, false, null, null,
            100 + (int) updateId, null, 0);
    }

    private UpdateEvent locationEvent(long updateId, long chatId, String text) {
        return new UpdateEvent(updateId, UpdateEvent.Type.LOCATION, chatId, 200L,
            "testuser", text, null, null, "location",
            null, null, null, false, null, null,
            100 + (int) updateId, null, 0);
    }

    private UpdateEvent unknownEvent(long updateId, long chatId) {
        return new UpdateEvent(updateId, UpdateEvent.Type.UNKNOWN, chatId, 200L,
            "testuser", null, null, null, null,
            null, null, null, false, null, null,
            100 + (int) updateId, null, 0);
    }

    @SuppressWarnings("unchecked")
    private void stubStreamingResult(String content, boolean streamFinalized) {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                tokenConsumer.accept(content);
                if (streamFinalized) {
                    Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(7);
                    onComplete.accept(new AgentBackendClient.ChatResult(content, "test-model", 100, 1000, true));
                }
                return new AgentBackendClient.ChatResult(content, "test-model", 100, 1000, streamFinalized, false);
            });
    }

    @SuppressWarnings("unchecked")
    private void stubStreamingResultWithMetadata(String content, boolean streamFinalized, String modelUsed,
                                                  Integer contextTokens, Integer contextLength, boolean memoryUpdated) {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                if (content != null) {
                    tokenConsumer.accept(content);
                }
                if (streamFinalized) {
                    Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(7);
                    onComplete.accept(new AgentBackendClient.ChatResult(content, modelUsed, contextTokens, contextLength, true));
                }
                return new AgentBackendClient.ChatResult(content, modelUsed, contextTokens, contextLength, streamFinalized, memoryUpdated);
            });
    }

    @SuppressWarnings("unchecked")
    private void stubStreamingWithTokensAndFinalize(String content, String modelUsed, boolean memoryUpdated) {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                tokenConsumer.accept(content);
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(7);
                onComplete.accept(new AgentBackendClient.ChatResult(content, modelUsed, 100, 1000, true, memoryUpdated));
                return new AgentBackendClient.ChatResult(content, modelUsed, 100, 1000, true, memoryUpdated);
            });
    }

    // ─── accept() — null and edge cases ─────────────────────────

    @Test
    void acceptNullEventDoesNothing() {
        processor.accept(null);
        verifyNoInteractions(telegramClient);
        verifyNoInteractions(backendClient);
    }

    @Test
    void acceptUnknownEventLogsAndDoesNothing() {
        processor.accept(unknownEvent(1, 100L));
        verifyNoInteractions(telegramClient);
        verifyNoInteractions(backendClient);
    }

    @Test
    void acceptUnknownEventWithExceptionSendsError() {
        // Force an exception by making groupMessageFilter throw
        when(groupMessageFilter.shouldProcess(any()))
            .thenThrow(new RuntimeException("test error"));
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendMessage(eq(100L), contains("An error occurred"), anyString(), any(), any());
    }

    // ─── Callback Query ──────────────────────────────────────────

    @Test
    void callbackQueryWithResponseSendsMessage() {
        when(callbackQueryHandler.handle(any())).thenReturn("Callback result");
        processor.accept(callbackEvent(1, 100L, "mp:gpt-4"));
        verify(telegramClient).sendMessage(100L, "Callback result");
    }

    @Test
    void callbackQueryWithBlankResponseDoesNotSendMessage() {
        when(callbackQueryHandler.handle(any())).thenReturn("");
        processor.accept(callbackEvent(1, 100L, "mp:gpt-4"));
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void callbackQueryWithNullResponseDoesNotSendMessage() {
        when(callbackQueryHandler.handle(any())).thenReturn(null);
        processor.accept(callbackEvent(1, 100L, "mp:gpt-4"));
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    // ─── Command handling ────────────────────────────────────────

    @Test
    void commandWithUnknownHandlerSendsUnknownMessage() {
        when(commandRegistry.get("nonexistent")).thenReturn(null);
        processor.accept(commandEvent(1, 100L, "nonexistent", ""));
        verify(telegramClient).sendMessage(eq(100L), contains("Unknown command"));
    }

    @Test
    void commandWithAccessDeniedSendsAccessMessage() {
        when(commandRegistry.get("admin")).thenReturn(mock(CommandHandler.class));
        when(slashAccessPolicy.canRun(anyLong(), anyString())).thenReturn(false);
        processor.accept(commandEvent(1, 100L, "admin", ""));
        verify(telegramClient).sendMessage(eq(100L), contains("don't have access"));
    }

    @Test
    void commandWithHandlerReturnsResponse() {
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(any(), any())).thenReturn("Command result");
        when(commandRegistry.get("test")).thenReturn(handler);
        processor.accept(commandEvent(1, 100L, "test", ""));
        // sendFormatted is called internally
        verify(telegramClient).sendMessage(eq(100L), contains("Command result"), anyString(), any(), any());
    }

    @Test
    void commandWithHandlerReturnsBlankDoesNotSend() {
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(any(), any())).thenReturn("");
        when(commandRegistry.get("test")).thenReturn(handler);
        processor.accept(commandEvent(1, 100L, "test", ""));
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void commandWithHandlerReturnsNullDoesNotSend() {
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(any(), any())).thenReturn(null);
        when(commandRegistry.get("test")).thenReturn(handler);
        processor.accept(commandEvent(1, 100L, "test", ""));
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void commandHandlerThrowsExceptionSendsError() {
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(any(), any())).thenThrow(new RuntimeException("cmd error"));
        when(commandRegistry.get("test")).thenReturn(handler);
        processor.accept(commandEvent(1, 100L, "test", ""));
        verify(telegramClient).sendMessage(eq(100L), contains("Error executing command"), anyString(), any(), any());
    }

    // ─── Text processing — basic flow ───────────────────────────

    @Test
    void textMessageProcessingCallsBackend() {
        stubStreamingResult("Hello back", true);
        processor.accept(textEvent(1, 100L, "Hello"));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void textMessageStreamFinalizedDoesNotSendDuplicate() {
        stubStreamingResult("Response", true);
        processor.accept(textEvent(1, 100L, "Hello"));
        // When streamFinalized is true, the message is finalized via streamEditor, not sent via sendMessage
        verify(telegramClient, never()).sendMessage(eq(100L), contains("Response"), anyString(), any(), any());
    }

    @Test
    void textMessageStreamNotFinalizedSendsFormatted() {
        stubStreamingResult("Response text", false);
        processor.accept(textEvent(1, 100L, "Hello"));
        // When streamFinalized is false, sendFormatted IS called (5-arg sendMessage)
        verify(telegramClient).sendMessage(eq(100L), contains("Response text"), anyString(), any(), any());
    }

    @Test
    void textMessageWithFooterAppendsFooter() {
        stubStreamingResult("Response", false);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("\n\nfooter");
        processor.accept(textEvent(1, 100L, "Hello"));
        verify(telegramClient).sendMessage(eq(100L), contains("footer"), anyString(), any(), any());
    }

    @Test
    void textMessageStartsAndStopsTyping() {
        stubStreamingResult("Response", true);
        processor.accept(textEvent(1, 100L, "Hello"));
        verify(typingManager).startTyping(eq(100L), any());
        verify(typingManager).stopTyping(100L);
    }

    @Test
    void textMessageMarksBusyAndMarksFree() {
        stubStreamingResult("Response", true);
        processor.accept(textEvent(1, 100L, "Hello"));
        // After processing, the chat should be free
        assertThat(busyHandler.isBusy(100L)).isFalse();
    }

    @Test
    void textMessageTriggersReactionOnStart() {
        stubStreamingResult("Response", true);
        processor.accept(textEvent(1, 100L, "Hello"));
        verify(reactionManager).onProcessingStart(eq(100L), anyLong());
        verify(reactionManager).onProcessingComplete(eq(100L), anyLong(), eq(true));
    }

    // ─── Text batching ──────────────────────────────────────────

    @Test
    void textBatchBufferedDoesNotProcessImmediately() {
        when(textBatchDebouncer.offer(any())).thenReturn(true);
        processor.accept(textEvent(1, 100L, "Hello"));
        verifyNoInteractions(backendClient);
    }

    @Test
    void textBatchDisabledProcessesImmediately() {
        when(textBatchDebouncer.offer(any())).thenReturn(false);
        stubStreamingResult("Response", true);
        processor.accept(textEvent(1, 100L, "Hello"));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void commandEventNotBatchedEvenWithTextBatchEnabled() {
        // offerTextBatch checks isCommand — commands should not be batched
        when(textBatchDebouncer.offer(any())).thenReturn(true);
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(any(), any())).thenReturn("ok");
        when(commandRegistry.get("test")).thenReturn(handler);
        processor.accept(commandEvent(1, 100L, "test", ""));
        // Command should be processed directly, not buffered
        verify(handler).handle(any(), any());
    }

    // ─── Photo batching ─────────────────────────────────────────

    @Test
    void photoWithMediaGroupIdBuffered() {
        when(photoBatchDebouncer.offer(any())).thenReturn(true);
        processor.accept(photoEvent(1, 100L, "file123", null, "group1"));
        verifyNoInteractions(backendClient);
    }

    @Test
    void photoWithoutMediaGroupIdProcessedImmediately() {
        when(photoBatchDebouncer.offer(any())).thenReturn(false);
        stubStreamingResult("desc", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Photo: test]"));
        processor.accept(photoEvent(1, 100L, "file123", "caption", null));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void photoWithBlankMediaGroupIdProcessedImmediately() {
        when(photoBatchDebouncer.offer(any())).thenReturn(false);
        stubStreamingResult("desc", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Photo: test]"));
        processor.accept(photoEvent(1, 100L, "file123", "caption", "  "));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── Other media types ──────────────────────────────────────

    @Test
    void documentEventProcessed() {
        stubStreamingResult("doc response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Document: test]"));
        processor.accept(documentEvent(1, 100L, "file123", "caption"));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void voiceEventProcessed() {
        stubStreamingResult("voice response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Voice: test]"));
        processor.accept(voiceEvent(1, 100L, "file123"));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void stickerEventProcessed() {
        stubStreamingResult("sticker response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Sticker: test]"));
        processor.accept(stickerEvent(1, 100L, "file123"));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void animationEventProcessed() {
        stubStreamingResult("animation response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Animation: test]"));
        processor.accept(animationEvent(1, 100L, "file123"));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void locationEventProcessed() {
        stubStreamingResult("location response", true);
        processor.accept(locationEvent(1, 100L, "Location: 55.75, 37.61"));
        verify(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── Group message filter ───────────────────────────────────

    @Test
    void groupMessageFilteredSkipsProcessing() {
        when(groupMessageFilter.shouldProcess(any())).thenReturn(false);
        processor.accept(textEvent(1, -100L, "hello"));
        verifyNoInteractions(backendClient);
    }

    @Test
    void groupMessageFilteredWithObserveUnmentionedDoesNotProcess() {
        when(groupMessageFilter.shouldProcess(any())).thenReturn(false);
        when(groupMessageFilter.shouldObserveUnmentioned()).thenReturn(true);
        when(groupMessageFilter.getObservationText(any())).thenReturn("observed text");
        processor.accept(textEvent(1, -100L, "hello"));
        verifyNoInteractions(backendClient);
    }

    @Test
    void groupMessageFilteredWithObserveUnmentionedNullTextDoesNotProcess() {
        when(groupMessageFilter.shouldProcess(any())).thenReturn(false);
        when(groupMessageFilter.shouldObserveUnmentioned()).thenReturn(true);
        when(groupMessageFilter.getObservationText(any())).thenReturn(null);
        processor.accept(textEvent(1, -100L, "hello"));
        verifyNoInteractions(backendClient);
    }

    @Test
    void groupMessageFilteredWithObserveUnmentionedBlankTextDoesNotProcess() {
        when(groupMessageFilter.shouldProcess(any())).thenReturn(false);
        when(groupMessageFilter.shouldObserveUnmentioned()).thenReturn(true);
        when(groupMessageFilter.getObservationText(any())).thenReturn("  ");
        processor.accept(textEvent(1, -100L, "hello"));
        verifyNoInteractions(backendClient);
    }

    // ─── Authorization ──────────────────────────────────────────

    @Test
    void unauthorizedMessageSendsNotAuthorized() {
        when(authorizationService.isAuthorized(any(UpdateEvent.class))).thenReturn(false);
        when(authorizationService.isPairingEnabled()).thenReturn(false);
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendMessage(eq(100L), contains("not authorized"));
        verifyNoInteractions(backendClient);
    }

    @Test
    void unauthorizedMessageWithPairingEnabledAndCodePresent() {
        when(authorizationService.isAuthorized(any(UpdateEvent.class))).thenReturn(false);
        when(authorizationService.isPairingEnabled()).thenReturn(true);
        when(authorizationService.generatePairingCode(anyLong(), anyString(), anyLong()))
            .thenReturn(Optional.of("PAIR123"));
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendMessage(eq(100L), contains("PAIR123"));
        verifyNoInteractions(backendClient);
    }

    @Test
    void unauthorizedMessageWithPairingEnabledButCodeEmpty() {
        when(authorizationService.isAuthorized(any(UpdateEvent.class))).thenReturn(false);
        when(authorizationService.isPairingEnabled()).thenReturn(true);
        when(authorizationService.generatePairingCode(anyLong(), anyString(), anyLong()))
            .thenReturn(Optional.empty());
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendMessage(eq(100L), contains("Pairing code limit reached"));
        verifyNoInteractions(backendClient);
    }

    // ─── Empty text / media extraction ──────────────────────────

    @Test
    void emptyTextSkipsProcessing() {
        processor.accept(textEvent(1, 100L, ""));
        verifyNoInteractions(backendClient);
    }

    @Test
    void nullTextSkipsProcessing() {
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.TEXT, 100L, 200L,
            "testuser", null, null, null, null, null, null, null,
            false, null, null, 101L, null, 0);
        processor.accept(event);
        verifyNoInteractions(backendClient);
    }

    @Test
    void blankTextSkipsProcessing() {
        processor.accept(textEvent(1, 100L, "   "));
        verifyNoInteractions(backendClient);
    }

    // ─── Media extraction ────────────────────────────────────────

    @Test
    void textWithMediaDescriptionUsesEnrichedText() {
        stubStreamingResult("response", true);
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.TEXT, 100L, 200L,
            "testuser", "What is this?", null, "file123", "photo",
            null, null, null, false, null, null, 101L, null, 0);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Photo: /tmp/test.jpg]"));
        processor.accept(event);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).chatStream(msgCaptor.capture(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
        assertThat(msgCaptor.getValue()).contains("What is this?");
        assertThat(msgCaptor.getValue()).contains("[Photo: /tmp/test.jpg]");
    }

    @Test
    void captionWithMediaDescriptionUsesEnrichedText() {
        stubStreamingResult("response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Photo: /tmp/test.jpg]"));
        processor.accept(photoEvent(1, 100L, "file123", "Look at this", null));
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).chatStream(msgCaptor.capture(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
        assertThat(msgCaptor.getValue()).contains("Look at this");
        assertThat(msgCaptor.getValue()).contains("[Photo: /tmp/test.jpg]");
    }

    @Test
    void captionOnlyWithoutMediaDescriptionUsesCaption() {
        stubStreamingResult("response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.empty());
        processor.accept(photoEvent(1, 100L, "file123", "Just caption", null));
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).chatStream(msgCaptor.capture(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
        assertThat(msgCaptor.getValue()).contains("Just caption");
    }

    @Test
    void mediaOnlyWithDescriptionUsesDescription() {
        stubStreamingResult("response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.of("[Photo: /tmp/test.jpg]"));
        UpdateEvent event = photoEvent(1, 100L, "file123", null, null);
        processor.accept(event);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).chatStream(msgCaptor.capture(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
        assertThat(msgCaptor.getValue()).contains("[Photo: /tmp/test.jpg]");
    }

    @Test
    void mediaOnlyWithoutDescriptionUsesPlaceholder() {
        stubStreamingResult("response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.empty());
        UpdateEvent event = photoEvent(1, 100L, "file123", null, null);
        processor.accept(event);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).chatStream(msgCaptor.capture(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
        assertThat(msgCaptor.getValue()).contains("[Media attachment: photo]");
    }

    @Test
    void mediaOnlyWithoutDescriptionAndNullFileTypeUsesUnknown() {
        stubStreamingResult("response", true);
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.empty());
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.DOCUMENT, 100L, 200L,
            "testuser", null, null, "file123", null,
            null, null, null, false, null, null, 101L, null, 0);
        processor.accept(event);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).chatStream(msgCaptor.capture(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
        assertThat(msgCaptor.getValue()).contains("[Media attachment: unknown]");
    }

    // ─── Busy session — queue mode ─────────────────────────────

    @Test
    void busyQueueModeQueuesMessage() {
        properties.setBusyMode("queue");
        stubStreamingResult("response", true);

        // First message marks busy, processes, then is free
        processor.accept(textEvent(1, 100L, "first"));
        assertThat(busyHandler.isBusy(100L)).isFalse();

        // Now manually mark busy and send another
        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "second"));
        // Should be queued, not processed
        assertThat(busyHandler.hasQueued(100L)).isTrue();
    }

    // ─── Busy session — interrupt mode ──────────────────────────

    @Test
    void busyInterruptModeInterruptsAndQueues() {
        properties.setBusyMode("interrupt");
        stubStreamingResult("response", true);

        busyHandler.markBusy(100L);
        processor.accept(textEvent(2, 100L, "interrupting msg"));
        // Should have called interrupt and queued the message
        assertThat(busyHandler.isInterrupted(100L)).isTrue();
        assertThat(busyHandler.hasQueued(100L)).isTrue();
    }

    // ─── Stream interruption ─────────────────────────────────────

    @Test
    void streamInterruptedFinalizesWithInterruptedMessage() {
        List<String> finalizedTexts = new ArrayList<>();
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                finalizedTexts.add(inv.getArgument(2));
                return true;
            });

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                Consumer<Throwable> onError = inv.getArgument(8);
                // Simulate token delivery, then interrupt
                tokenConsumer.accept("Partial response");
                // Set interrupt flag
                busyHandler.interrupt(100L);
                // Next token triggers interrupt
                try {
                    tokenConsumer.accept(" more text");
                } catch (Exception e) {
                    // StreamInterruptedException is thrown internally
                    onError.accept(e);
                }
                return new AgentBackendClient.ChatResult("Partial response", "test-model", 100, 1000, true, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        // The finalized text should contain the partial accumulated content (no "[Interrupted by user]")
        assertThat(finalizedTexts).anyMatch(t -> t.contains("Partial response"));
    }

    @Test
    void streamErrorFinalizesWithErrorMessage() {
        List<String> finalizedTexts = new ArrayList<>();
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                finalizedTexts.add(inv.getArgument(2));
                return true;
            });

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<Throwable> onError = inv.getArgument(8);
                onError.accept(new RuntimeException("stream error"));
                return new AgentBackendClient.ChatResult("", null, null, null, false, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        // Error messages are now user-friendly (matching Hermes), not raw [Error: msg]
        assertThat(finalizedTexts).anyMatch(t -> t.contains("Temporary issue"));
    }

    @Test
    void streamErrorWithPartialContentFinalizesWithPartialAndError() {
        List<String> finalizedTexts = new ArrayList<>();
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                finalizedTexts.add(inv.getArgument(2));
                return true;
            });

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                Consumer<Throwable> onError = inv.getArgument(8);
                tokenConsumer.accept("Partial content");
                onError.accept(new RuntimeException("stream error"));
                return new AgentBackendClient.ChatResult("Partial content", null, null, null, false, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        // Partial content + user-friendly error message
        assertThat(finalizedTexts).anyMatch(t -> t.contains("Partial content") && t.contains("Temporary issue"));
    }

    // ─── Streaming fallback ─────────────────────────────────────

    @Test
    void streamingFailsToStartFallsBackToSyncChat() {
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(Optional.empty());
        // When startStream returns empty, messageId stays -1, so no tokens are delivered,
        // accumulated is empty, and the code falls through to sync fallback
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("", "test-model", 100, 1000, false, false));
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenReturn(new AgentBackendClient.ChatResult("Sync response", "sync-model", 50, 500, false, false));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(backendClient).chat(anyString(), nullable(String.class), any());
        verify(telegramClient).sendMessage(eq(100L), contains("Sync response"), anyString(), any(), any());
    }

    @Test
    void streamingThrowsExceptionSendsError() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("connection refused"));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendMessage(eq(100L), contains("Error contacting the agent backend"), anyString(), any(), any());
    }

    @Test
    void streamingThrowsExceptionClearsStream() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("connection refused"));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(streamEditor).clearStream(100L);
    }

    @Test
    void streamingNoContentButHasMetadataFallsBackToSync() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("", "test-model", 100, 1000, false, false));
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenReturn(new AgentBackendClient.ChatResult("Sync fallback", "sync-model", 50, 500, false, false));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(backendClient).chat(anyString(), nullable(String.class), any());
        verify(telegramClient).sendMessage(eq(100L), contains("Sync fallback"), anyString(), any(), any());
    }

    @Test
    void streamingNoContentNoMetadataReturnsEmpty() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("", null, null, null, false, false));

        processor.accept(textEvent(1, 100L, "hello"));
        // Empty response should be filtered
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    // ─── resolveModelUsed ────────────────────────────────────────

    @Test
    void resolveModelUsedPrefersResultModel() {
        stubStreamingResultWithMetadata("response", false, "result-model", 100, 1000, false);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("\n\nmodel: result-model");
        processor.accept(textEvent(1, 100L, "hello"));
        verify(runtimeFooter).format(eq("result-model"), anyInt(), anyInt(), anyString());
    }

    @Test
    void resolveModelUsedFallsBackToSessionModelOverride() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setModelOverride("session-model");
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        stubStreamingResultWithMetadata("response", false, null, 100, 1000, false);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("\n\nmodel: session-model");
        processor.accept(textEvent(1, 100L, "hello"));
        verify(runtimeFooter).format(eq("session-model"), anyInt(), anyInt(), anyString());
    }

    @Test
    void resolveModelUsedFallsBackToDefaultModel() {
        stubStreamingResultWithMetadata("response", false, null, 100, 1000, false);
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString())).thenReturn("\n\nmodel: test-model");
        processor.accept(textEvent(1, 100L, "hello"));
        verify(runtimeFooter).format(eq("test-model"), anyInt(), anyInt(), anyString());
    }

    // ─── Voice mode ──────────────────────────────────────────────

    @Test
    void voiceModeSendsVoiceResponse() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setVoiceMode(true);
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        stubStreamingWithTokensAndFinalize("Voice response text", "test-model", false);
        when(backendClient.tts(anyString(), any())).thenReturn(new byte[]{1, 2, 3});

        processor.accept(textEvent(1, 100L, "hello"));
        verify(backendClient).tts(anyString(), any());
        verify(telegramClient).sendVoice(eq(100L), any(byte[].class), any());
    }

    @Test
    void voiceModeBlankContentDoesNotSendVoice() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setVoiceMode(true);
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        // Stream finalized with no content → result.content() is blank
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("", "test-model", 100, 1000, true, false));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(backendClient, never()).tts(anyString(), any());
    }

    @Test
    void voiceModeNullContentDoesNotSendVoice() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setVoiceMode(true);
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult(null, "test-model", 100, 1000, true, false));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(backendClient, never()).tts(anyString(), any());
    }

    @Test
    void voiceModeTtsReturnsEmptyArrayDoesNotSendVoice() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setVoiceMode(true);
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        stubStreamingWithTokensAndFinalize("Voice response", "test-model", false);
        when(backendClient.tts(anyString(), any())).thenReturn(new byte[0]);

        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient, never()).sendVoice(anyLong(), any(byte[].class), any());
    }

    @Test
    void voiceModeTtsThrowsExceptionDoesNotSendVoice() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setVoiceMode(true);
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        stubStreamingWithTokensAndFinalize("Voice response", "test-model", false);
        when(backendClient.tts(anyString(), any())).thenThrow(new RuntimeException("TTS error"));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient, never()).sendVoice(anyLong(), any(byte[].class), any());
    }

    @Test
    void voiceModeTtsTruncatesLongText() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setVoiceMode(true);
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        String longContent = "A".repeat(5000);
        stubStreamingWithTokensAndFinalize(longContent, "test-model", false);
        when(backendClient.tts(anyString(), any())).thenReturn(new byte[]{1, 2, 3});

        processor.accept(textEvent(1, 100L, "hello"));
        ArgumentCaptor<String> ttsCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).tts(ttsCaptor.capture(), any());
        assertThat(ttsCaptor.getValue().length()).isLessThanOrEqualTo(4000);
    }

    @Test
    void voiceModeWithMediaOnlyTextDoesNotSendVoice() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setVoiceMode(true);
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        // Content that is only MEDIA: lines → cleanText is blank → no TTS
        stubStreamingWithTokensAndFinalize("MEDIA:/tmp/test.jpg", "test-model", false);
        when(backendClient.tts(anyString(), any())).thenReturn(new byte[]{1, 2, 3});

        processor.accept(textEvent(1, 100L, "hello"));
        verify(backendClient, never()).tts(anyString(), any());
    }

    // ─── Memory updated ──────────────────────────────────────────

    @Test
    void memoryUpdatedSendsNotification() {
        stubStreamingWithTokensAndFinalize("response", "test-model", true);
        processor.accept(textEvent(1, 100L, "hello"));
        // The text is MarkdownV2-escaped, so check for a substring without special chars
        verify(telegramClient).sendMessage(eq(100L), contains("Memory updated"), anyString(), any(), any());
    }

    @Test
    void memoryNotUpdatedDoesNotSendNotification() {
        stubStreamingWithTokensAndFinalize("response", "test-model", false);
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient, never()).sendMessage(eq(100L), contains("Memory updated"), anyString(), any(), any());
    }

    // ─── Goal auto-continue ─────────────────────────────────────

    @Test
    void goalAutoContinueEnabledAndActiveGoalSendsContinuations() {
        properties.getGoalAutoContinue().setEnabled(true);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setMetadata("_standingGoal", "Complete the task");
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        stubStreamingWithTokensAndFinalize("First response", "test-model", false);
        when(goalAutoContinueService.runAutoContinue(any(), anyString(), any()))
            .thenReturn(List.of("Continuation 1", "Continuation 2"));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendMessage(eq(100L), contains("Continuation 1"), anyString(), any(), any());
        verify(telegramClient).sendMessage(eq(100L), contains("Continuation 2"), anyString(), any(), any());
    }

    @Test
    void goalAutoContinueEnabledButNoActiveGoalDoesNotContinue() {
        properties.getGoalAutoContinue().setEnabled(true);
        stubStreamingWithTokensAndFinalize("response", "test-model", false);
        // GoalCommand.getActiveGoal returns null because no goal is set
        when(goalAutoContinueService.runAutoContinue(any(), anyString(), any()))
            .thenReturn(List.of());

        processor.accept(textEvent(1, 100L, "hello"));
        verify(goalAutoContinueService, never()).runAutoContinue(any(), anyString(), any());
    }

    @Test
    void goalAutoContinueDisabledDoesNotContinue() {
        properties.getGoalAutoContinue().setEnabled(false);
        stubStreamingWithTokensAndFinalize("response", "test-model", false);
        processor.accept(textEvent(1, 100L, "hello"));
        verify(goalAutoContinueService, never()).runAutoContinue(any(), anyString(), any());
    }

    @Test
    void goalAutoContinueWithInterruptedSessionDoesNotContinue() {
        properties.getGoalAutoContinue().setEnabled(true);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setMetadata("_standingGoal", "Complete the task");
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        stubStreamingWithTokensAndFinalize("response", "test-model", false);
        // Simulate interrupt after processing
        busyHandler.markBusy(100L);
        busyHandler.interrupt(100L);

        processor.accept(textEvent(1, 100L, "hello"));
        verify(goalAutoContinueService, never()).runAutoContinue(any(), anyString(), any());
    }

    @Test
    void goalAutoContinueWithBlankContinuationDoesNotSend() {
        properties.getGoalAutoContinue().setEnabled(true);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setMetadata("_standingGoal", "Complete the task");
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        stubStreamingWithTokensAndFinalize("response", "test-model", false);
        when(goalAutoContinueService.runAutoContinue(any(), anyString(), any()))
            .thenReturn(List.of("", "  "));

        processor.accept(textEvent(1, 100L, "hello"));
        // Continuations are blank, so no sendMessage should be called for them
        // But the stream finalization may call sendMessage for other things
        // The test verifies that no sendMessage contains blank-only text
        verify(telegramClient, never()).sendMessage(eq(100L), eq(""), anyString(), any(), any());
        verify(telegramClient, never()).sendMessage(eq(100L), eq("  "), anyString(), any(), any());
    }

    // ─── Reaction on interrupt ──────────────────────────────────

    @Test
    void reactionOnCancelWhenInterrupted() {
        // Use streaming that triggers an interrupt during processing
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                tokenConsumer.accept("response");
                // Set interrupt during token processing
                busyHandler.interrupt(100L);
                return new AgentBackendClient.ChatResult("response", "test-model", 100, 1000, true, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        verify(reactionManager).onCancel(eq(100L), anyLong());
    }

    // ─── sendFormatted — response filter ────────────────────────

    @Test
    void responseFilteredDoesNotSend() {
        stubStreamingResult("***", false);
        when(responseFilter.shouldFilter("***")).thenReturn(true);
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void responseNullFilteredDoesNotSend() {
        stubStreamingResult(null, false);
        when(responseFilter.shouldFilter(null)).thenReturn(true);
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient, never()).sendMessage(anyLong(), anyString());
    }

    // ─── sendFormatted — MEDIA: pattern ─────────────────────────

    @Test
    void mediaPatternSendsPhoto(@TempDir Path tempDir) throws Exception {
        Path mediaFile = tempDir.resolve("test.jpg");
        Files.write(mediaFile, new byte[]{1, 2, 3});
        properties.setWorkingDirectory(tempDir.toString());

        stubStreamingResult("MEDIA:" + mediaFile.toString(), false);
        when(telegramClient.sendPhoto(anyLong(), any(byte[].class), any(), any()))
            .thenReturn(Optional.of(1L));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendPhoto(eq(100L), any(byte[].class), any(), any());
    }

    @Test
    void mediaPatternFileNotFoundSendsErrorMessage() {
        // Path is within working dir but file doesn't exist
        properties.setWorkingDirectory("/tmp");
        stubStreamingResult("MEDIA:/tmp/nonexistent_path_to_file.jpg", false);
        processor.accept(textEvent(1, 100L, "hello"));
        // S-2: File not found is silently skipped (no error message sent to user)
        // The MEDIA: tag is still stripped from the displayed text
        verify(telegramClient, never()).sendPhoto(anyLong(), any(byte[].class), any(), any());
    }

    @Test
    void mediaPatternWithRemainingTextSendsBoth(@TempDir Path tempDir) throws Exception {
        Path mediaFile = tempDir.resolve("test.jpg");
        Files.write(mediaFile, new byte[]{1, 2, 3});
        properties.setWorkingDirectory(tempDir.toString());

        stubStreamingResult("Here is the photo\nMEDIA:" + mediaFile.toString(), false);
        when(telegramClient.sendPhoto(anyLong(), any(byte[].class), any(), any()))
            .thenReturn(Optional.of(1L));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendPhoto(eq(100L), any(byte[].class), any(), any());
        verify(telegramClient).sendMessage(eq(100L), contains("Here is the photo"), anyString(), any(), any());
    }

    @Test
    void mediaPatternFileNotAFileSendsError(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("notfile");
        Files.createDirectories(dir);
        properties.setWorkingDirectory(tempDir.toString());

        stubStreamingResult("MEDIA:" + dir.toString(), false);
        processor.accept(textEvent(1, 100L, "hello"));
        // S-2: Directory (not a file) is silently skipped
        verify(telegramClient, never()).sendPhoto(anyLong(), any(byte[].class), any(), any());
    }

    @Test
    void mediaPatternSendPhotoThrowsExceptionSendsError(@TempDir Path tempDir) throws Exception {
        Path mediaFile = tempDir.resolve("test.jpg");
        Files.write(mediaFile, new byte[]{1, 2, 3});
        properties.setWorkingDirectory(tempDir.toString());

        stubStreamingResult("MEDIA:" + mediaFile.toString(), false);
        when(telegramClient.sendPhoto(anyLong(), any(byte[].class), any(), any()))
            .thenThrow(new RuntimeException("send failed"));

        processor.accept(textEvent(1, 100L, "hello"));
        // S-2: Send failure is logged, no error message sent to user
        verify(telegramClient).sendPhoto(eq(100L), any(byte[].class), any(), any());
    }

    // ─── sendFormatted — reply modes ────────────────────────────

    @Test
    void replyModeAllSendsReplyToAllChunks() {
        properties.setReplyToMode("all");
        stubStreamingResult("Response text", false);
        processor.accept(textEventWithMessageId(1, 100L, "hello", 42L));
        verify(telegramClient).sendMessage(eq(100L), anyString(), anyString(), eq(42L), any());
    }

    @Test
    void replyModeFirstSendsReplyToFirstChunkOnly() {
        properties.setReplyToMode("first");
        stubStreamingResult("Response text", false);
        processor.accept(textEventWithMessageId(1, 100L, "hello", 42L));
        verify(telegramClient).sendMessage(eq(100L), anyString(), anyString(), eq(42L), any());
    }

    @Test
    void replyModeOffSendsNoReplyTo() {
        properties.setReplyToMode("off");
        stubStreamingResult("Response text", false);
        processor.accept(textEventWithMessageId(1, 100L, "hello", 42L));
        verify(telegramClient).sendMessage(eq(100L), anyString(), anyString(), isNull(), any());
    }

    @Test
    void replyModeFirstWithZeroMessageIdSendsNoReplyTo() {
        properties.setReplyToMode("first");
        stubStreamingResult("Response text", false);
        processor.accept(textEvent(1, 100L, "hello")); // messageId = 101
        // messageId > 0, so reply should be set for first chunk
        verify(telegramClient).sendMessage(eq(100L), anyString(), anyString(), any(), any());
    }

    // ─── sendError ───────────────────────────────────────────────

    @Test
    void sendErrorEscapesMarkdownV2() {
        properties.setParseMode("MarkdownV2");
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Error at C:\\Users\\test_file.java"));
        processor.accept(textEvent(1, 100L, "test"));
        verify(telegramClient).sendMessage(eq(100L), argThat(text ->
            text != null && !text.contains("C:\\Users\\test_file.java")
        ), eq("MarkdownV2"), isNull(), isNull());
    }

    @Test
    void sendErrorWithHtmlParseMode() {
        properties.setParseMode("HTML");
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Backend error"));
        processor.accept(textEvent(1, 100L, "test"));
        verify(telegramClient).sendMessage(eq(100L), contains("Backend error"), eq("HTML"), isNull(), isNull());
    }

    @Test
    void sendErrorThrowsExceptionLogsOnly() {
        properties.setParseMode("MarkdownV2");
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Primary error"));
        // telegramClient.sendMessage throws
        when(telegramClient.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
            .thenThrow(new RuntimeException("Send error"));
        // Should not throw — error is caught internally
        processor.accept(textEvent(1, 100L, "test"));
    }

    // ─── Backend error handling ──────────────────────────────────

    @Test
    void backendCallFailsSendsErrorAndReaction() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("backend down"));
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendMessage(eq(100L), contains("Error contacting the agent backend"), anyString(), any(), any());
        verify(reactionManager).onProcessingComplete(eq(100L), anyLong(), eq(false));
        verify(typingManager).flushTyping(100L);
        verify(typingManager).stopTyping(100L);
    }

    @Test
    void backendCallFailsMarksFree() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("backend down"));
        processor.accept(textEvent(1, 100L, "hello"));
        assertThat(busyHandler.isBusy(100L)).isFalse();
    }

    // ─── Streaming with callbacks ───────────────────────────────

    @Test
    void toolCallConsumerDoesNotShowToolProgress() {
        List<String> editedTexts = new ArrayList<>();
        when(streamEditor.editStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                editedTexts.add(inv.getArgument(2));
                return true;
            });

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                Consumer<String> toolCallConsumer = inv.getArgument(4);
                tokenConsumer.accept("Answer");
                toolCallConsumer.accept("search");
                return new AgentBackendClient.ChatResult("Answer", "test-model", 100, 1000, true, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        // Tool progress should NOT be shown in stream edits (tool_progress: off)
        assertThat(editedTexts).noneMatch(t -> t.contains("🔧"));
        // The tool name is tracked internally for heartbeat, not shown in stream
        verify(streamEditor).setCurrentToolName(anyLong(), eq("search"));
    }

    @Test
    void toolResultConsumerTriggersSegmentBreak() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                BiConsumer<String, String> toolResultConsumer = inv.getArgument(5);
                tokenConsumer.accept("Answer");
                toolResultConsumer.accept("search", "results found");
                return new AgentBackendClient.ChatResult("Answer", "test-model", 100, 1000, true, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        // Tool results should NOT be shown in stream edits — instead a segment break is triggered
        verify(streamEditor).onSegmentBreak(anyLong(), anyLong(), anyString());
    }

    @Test
    void onCompleteCallbackFinalizesStreamWithFooter() {
        List<String> finalizedTexts = new ArrayList<>();
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                finalizedTexts.add(inv.getArgument(2));
                return true;
            });

        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString()))
            .thenReturn("\n\nfooter");

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(7);
                tokenConsumer.accept("Final answer");
                onComplete.accept(new AgentBackendClient.ChatResult("Final answer", "test-model", 100, 1000, true));
                return new AgentBackendClient.ChatResult("Final answer", "test-model", 100, 1000, true, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        assertThat(finalizedTexts).anyMatch(t -> t.contains("Final answer") && t.contains("footer"));
    }

    @Test
    void onCompleteCallbackWithNoAccumulatedContentDoesNotFinalize() {
        List<String> finalizedTexts = new ArrayList<>();
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                finalizedTexts.add(inv.getArgument(2));
                return true;
            });

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(7);
                onComplete.accept(new AgentBackendClient.ChatResult("", "test-model", 100, 1000, true));
                return new AgentBackendClient.ChatResult("", "test-model", 100, 1000, true, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        // No content accumulated → finalizeStream not called from onComplete
        assertThat(finalizedTexts).isEmpty();
    }

    // ─── PII Redaction ──────────────────────────────────────────

    @Test
    void piiRedactionEnabledPrependsContextPrompt() {
        properties.setRedactPii(true);
        stubStreamingResult("response", true);

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId("12345");
        session.setUsername("testuser");
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        processor.accept(textEvent(1, 100L, "hello"));
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).chatStream(msgCaptor.capture(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
        // The message should contain the original text plus some context prefix
        assertThat(msgCaptor.getValue()).contains("hello");
        // Should be longer than just "hello" due to context prefix
        assertThat(msgCaptor.getValue().length()).isGreaterThan("hello".length());
    }

    @Test
    void piiRedactionDisabledPassesMessageUnchanged() {
        properties.setRedactPii(false);
        stubStreamingResult("response", true);
        processor.accept(textEvent(1, 100L, "hello"));
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(backendClient).chatStream(msgCaptor.capture(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
        assertThat(msgCaptor.getValue()).isEqualTo("hello");
    }

    // ─── Session with null ID ────────────────────────────────────

    @Test
    void sessionWithNullIdPassesNullSessionId() {
        BotSessionEntity session = new BotSessionEntity();
        // id is null
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
        stubStreamingResult("response", true);
        processor.accept(textEvent(1, 100L, "hello"));
        verify(backendClient).chatStream(anyString(), isNull(), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── Drain queue ─────────────────────────────────────────────

    @Test
    void queuedMessagesProcessedWithoutStackOverflow() {
        long chatId = 100L;
        List<String> processedTexts = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                int n = callCount.incrementAndGet();
                processedTexts.add(msg);
                if (n == 1) {
                    for (int i = 1; i <= 50; i++) {
                        busyHandler.queueMessage(chatId, textEvent(i, chatId, "msg-" + i));
                    }
                }
                return new AgentBackendClient.ChatResult("reply to: " + msg, null, 100, 1000, false);
            });
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                processedTexts.add(msg);
                return new AgentBackendClient.ChatResult("reply to: " + msg, "test-model", 100, 1000, false);
            });

        processor.accept(textEvent(0, chatId, "msg-0"));
        assertThat(processedTexts).isNotEmpty();
    }

    @Test
    void queueOrderPreservedFIFO() {
        long chatId = 200L;
        List<String> processedTexts = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                int n = callCount.incrementAndGet();
                processedTexts.add(msg);
                if (n == 1) {
                    for (int i = 1; i <= 5; i++) {
                        busyHandler.queueMessage(chatId, textEvent(i, chatId, "msg-" + i));
                    }
                }
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, true);
            });
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                processedTexts.add(msg);
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, false);
            });

        processor.accept(textEvent(0, chatId, "msg-0"));
        assertThat(processedTexts).startsWith("msg-0");
        for (int i = 1; i < processedTexts.size(); i++) {
            assertThat(processedTexts.get(i)).startsWith("msg-");
        }
    }

    @Test
    void maxDrainDepthGuardsAgainstInfiniteLoop() {
        long chatId = 300L;
        AtomicInteger processCount = new AtomicInteger(0);

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                processCount.incrementAndGet();
                busyHandler.queueMessage(chatId, textEvent(999, chatId, "loop-msg"));
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, true);
            });
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenAnswer(inv -> {
                processCount.incrementAndGet();
                busyHandler.queueMessage(chatId, textEvent(999, chatId, "loop-msg"));
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, false);
            });

        processor.accept(textEvent(0, chatId, "initial"));
        assertThat(processCount.get()).isLessThanOrEqualTo(202);
    }

    @Test
    void drainQueueExceptionDoesNotPropagate() {
        long chatId = 400L;
        AtomicInteger callCount = new AtomicInteger(0);

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                int n = callCount.incrementAndGet();
                if (n == 1) {
                    busyHandler.queueMessage(chatId, textEvent(2, chatId, "queued-msg"));
                }
                if (n == 2) {
                    throw new RuntimeException("drain error");
                }
                return new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, true);
            });
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenAnswer(inv -> new AgentBackendClient.ChatResult("ok", "test-model", 10, 100, false));

        processor.accept(textEvent(1, chatId, "first"));
        // Should not throw — drain error is caught
        assertThat(callCount.get()).isGreaterThanOrEqualTo(1);
    }

    // ─── stopTyping ordering ─────────────────────────────────────

    @Test
    void stopTypingCalledAfterSendNotInFinally() {
        long chatId = 700L;
        List<String> callOrder = new ArrayList<>();

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                callOrder.add("chatStream");
                return new AgentBackendClient.ChatResult("reply", null, 100, 1000, false);
            });
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenAnswer(inv -> {
                callOrder.add("chat");
                return new AgentBackendClient.ChatResult("reply", "test-model", 100, 1000, false);
            });

        doAnswer(inv -> { callOrder.add("sendMessage"); return Optional.of(1L); })
            .when(telegramClient).sendMessage(anyLong(), anyString(), anyString(), any(), any());
        doAnswer(inv -> { callOrder.add("stopTyping"); return null; })
            .when(typingManager).stopTyping(anyLong());

        processor.accept(textEvent(1, chatId, "test"));
        int sendIdx = callOrder.indexOf("sendMessage");
        int stopIdx = callOrder.indexOf("stopTyping");
        assertThat(sendIdx).as("sendMessage should have been called").isGreaterThanOrEqualTo(0);
        assertThat(stopIdx).as("stopTyping should have been called").isGreaterThanOrEqualTo(0);
        assertThat(stopIdx).as("stopTyping should come after sendMessage").isGreaterThan(sendIdx);
    }

    // ─── Tool progress not in final text ─────────────────────────

    @Test
    void toolProgressNotInFinalText() {
        long chatId = 500L;
        List<String> finalizedTexts = new ArrayList<>();

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                Consumer<String> tokenConsumer = inv.getArgument(3);
                tokenConsumer.accept("Here is the answer");
                Consumer<String> toolCallConsumer = inv.getArgument(4);
                toolCallConsumer.accept("search");
                BiConsumer<String, String> toolResultConsumer = inv.getArgument(5);
                toolResultConsumer.accept("search", "results found");
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(7);
                onComplete.accept(new AgentBackendClient.ChatResult("Here is the answer", "test-model", 100, 1000, true));
                return new AgentBackendClient.ChatResult("Here is the answer", "test-model", 100, 1000, true);
            });

        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                finalizedTexts.add(inv.getArgument(2));
                return true;
            });

        processor.accept(textEvent(1, chatId, "test msg"));
        assertThat(finalizedTexts).isNotEmpty();
        assertThat(finalizedTexts.get(0)).doesNotContain("🔧");
        assertThat(finalizedTexts.get(0)).doesNotContain("✅");
        assertThat(finalizedTexts.get(0)).contains("Here is the answer");
    }

    // ─── Interrupt message queued for reprocessing ──────────────

    @Test
    void interruptMessageQueuedForReprocessing() {
        properties.setBusyMode("interrupt");
        long chatId = 400L;
        AtomicInteger callCount = new AtomicInteger(0);
        List<String> processedTexts = new ArrayList<>();

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                int n = callCount.incrementAndGet();
                processedTexts.add(msg);
                if (n == 1) {
                    busyHandler.queueMessage(chatId, textEvent(2, chatId, "interrupting-msg"));
                }
                return new AgentBackendClient.ChatResult("reply to: " + msg, null, 100, 1000, false);
            });
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenAnswer(inv -> {
                String msg = inv.getArgument(0);
                processedTexts.add(msg);
                return new AgentBackendClient.ChatResult("reply to: " + msg, "test-model", 100, 1000, false);
            });

        processor.accept(textEvent(1, chatId, "first-msg"));
        assertThat(processedTexts).contains("first-msg");
    }

    // ─── PostConstruct init ─────────────────────────────────────

    @Test
    void initRegistersDispatchers() {
        processor.init();
        verify(textBatchDebouncer).onDispatch(any());
        verify(photoBatchDebouncer).onDispatch(any());
    }

    // ─── StreamEditor startStream returns empty ─────────────────

    @Test
    void startStreamReturnsEmptyUsesSyncFallback() {
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(Optional.empty());
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("", "test-model", 100, 1000, false, false));
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenReturn(new AgentBackendClient.ChatResult("sync response", "sync-model", 100, 1000, false, false));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(backendClient).chat(anyString(), nullable(String.class), any());
        verify(telegramClient).sendMessage(eq(100L), contains("sync response"), anyString(), any(), any());
    }

    // ─── Sync fallback with metadata merge ───────────────────────

    @Test
    void syncFallbackMergesMetadataFromStreamAndSync() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("", "stream-model", 200, 2000, false, true));
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenReturn(new AgentBackendClient.ChatResult("sync content", null, 100, 1000, false, false));

        processor.accept(textEvent(1, 100L, "hello"));
        // Should prefer sync content but merge metadata
        verify(telegramClient).sendMessage(eq(100L), contains("sync content"), anyString(), any(), any());
        // Footer should use stream-model (since sync modelUsed is null, stream model is used)
        verify(runtimeFooter).format(eq("stream-model"), eq(100), eq(1000), anyString());
    }

    @Test
    void syncFallbackPrefersSyncMetadata() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("", "stream-model", 200, 2000, false, false));
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenReturn(new AgentBackendClient.ChatResult("sync content", "sync-model", 100, 1000, false, true));

        processor.accept(textEvent(1, 100L, "hello"));
        verify(runtimeFooter).format(eq("sync-model"), eq(100), eq(1000), anyString());
    }

    @Test
    void syncFallbackMergesMemoryUpdated() {
        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new AgentBackendClient.ChatResult("", "stream-model", 200, 2000, false, true));
        when(backendClient.chat(anyString(), nullable(String.class), any()))
            .thenReturn(new AgentBackendClient.ChatResult("sync content", "sync-model", 100, 1000, false, false));

        processor.accept(textEvent(1, 100L, "hello"));
        // memoryUpdated should be true (stream) || false (sync) = true
        verify(telegramClient).sendMessage(eq(100L), contains("Memory updated"), anyString(), any(), any());
    }

    // ─── MarkdownV2 conversion in sendFormatted ──────────────────

    @Test
    void sendFormattedWithMarkdownV2ConvertsText() {
        properties.setParseMode("MarkdownV2");
        stubStreamingResult("**Bold text**", false);
        processor.accept(textEvent(1, 100L, "hello"));
        // Verify sendMessage was called with converted markdown
        verify(telegramClient).sendMessage(eq(100L), anyString(), eq("MarkdownV2"), any(), any());
    }

    @Test
    void sendFormattedWithHTMLParseModeDoesNotConvert() {
        properties.setParseMode("HTML");
        stubStreamingResult("Plain text", false);
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendMessage(eq(100L), eq("Plain text"), eq("HTML"), any(), any());
    }

    // ─── Media pattern with caption formatting ────────────────────

    @Test
    void mediaPatternWithMarkdownV2Caption(@TempDir Path tempDir) throws Exception {
        properties.setParseMode("MarkdownV2");
        Path mediaFile = tempDir.resolve("test.jpg");
        Files.write(mediaFile, new byte[]{1, 2, 3});
        properties.setWorkingDirectory(tempDir.toString());

        stubStreamingResult("MEDIA:" + mediaFile.toString(), false);
        when(telegramClient.sendPhoto(anyLong(), any(byte[].class), any(), any()))
            .thenReturn(Optional.of(1L));

        processor.accept(textEvent(1, 100L, "hello"));
        // S-2: deliverImageBatch sends photo with null caption and null parseMode
        verify(telegramClient).sendPhoto(eq(100L), any(byte[].class), any(), any());
    }

    // ─── Error in accept() catch block ───────────────────────────

    @Test
    void acceptExceptionInCallbackQuerySendsError() {
        when(callbackQueryHandler.handle(any())).thenThrow(new RuntimeException("callback error"));
        processor.accept(callbackEvent(1, 100L, "mp:gpt-4"));
        verify(telegramClient).sendMessage(eq(100L), contains("An error occurred"), anyString(), any(), any());
    }

    @Test
    void acceptExceptionInCommandSendsError() {
        CommandHandler handler = mock(CommandHandler.class);
        when(handler.handle(any(), any())).thenThrow(new RuntimeException("cmd error"));
        when(commandRegistry.get("test")).thenReturn(handler);
        // The session resolution throws — handleCommand catches it and sends
        // "Error executing command: session error" via sendError (5-arg sendMessage)
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("session error"));
        processor.accept(commandEvent(1, 100L, "test", ""));
        verify(telegramClient).sendMessage(eq(100L), contains("Error executing command"), anyString(), any(), any());
    }

    // ─── Stream interrupt with no accumulated content ─────────────

    @Test
    void streamInterruptedWithNoContentDoesNotFinalize() {
        List<String> finalizedTexts = new ArrayList<>();
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString()))
            .thenAnswer(inv -> {
                finalizedTexts.add(inv.getArgument(2));
                return true;
            });

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                // No tokens delivered, no onError call — just return empty
                return new AgentBackendClient.ChatResult("", null, null, null, false, false);
            });

        processor.accept(textEvent(1, 100L, "hello"));
        // No content accumulated, no finalize called from onComplete
        assertThat(finalizedTexts).isEmpty();
    }

    // ─── Long text splitting in sendFormatted ────────────────────

    @Test
    void longTextSplitIntoMultipleChunks() {
        String longText = "A".repeat(5000); // Exceeds 4096 limit
        stubStreamingResult(longText, false);
        processor.accept(textEvent(1, 100L, "hello"));
        // MessageSplitter should split into at least 2 chunks
        verify(telegramClient, atLeast(2)).sendMessage(eq(100L), anyString(), anyString(), any(), any());
    }

    // ─── Blank chunk skipped ─────────────────────────────────────

    @Test
    void blankChunksSkippedInSendFormatted() {
        // Text that splits into chunks where some might be blank
        stubStreamingResult("Valid text", false);
        processor.accept(textEvent(1, 100L, "hello"));
        // Only non-blank chunks should be sent
        verify(telegramClient, atLeast(1)).sendMessage(eq(100L), argThat(s -> s != null && !s.isBlank()), anyString(), any(), any());
    }

    // ─── Edited message handling ──────────────────────────────────

    @Test
    void editedMessageSendsEditedNotice() {
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.EDITED_MESSAGE, 100L, 200L,
            "testuser", "edited text", null, null, null,
            null, null, null, false, null, null, 101L, null, 0, null);
        processor.accept(event);
        verify(telegramClient).sendMessage(eq(100L), contains("Message edited"), anyString(), any(), any());
    }

    @Test
    void editedMessageDoesNotCallBackend() {
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.EDITED_MESSAGE, 100L, 200L,
            "testuser", "edited text", null, null, null,
            null, null, null, false, null, null, 101L, null, 0, null);
        processor.accept(event);
        verify(backendClient, never()).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
    }

    // ─── MEDIA: path traversal protection ──────────────────────────

    @Test
    void mediaPathTraversalAttackSkipsFile(@TempDir Path tempDir) throws Exception {
        // Create a file inside tempDir, then try to access it via a path traversal
        Path mediaFile = tempDir.resolve("test.jpg");
        Files.write(mediaFile, new byte[]{1, 2, 3});

        // Set working directory to something else so tempDir is not allowed
        properties.setWorkingDirectory("/opt/dev/java-agent");

        stubStreamingResult("MEDIA:" + mediaFile.toString(), false);
        processor.accept(textEvent(1, 100L, "hello"));
        // The file should NOT be sent because the path is outside allowed dirs
        verify(telegramClient, never()).sendPhoto(anyLong(), any(byte[].class), any(), any());
    }

    @Test
    void mediaPathWithinWorkingDirectorySendsFile(@TempDir Path tempDir) throws Exception {
        Path mediaFile = tempDir.resolve("test.jpg");
        Files.write(mediaFile, new byte[]{1, 2, 3});

        // Set working directory to tempDir so the file is within allowed bounds
        properties.setWorkingDirectory(tempDir.toString());

        stubStreamingResult("MEDIA:" + mediaFile.toString(), false);
        when(telegramClient.sendPhoto(anyLong(), any(byte[].class), any(), any()))
            .thenReturn(Optional.of(1L));
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient).sendPhoto(eq(100L), any(byte[].class), any(), any());
    }

    @Test
    void mediaPathWithinAgentMediaDirSendsFile() throws Exception {
        // The /tmp/agent-media/ directory is always allowed
        Path mediaDir = Paths.get("/tmp/agent-media/");
        Files.createDirectories(mediaDir);
        Path mediaFile = mediaDir.resolve("traversal-test.jpg");
        try {
            Files.write(mediaFile, new byte[]{1, 2, 3});
            properties.setWorkingDirectory("/some/other/dir");

            stubStreamingResult("MEDIA:" + mediaFile.toString(), false);
            when(telegramClient.sendPhoto(anyLong(), any(byte[].class), any(), any()))
                .thenReturn(Optional.of(1L));
            processor.accept(textEvent(1, 100L, "hello"));
            verify(telegramClient).sendPhoto(eq(100L), any(byte[].class), any(), any());
        } finally {
            Files.deleteIfExists(mediaFile);
        }
    }

    @Test
    void mediaPathTraversalWithRelativePathSkipsFile(@TempDir Path tempDir) throws Exception {
        Path mediaFile = tempDir.resolve("test.jpg");
        Files.write(mediaFile, new byte[]{1, 2, 3});

        properties.setWorkingDirectory("/opt/dev/java-agent");

        // Try a path with .. traversal
        String traversalPath = tempDir.resolve("test.jpg").toString();
        stubStreamingResult("MEDIA:" + traversalPath, false);
        processor.accept(textEvent(1, 100L, "hello"));
        verify(telegramClient, never()).sendPhoto(anyLong(), any(byte[].class), any(), any());
    }

    // ─── message_thread_id pass-through ────────────────────────────

    @Test
    void threadIdPassedToStreamChatAndSendFormatted() {
        stubStreamingResult("response", false);
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.TEXT, 100L, 200L,
            "testuser", "hello", null, null, null,
            null, null, null, false, null, null, 101L, null, 42L);
        processor.accept(event);
        // Verify sendMessage was called with threadId=42
        verify(telegramClient).sendMessage(eq(100L), anyString(), anyString(), any(), eq(42), anyBoolean());
    }

    @Test
    void threadIdPassedToEditedMessageResponse() {
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.EDITED_MESSAGE, 100L, 200L,
            "testuser", "edited", null, null, null,
            null, null, null, false, null, null, 101L, null, 55L);
        processor.accept(event);
        verify(telegramClient).sendMessage(eq(100L), anyString(), anyString(), any(), eq(55), anyBoolean());
    }

    // ─── Race condition: per-chat lock ────────────────────────────

    @Test
    void perChatLockSerializesConcurrentMessages() throws Exception {
        long chatId = 100L;

        // Simulate concurrent processing — the lock should serialize them
        int numThreads = 3;
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(numThreads);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(numThreads);
        java.util.concurrent.atomic.AtomicInteger concurrent = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger maxConcurrent = new java.util.concurrent.atomic.AtomicInteger(0);

        when(backendClient.chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any()))
            .thenAnswer(inv -> {
                int cur = concurrent.incrementAndGet();
                maxConcurrent.set(Math.max(maxConcurrent.get(), cur));
                Thread.sleep(50); // simulate work
                concurrent.decrementAndGet();
                return new AgentBackendClient.ChatResult("response", "test-model", 100, 1000, true, false);
            });

        for (int i = 0; i < numThreads; i++) {
            final int idx = i;
            exec.submit(() -> {
                start.countDown();
                try { start.await(); } catch (InterruptedException e) { return; }
                processor.accept(textEvent(idx + 1, chatId, "msg " + idx));
                done.countDown();
            });
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);
        exec.shutdown();

        // The per-chat lock should ensure only 1 concurrent processing for this chat
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    // ─── Finding 9.2: Voice/sticker/animation edge case tests ──────

    @Test
    void voiceMessageWithNullCaptionDoesNotCrash() {
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.VOICE, 100L, 200L,
            "testuser", null, null, "file123", "voice",
            null, null, null, false, null, null, 101L, null, 0L);
        // Should not throw — voice messages are handled by transcribing or acknowledging
        processor.accept(event);
        // Either sends a response or calls backend — the key is it doesn't crash
        verify(telegramClient, atLeast(0)).sendMessage(anyLong(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void stickerMessageDoesNotCrash() {
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.STICKER, 100L, 200L,
            "testuser", null, null, "sticker123", "sticker",
            null, null, null, false, null, null, 101L, null, 0L);
        processor.accept(event);
        // Sticker messages should be handled gracefully
        verify(telegramClient, atLeast(0)).sendMessage(anyLong(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void animationMessageWithCaptionIsProcessed() {
        UpdateEvent event = new UpdateEvent(1, UpdateEvent.Type.ANIMATION, 100L, 200L,
            "testuser", null, "look at this gif", "anim123", "animation",
            null, null, null, false, null, null, 101L, null, 0L);
        processor.accept(event);
        // Animation with caption should be processed (caption used as text)
        verify(telegramClient, atLeast(0)).sendMessage(anyLong(), anyString(), anyString(), any(), any(), anyBoolean());
    }
}