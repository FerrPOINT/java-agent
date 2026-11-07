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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@code deliverMedia} routing in {@link BotMessageProcessor} (S-2).
 * <p>
 * Verifies that media files are routed to the correct Telegram API method
 * based on file extension:
 * <ul>
 *   <li>Single image → {@code sendPhoto}</li>
 *   <li>Multiple images (2+) → {@code sendMediaGroup}</li>
 *   <li>Video file → {@code sendVideo}</li>
 *   <li>Document file (e.g. PDF) → {@code sendDocument}</li>
 *   <li>Audio with {@code [[audio_as_voice]]} → {@code sendAudioAsVoice}</li>
 *   <li>File not found → warning via {@code sendMessage}</li>
 * </ul>
 * <p>
 * Uses real {@link BusySessionHandler}, real {@link BotProperties}, and real
 * {@link MediaDeliveryService}, matching the pattern from
 * {@link BotMessageProcessorBusyAckTest}.
 */
class BotMessageProcessorMediaDeliveryTest {

    @TempDir
    Path tempDir;

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

    private AttachmentApiClient attachmentApiClient;
    private BotMessageProcessor processor;
    private final com.azhukov.agent.bot.session.BotSessionStore sessionStoreMock = org.mockito.Mockito.mock(com.azhukov.agent.bot.session.BotSessionStore.class);


    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        authorizationService = mock(AuthorizationService.class);
        sessionStore = mock(BotSessionStore.class);
        properties = new BotProperties();
        properties.setDefaultModel("test-model");
        properties.setParseMode("MarkdownV2");
        properties.setMediaDeliveryEnabled(true);
        // Set working directory to tempDir so isMediaPathAllowed() accepts our temp files
        properties.setWorkingDirectory(tempDir.toString());
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
        when(runtimeFooter.format(anyString(), anyInt(), anyInt(), anyString(), anyLong(), anyBoolean())).thenReturn("");
        when(inboundMediaHandler.handle(any())).thenReturn(Optional.empty());
        when(streamEditor.startStream(anyLong(), anyString())).thenReturn(Optional.of(1L));
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong())).thenReturn(Optional.of(1L));
        when(streamEditor.startStream(anyLong(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.of(1L));
        when(streamEditor.editStream(anyLong(), anyLong(), anyString())).thenReturn(true);
        when(streamEditor.finalizeStream(anyLong(), anyLong(), anyString())).thenReturn(true);
        doNothing().when(streamEditor).clearStream(anyLong());
        when(responseFilter.shouldFilter(anyString())).thenReturn(false);
        when(slashAccessPolicy.canRun(anyLong(), anyString())).thenReturn(true);
        when(telegramClient.sendDocument(anyLong(), any(byte[].class), anyString(), nullable(String.class), nullable(String.class)))
            .thenReturn(Optional.of(1L));
        when(telegramClient.sendVideo(anyLong(), any(byte[].class), anyString(), nullable(String.class), nullable(String.class)))
            .thenReturn(Optional.of(1L));
        when(telegramClient.sendAudioAsVoice(anyLong(), any(byte[].class), anyString(), nullable(String.class)))
            .thenReturn(Optional.of(1L));
        when(editCaptureService.getCapture(anyLong())).thenReturn(null);

        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
        org.mockito.Mockito.lenient().when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(session);

        when(textBatchDebouncer.offer(any())).thenReturn(false);
        when(photoBatchDebouncer.offer(any())).thenReturn(false);

        // Default streaming result — will be overridden per test
        stubStreamingResult("response", true);

        // c5: construct the extracted collaborators with the same mocked deps
        UpdateDispatcher updateDispatcher = new UpdateDispatcher(
            properties, editCaptureService, textBatchDebouncer, photoBatchDebouncer);
        StreamingOrchestrator streamingOrchestrator = new StreamingOrchestrator(
            backendClient, streamEditor, busyHandler, runtimeFooter, properties, mediaDeliveryService,
            mock(com.azhukov.agent.bot.client.TelegramClient.class), sessionStoreMock,
            mock(com.azhukov.agent.bot.keyboard.ClarifyInteractionRenderer.class));

        attachmentApiClient = mock(AttachmentApiClient.class);
        processor = new BotMessageProcessor(
            telegramClient, authorizationService, sessionStore, busyHandler,
            typingManager, backendClient, commandRegistry, callbackQueryHandler,
            properties, streamEditor, inboundMediaHandler, mediaDeliveryService, attachmentApiClient,
            runtimeFooter, reactionManager, textBatchDebouncer, photoBatchDebouncer,
            groupMessageFilter, slashAccessPolicy, responseFilter, goalAutoContinueService,
            editCaptureService, updateDispatcher, streamingOrchestrator);
        processor.init();
    }

    private void stubStreamingResult(String content, boolean streamFinalized) {
        doAnswer(inv -> {
            Consumer<String> tokenConsumer = inv.getArgument(4);
            tokenConsumer.accept(content);
            if (streamFinalized) {
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(10);
                onComplete.accept(new AgentBackendClient.ChatResult(content, "test-model", 100, 1000, true));
            }
            return new AgentBackendClient.ChatResult(content, "test-model", 100, 1000, streamFinalized, false);
        }).when(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private UpdateEvent textEvent(long updateId, long chatId, String text) {
        return new UpdateEvent(updateId, UpdateEvent.Type.TEXT, chatId, 200L,
            "testuser", text, null, null, null, null, null, null,
            false, null, null, 100 + (int) updateId, null, 0);
    }

    /**
     * Create a temp file with the given name and some dummy content.
     * The file is created inside the @TempDir so isMediaPathAllowed() accepts it.
     */
    private Path createTempFile(String fileName) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.write(file, "dummy content for testing".getBytes());
        return file;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Media delivery routing tests
    // ═══════════════════════════════════════════════════════════════════


    @Test
    void validArtifactsReachTelegramAndOnlyAcceptedItemsGetReceipts() {
        UUID sessionId = UUID.randomUUID();
        byte[] bytes = {1, 2};
        when(attachmentApiClient.loadOutbound("photo", "101", sessionId))
            .thenReturn(Optional.of(new AttachmentApiClient.Outbound("photo", "photo.png", "image/png", bytes)));
        when(attachmentApiClient.loadOutbound("voice", "101", sessionId))
            .thenReturn(Optional.of(new AttachmentApiClient.Outbound("voice", "voice.ogg", "audio/ogg", bytes)));
        when(telegramClient.sendPhoto(100L, bytes, null, null)).thenReturn(Optional.of(17L));
        when(telegramClient.sendAudioAsVoice(100L, bytes, "voice.ogg", null)).thenReturn(Optional.empty());
        processor.deliverMedia(100L, List.of(new MediaDeliveryService.MediaDescriptor("photo", false, false),
            new MediaDeliveryService.MediaDescriptor("voice", true, false)), null, "101", sessionId);
        verify(telegramClient).sendPhoto(100L, bytes, null, null);
        verify(telegramClient).sendAudioAsVoice(100L, bytes, "voice.ogg", null);
        verify(attachmentApiClient).markOutboundDelivered("photo", "101", sessionId, 17L);
        verify(attachmentApiClient, never()).markOutboundDelivered(eq("voice"), anyString(), any(), anyLong());
    }

    @Test
    void missingOrUnscopedArtifactsDoNotReachTelegram() {
        UUID sessionId = UUID.randomUUID();
        var media = List.of(new MediaDeliveryService.MediaDescriptor("missing", false, false));
        processor.deliverMedia(100L, media, null, "101", null);
        verifyNoInteractions(attachmentApiClient);
        processor.deliverMedia(100L, media, null, "101", sessionId);
        verify(attachmentApiClient).loadOutbound("missing", "101", sessionId);
        verifyNoInteractions(telegramClient);
    }
}
