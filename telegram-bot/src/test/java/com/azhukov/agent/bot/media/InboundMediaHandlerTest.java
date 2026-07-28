package com.azhukov.agent.bot.media;

import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InboundMediaHandlerTest {

    private MediaDownloader mediaDownloader;
    private InboundMediaHandler handler;

    @BeforeEach
    void setUp() {
        mediaDownloader = mock(MediaDownloader.class);
        handler = new InboundMediaHandler(mediaDownloader);
    }

    @Test
    void handle_photo_returnsPhotoDescription() {
        UpdateEvent event = new UpdateEvent(100L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, "Check this", "file-photo-1", "photo",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-photo-1"))
            .thenReturn(Optional.of("image-bytes".getBytes()));

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("[Photo received");
        assertThat(result.get()).contains("file-photo-1");
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
        assertThat(result.get()).contains("file-doc-1");
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
        assertThat(result.get()).contains("[Voice message received");
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
        assertThat(result.get()).contains("[Sticker received");
        assertThat(result.get()).contains("file-sticker-1");
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
        assertThat(result.get()).contains("[Animation/GIF received");
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
    void handle_downloadFails_stillReturnsDescriptionWithZeroSize() {
        UpdateEvent event = new UpdateEvent(107L, UpdateEvent.Type.PHOTO, 123L, 456L,
            "jdoe", null, null, "file-photo-fail", "photo",
            null, null, null, false, null, null);
        when(mediaDownloader.downloadToFileId("file-photo-fail"))
            .thenReturn(Optional.empty());

        Optional<String> result = handler.handle(event);

        assertThat(result).isPresent();
        assertThat(result.get()).contains("[Photo received");
        assertThat(result.get()).contains("0 bytes");
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
}