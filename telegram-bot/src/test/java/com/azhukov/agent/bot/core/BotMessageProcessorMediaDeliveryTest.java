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

    private BotMessageProcessor processor;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        authorizationService = mock(AuthorizationService.class);
        sessionStore = mock(BotSessionStore.class);
        properties = new BotProperties();
        properties.setDefaultModel("test-model");
        properties.setRedactPii(false);
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

        // Default streaming result — will be overridden per test
        stubStreamingResult("response", true);

        processor = new BotMessageProcessor(
            telegramClient, authorizationService, sessionStore, busyHandler,
            typingManager, backendClient, commandRegistry, callbackQueryHandler,
            properties, streamEditor, inboundMediaHandler, mediaDeliveryService,
            runtimeFooter, reactionManager, textBatchDebouncer, photoBatchDebouncer,
            groupMessageFilter, slashAccessPolicy, responseFilter, goalAutoContinueService,
            editCaptureService);
    }

    @AfterEach
    void tearDown() {
        // Reset the busy state between tests to avoid cross-test interference
        // (BusySessionHandler is a real instance, not a mock)
    }

    @SuppressWarnings("unchecked")
    private void stubStreamingResult(String content, boolean streamFinalized) {
        doAnswer(inv -> {
            Consumer<String> tokenConsumer = inv.getArgument(3);
            tokenConsumer.accept(content);
            if (streamFinalized) {
                Consumer<AgentBackendClient.ChatResult> onComplete = inv.getArgument(7);
                onComplete.accept(new AgentBackendClient.ChatResult(content, "test-model", 100, 1000, true));
            }
            return new AgentBackendClient.ChatResult(content, "test-model", 100, 1000, streamFinalized, false);
        }).when(backendClient).chatStream(anyString(), nullable(String.class), any(), any(), any(), any(), any(), any(), any());
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
    @DisplayName("1. Single image → sendPhoto is called")
    void singleImageRoutesToSendPhoto() throws Exception {
        Path imageFile = createTempFile("test-image.png");
        String mediaTag = "MEDIA:" + imageFile.toString();
        stubStreamingResult("Here is the image\n" + mediaTag, true);

        processor.accept(textEvent(1, 100L, "show me an image"));

        verify(telegramClient).sendPhoto(eq(100L), any(byte[].class), isNull(), isNull());
    }

    @Test
    @DisplayName("2. Multiple images (2+) → sendMediaGroup is called")
    void multipleImagesRoutesToSendMediaGroup() throws Exception {
        Path image1 = createTempFile("image1.png");
        Path image2 = createTempFile("image2.png");
        String mediaTags = "MEDIA:" + image1.toString() + "\nMEDIA:" + image2.toString();
        stubStreamingResult("Here are the images\n" + mediaTags, true);

        processor.accept(textEvent(1, 100L, "show me images"));

        verify(telegramClient).sendMediaGroup(eq(100L), anyList());
    }

    @Test
    @DisplayName("3. Video file → sendVideo is called")
    void videoFileRoutesToSendVideo() throws Exception {
        Path videoFile = createTempFile("test-video.mp4");
        String mediaTag = "MEDIA:" + videoFile.toString();
        stubStreamingResult("Here is the video\n" + mediaTag, true);

        processor.accept(textEvent(1, 100L, "show me a video"));

        verify(telegramClient).sendVideo(eq(100L), any(byte[].class), eq("test-video.mp4"), isNull(), isNull());
    }

    @Test
    @DisplayName("4. Document file (PDF) → sendDocument is called")
    void documentFileRoutesToSendDocument() throws Exception {
        Path pdfFile = createTempFile("report.pdf");
        String mediaTag = "MEDIA:" + pdfFile.toString();
        stubStreamingResult("Here is the document\n" + mediaTag, true);

        processor.accept(textEvent(1, 100L, "show me a document"));

        verify(telegramClient).sendDocument(eq(100L), any(byte[].class), eq("report.pdf"), isNull(), isNull());
    }

    @Test
    @DisplayName("5. Audio with [[audio_as_voice]] → sendAudioAsVoice is called")
    void audioWithAsVoiceRoutesToSendAudioAsVoice() throws Exception {
        Path audioFile = createTempFile("recording.mp3");
        String mediaTag = "MEDIA:" + audioFile.toString();
        // The [[audio_as_voice]] directive causes audio files to be delivered as voice messages
        stubStreamingResult("[[audio_as_voice]]Here is the audio\n" + mediaTag, true);

        processor.accept(textEvent(1, 100L, "play me audio"));

        verify(telegramClient).sendAudioAsVoice(eq(100L), any(byte[].class), eq("recording.mp3"), isNull());
    }

    @Test
    @DisplayName("6. File not found → warning message sent via sendMessage")
    void fileNotFoundSendsWarningMessage() {
        // Reference a file that does not exist, but is within the allowed working directory
        String nonExistentPath = tempDir.resolve("nonexistent-file.png").toString();
        String mediaTag = "MEDIA:" + nonExistentPath;
        stubStreamingResult("Here is the image\n" + mediaTag, true);

        processor.accept(textEvent(1, 100L, "show me the image"));

        // A warning message should be sent via sendMessage containing "not found"
        verify(telegramClient).sendMessage(eq(100L), contains("not found"));
    }
}