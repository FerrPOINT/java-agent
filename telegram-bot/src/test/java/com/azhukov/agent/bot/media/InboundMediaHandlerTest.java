package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InboundMediaHandlerTest {

    private MediaDownloader mediaDownloader;
    private com.azhukov.agent.bot.sticker.StickerCache stickerCache;
    private com.azhukov.agent.bot.core.AgentBackendClient backendClient;
    private InboundMediaHandler handler;

    @BeforeEach
    void setUp() {
        mediaDownloader = mock(MediaDownloader.class);
        stickerCache = mock(com.azhukov.agent.bot.sticker.StickerCache.class);
        backendClient = mock(com.azhukov.agent.bot.core.AgentBackendClient.class);
        when(stickerCache.get(any())).thenReturn(Optional.empty());
        handler = new InboundMediaHandler(mediaDownloader, stickerCache, backendClient);
    }

    @Test
    void handle_photo_returnsPhotoDescriptionWithCaption() {
        UpdateEvent event = new UpdateEvent(100L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, "Check this", "file-photo-1", "photo",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-photo-1"))
            .thenReturn(Optional.of("image-bytes".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        // B1: Should contain the file path, not just file_id
        assertThat(result.get()).contains("[Photo:");
        assertThat(result.get()).contains("caption=\"Check this\"");
    }

    @Test
    void handle_document_returnsDocumentDescription() {
        UpdateEvent event = new UpdateEvent(101L, UpdateEvent.Type.DOCUMENT, 123L, 456L,
            "jdoe", null, null, "file-doc-1", "document",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-doc-1"))
            .thenReturn(Optional.of(new byte[1024]));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("[Document:");
        assertThat(result.get()).contains("1024 bytes");
    }

    @Test
    void handle_voice_returnsVoiceDescription() {
        UpdateEvent event = new UpdateEvent(102L, UpdateEvent.Type.VOICE, 123L, 456L,
            "jdoe", null, null, "file-voice-1", "voice",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-voice-1"))
            .thenReturn(Optional.of("voice-data".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("[Voice message:");
        assertThat(result.get()).contains("file-voice-1");
    }

    @Test
    void handle_sticker_returnsStickerDescription() {
        UpdateEvent event = new UpdateEvent(103L, UpdateEvent.Type.STICKER, 123L, 456L,
            "jdoe", null, null, "file-sticker-1", "sticker",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-sticker-1"))
            .thenReturn(Optional.of("sticker-data".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("[Sticker:");
    }

    @Test
    void handle_animation_returnsAnimationDescription() {
        UpdateEvent event = new UpdateEvent(104L, UpdateEvent.Type.ANIMATION, 123L, 456L,
            "jdoe", null, "funny gif", "file-anim-1", "animation",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-anim-1"))
            .thenReturn(Optional.of("gif-data".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("[Animation/GIF:");
        assertThat(result.get()).contains("file-anim-1");
    }

    @Test
    void handle_textMessage_returnsEmpty() {
        UpdateEvent event = new UpdateEvent(105L, UpdateEvent.Type.TEXT, 123L, 456L,
            "jdoe", "Hello", null, null, null,
            null, null, null, false, null, null);

        Optional<String> result = handler.handle(event);

        assertThat(result).isEmpty();
        verifyNoInteractions(mediaDownloader);
    }

    @Test
    void handle_nullEvent_returnsEmpty() {
        Optional<String> result = handler.handle(null);

        assertThat(result).isEmpty();
    }

    @Test
    void handle_noFileType_returnsEmpty() {
        UpdateEvent event = new UpdateEvent(106L, UpdateEvent.Type.TEXT, 123L, 456L,
            "jdoe", "Hello", null, null, null,
            null, null, null, false, null, null);

        Optional<String> result = handler.handle(event);

        assertThat(result).isEmpty();
    }

    @Test
    void handle_downloadFails_returnsDescriptionWithDownloadFailed() {
        UpdateEvent event = new UpdateEvent(107L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, null, "file-photo-fail", "photo",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-photo-fail"))
            .thenReturn(Optional.empty());

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("[Photo:");
        // Should indicate download failed
        assertThat(result.get()).contains("download failed");
    }

    @Test
    void handle_photoWithoutCaption_omitsCaption() {
        UpdateEvent event = new UpdateEvent(108L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, null, "file-photo-2", "photo",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-photo-2"))
            .thenReturn(Optional.of("data".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).doesNotContain("caption=");
    }

    @Test
    void handle_photo_includesFilePathForVision() {
        UpdateEvent event = new UpdateEvent(109L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, "look at this", "file-photo-path", "photo",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-photo-path"))
            .thenReturn(Optional.of("image-bytes".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        // B1: The description should include a file path for vision tools to analyze
        assertThat(result.get()).contains("/tmp/agent-media/");
    }

    @Test
    void handle_fileTooLarge_returnsDescriptionWithoutPath() {
        UpdateEvent event = new UpdateEvent(110L, UpdateEvent.Type.DOCUMENT, 123L, 456L,
            "jdoe", null, null, "file-big", "document",
            null, null, null, false, null, null);
        // 25MB — exceeds the 20MB limit
        byte[] largeFile = new byte[25 * 1024 * 1024];
        when(mediaDownloader.downloadToFileId("file-big"))
            .thenReturn(Optional.of(largeFile));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("[Document:");
        // Should NOT contain a file path (was not saved)
        assertThat(result.get()).doesNotContain("/tmp/agent-media/");
    }

    // ─── Photo album: comma-separated file IDs ─────────────────────

    @Test
    void handle_commaSeparatedFileIdsProcessesEachPhoto() {
        UpdateEvent event = new UpdateEvent(111L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, "Album caption", "photo-1,photo-2,photo-3", "photo",
            null, null, null, false, null, null);

        when(mediaDownloader.downloadToFileId("photo-1"))
            .thenReturn(Optional.of("img1".getBytes()));
        when(mediaDownloader.downloadToFileId("photo-2"))
            .thenReturn(Optional.of("img2".getBytes()));
        when(mediaDownloader.downloadToFileId("photo-3"))
            .thenReturn(Optional.of("img3".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        // Should contain three photo descriptions
        assertThat(result.get()).contains("[Photo:");
        // Should mention /tmp/agent-media/ for each saved photo
        long photoCount = result.get().lines().filter(l -> l.contains("[Photo:")).count();
        assertThat(photoCount).isEqualTo(3);
    }

    @Test
    void handle_commaSeparatedFileIdsWithSpacesProcessesEachPhoto() {
        UpdateEvent event = new UpdateEvent(112L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, null, "photo-a, photo-b", "photo",
            null, null, null, false, null, null);

        when(mediaDownloader.downloadToFileId("photo-a"))
            .thenReturn(Optional.of("imgA".getBytes()));
        when(mediaDownloader.downloadToFileId("photo-b"))
            .thenReturn(Optional.of("imgB".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        long photoCount = result.get().lines().filter(l -> l.contains("[Photo:")).count();
        assertThat(photoCount).isEqualTo(2);
    }

    @Test
    void handle_singleFileIdWithoutCommaStillWorks() {
        UpdateEvent event = new UpdateEvent(113L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, null, "single-photo", "photo",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("single-photo"))
            .thenReturn(Optional.of("data".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        long photoCount = result.get().lines().filter(l -> l.contains("[Photo:")).count();
        assertThat(photoCount).isEqualTo(1);
    }

    @Test
    void handle_commaSeparatedWithEmptyEntriesSkipsEmpty() {
        UpdateEvent event = new UpdateEvent(114L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, null, "photo-x,,photo-y", "photo",
            null, null, null, false, null, null);

        when(mediaDownloader.downloadToFileId("photo-x"))
            .thenReturn(Optional.of("imgX".getBytes()));
        when(mediaDownloader.downloadToFileId("photo-y"))
            .thenReturn(Optional.of("imgY".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        long photoCount = result.get().lines().filter(l -> l.contains("[Photo:")).count();
        assertThat(photoCount).isEqualTo(2);
    }
}