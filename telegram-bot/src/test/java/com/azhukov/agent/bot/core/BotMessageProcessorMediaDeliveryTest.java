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
        when(attachmentApiClient.claimOutbound("photo", "101", sessionId))
            .thenReturn(Optional.of(new AttachmentApiClient.Outbound("photo", "photo.png", "image/png", bytes, "claim")));
        when(attachmentApiClient.claimOutbound("voice", "101", sessionId))
            .thenReturn(Optional.of(new AttachmentApiClient.Outbound("voice", "voice.ogg", "audio/ogg", bytes, "claim")));
        when(telegramClient.sendPhoto(100L, bytes, null, null, 55)).thenReturn(Optional.of(17L));
        when(telegramClient.sendAudioAsVoice(100L, bytes, "voice.ogg", null, 55)).thenReturn(Optional.empty());
        processor.deliverMedia(100L, List.of(new MediaDeliveryService.MediaDescriptor("photo", false, false),
            new MediaDeliveryService.MediaDescriptor("voice", true, false)), 55, "101", sessionId);
        verify(telegramClient).sendPhoto(100L, bytes, null, null, 55);
        verify(telegramClient).sendAudioAsVoice(100L, bytes, "voice.ogg", null, 55);
        verify(attachmentApiClient).markOutboundDelivered("photo", "101", sessionId, 17L);
        verify(attachmentApiClient, never()).markOutboundDelivered(eq("voice"), anyString(), any(), anyLong());
    }

    @Test
    void albumDocumentAndVoiceRecordTheirOwnReceipts() {
        UUID sessionId = UUID.randomUUID();
        byte[] bytes = {1, 2};
        for (String id : List.of("first", "second", "document", "voice")) {
            String mime = switch (id) {
                case "document" -> "application/pdf";
                case "voice" -> "audio/ogg";
                default -> "image/png";
            };
            when(attachmentApiClient.claimOutbound(id, "101", sessionId))
                .thenReturn(Optional.of(new AttachmentApiClient.Outbound(id, id, mime, bytes, "claim")));
        }
        when(attachmentApiClient.markOutboundDelivered(anyString(), eq("101"), eq(sessionId), anyLong()))
            .thenReturn(true);
        when(telegramClient.sendMediaGroup(eq(100L), anyList(), eq(55))).thenReturn(List.of(20L, 21L));
        when(telegramClient.sendDocument(100L, bytes, "document", null, null, 55)).thenReturn(Optional.of(22L));
        when(telegramClient.sendAudioAsVoice(100L, bytes, "voice", null, 55)).thenReturn(Optional.of(23L));

        processor.deliverMedia(100L, List.of(new MediaDeliveryService.MediaDescriptor("first", false, false),
            new MediaDeliveryService.MediaDescriptor("second", false, false),
            new MediaDeliveryService.MediaDescriptor("document", false, true),
            new MediaDeliveryService.MediaDescriptor("voice", true, false)), 55, "101", sessionId);

        verify(telegramClient).sendMediaGroup(eq(100L), argThat(photos -> photos.size() == 2), eq(55));
        verify(attachmentApiClient).markOutboundDelivered("first", "101", sessionId, 20L);
        verify(attachmentApiClient).markOutboundDelivered("second", "101", sessionId, 21L);
        verify(attachmentApiClient).markOutboundDelivered("document", "101", sessionId, 22L);
        verify(attachmentApiClient).markOutboundDelivered("voice", "101", sessionId, 23L);
        verify(telegramClient, never()).sendMessage(anyLong(), anyString(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void acceptedAlbumWithFailedReceiptReportsUnconfirmedAndRecordsOtherReceipt() {
        UUID sessionId = UUID.randomUUID();
        byte[] bytes = {1, 2};
        for (String id : List.of("first", "second")) {
            when(attachmentApiClient.claimOutbound(id, "101", sessionId))
                .thenReturn(Optional.of(new AttachmentApiClient.Outbound(id, id, "image/png", bytes, "claim")));
        }
        when(telegramClient.sendMediaGroup(eq(100L), anyList(), eq(55))).thenReturn(List.of(20L, 21L));
        when(attachmentApiClient.markOutboundDelivered("second", "101", sessionId, 21L)).thenReturn(true);

        processor.deliverMedia(100L, List.of(new MediaDeliveryService.MediaDescriptor("first", false, false),
            new MediaDeliveryService.MediaDescriptor("second", false, false)), 55, "101", sessionId);

        verify(telegramClient).sendMediaGroup(eq(100L), anyList(), eq(55));
        verify(attachmentApiClient).markOutboundDelivered("first", "101", sessionId, 20L);
        verify(attachmentApiClient).markOutboundDelivered("second", "101", sessionId, 21L);
        verify(telegramClient).sendMessage(100L, "Delivery could not be confirmed for one or more attachments.",
            null, null, 55, null, false);
        verifyNoMoreInteractions(telegramClient);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void confirmedRefusalReleasesEveryClaimInAnUnsentAlbum(boolean telegramRateLimit) {
        UUID session = UUID.randomUUID();
        byte[] bytes = {1, 2};
        var first = new AttachmentApiClient.Outbound("first", "first.png", "image/png", bytes, "claim-1");
        var second = new AttachmentApiClient.Outbound("second", "second.png", "image/png", bytes, "claim-2");
        when(attachmentApiClient.claimOutbound("first", "101", session)).thenReturn(Optional.of(first));
        when(attachmentApiClient.claimOutbound("second", "101", session)).thenReturn(Optional.of(second));
        var refusal = telegramRateLimit
            ? new TelegramClient.SendRefusalException(429, 17, "Flood control")
            : mock(TelegramClient.LocalSendRefusalException.class);
        when(telegramClient.sendMediaGroup(eq(100L), anyList(), eq(55))).thenThrow(refusal);

        processor.deliverMedia(100L, List.of(new MediaDeliveryService.MediaDescriptor("first", false, false),
            new MediaDeliveryService.MediaDescriptor("second", false, false)), 55, "101", session);

        verify(attachmentApiClient).releaseOutbound(first, "101", session);
        verify(attachmentApiClient).releaseOutbound(second, "101", session);
        verify(attachmentApiClient, never()).markOutboundDelivered(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void uncertainDeliveryHasNoReceiptAndClaimPreventsAnotherSend() {
        UUID sessionId = UUID.randomUUID();
        byte[] bytes = {1, 2};
        when(attachmentApiClient.claimOutbound("document", "101", sessionId))
            .thenReturn(Optional.of(new AttachmentApiClient.Outbound("document", "report.pdf", "application/pdf", bytes, "claim")))
            .thenReturn(Optional.empty());
        when(telegramClient.sendDocument(100L, bytes, "report.pdf", null, null, 55))
            .thenReturn(Optional.empty());

        var media = List.of(new MediaDeliveryService.MediaDescriptor("document", false, true));
        processor.deliverMedia(100L, media, 55, "101", sessionId);
        processor.deliverMedia(100L, media, 55, "101", sessionId);

        verify(attachmentApiClient, never()).releaseOutbound(any(), any(), any());
        verify(telegramClient).sendDocument(100L, bytes, "report.pdf", null, null, 55);
        verify(attachmentApiClient, never()).markOutboundDelivered(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void reportsUnavailableArtifactAndSendsLargeImageAsDocument() {
        UUID sessionId = UUID.randomUUID();
        byte[] image = new byte[10_000_001];
        when(attachmentApiClient.claimOutbound("missing", "101", sessionId))
            .thenThrow(new AttachmentApiClient.OutboundUnavailable(new IllegalStateException()));
        when(attachmentApiClient.claimOutbound("large", "101", sessionId))
            .thenReturn(Optional.of(new AttachmentApiClient.Outbound("large", "large.png", "image/png", image, "claim")));
        when(telegramClient.sendDocument(100L, image, "large.png", null, null, 55)).thenReturn(Optional.of(18L));

        processor.deliverMedia(100L, List.of(new MediaDeliveryService.MediaDescriptor("missing", false, false),
            new MediaDeliveryService.MediaDescriptor("large", false, false)), 55, "101", sessionId);

        verify(telegramClient).sendDocument(100L, image, "large.png", null, null, 55);
        verify(telegramClient).sendMessage(100L, "Delivery could not be confirmed for one or more attachments.",
            null, null, 55, null, false);
        verify(attachmentApiClient).markOutboundDelivered("large", "101", sessionId, 18L);
        verifyNoMoreInteractions(telegramClient);
    }

    @Test
    void missingOrUnscopedArtifactsDoNotReachTelegram() {
        UUID sessionId = UUID.randomUUID();
        var media = List.of(new MediaDeliveryService.MediaDescriptor("missing", false, false));
        processor.deliverMedia(100L, media, null, "101", null);
        verifyNoInteractions(attachmentApiClient);
        processor.deliverMedia(100L, media, null, "101", sessionId);
        verify(attachmentApiClient).claimOutbound("missing", "101", sessionId);
        verifyNoInteractions(telegramClient);
    }
}
