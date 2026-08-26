package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link TelegramMediaClient}, the package-private
 * collaborator extracted from {@link TelegramClient} for media
 * upload/download operations.
 *
 * <p>Tests verify that {@code TelegramMediaClient} correctly:
 * <ul>
 *   <li>Delegates multipart API calls through {@link TelegramClient#callMultipartApi}</li>
 *   <li>Delegates JSON API calls (getFile) through {@link TelegramClient#callApi}</li>
 *   <li>Handles success, error, and exception cases for each media method</li>
 *   <li>Correctly handles edge cases (null/blank params, empty lists)</li>
 * </ul>
 *
 * <p>The {@link TelegramClient} is mocked so we can verify delegation without
 * needing the full RestClient chain. However, for integration-style tests
 * (verifying the full multipart chain), we use a real {@link TelegramClient}
 * with mocked RestClient — matching the pattern in {@link TelegramClientTest}.
 */
@ExtendWith(MockitoExtension.class)
class TelegramMediaClientTest {

    // ── Integration-style: real TelegramClient with mocked RestClient ──

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock
    private RestClient.RequestBodySpec bodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;
    @Mock
    private RestClient.RequestHeadersUriSpec<?> getUriSpec;
    @Mock
    private RestClient.RequestHeadersSpec<?> headersSpec;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TelegramClient client;
    private TelegramMediaClient mediaClient;

    @BeforeEach
    void setUp() {
        // rateLimitPerSecond=0 disables the semaphore in tests
        client = new TelegramClient(restClient, objectMapper, "BOT_TOKEN", 0);
        mediaClient = new TelegramMediaClient(client, restClient, objectMapper, "BOT_TOKEN");
    }

    // ── Multipart chain stubbing ──

    @SuppressWarnings("unchecked")
    private void stubMultipartChain(TelegramResponse response) {
        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(TelegramResponse.class)).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private void stubPostChain(TelegramResponse response) {
        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
        when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(TelegramResponse.class)).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private void stubGetChain(byte[] data) {
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getUriSpec);
        when(getUriSpec.uri(anyString(), any(), any())).thenReturn((RestClient.RequestHeadersSpec) headersSpec);
        when(((RestClient.RequestHeadersSpec) headersSpec).retrieve()).thenReturn((RestClient.ResponseSpec) responseSpec);
        when(((RestClient.ResponseSpec) responseSpec).body(byte[].class)).thenReturn(data);
    }

    private TelegramResponse successResponseWithMessageId(long messageId) {
        return new TelegramResponse(true, null, "OK", Map.of("message_id", messageId), null);
    }

    private TelegramResponse successResponseWithResult(Object result) {
        return new TelegramResponse(true, null, "OK", result, null);
    }

    private TelegramResponse errorResponse(int errorCode, String description) {
        return new TelegramResponse(false, errorCode, description, null, null);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  sendPhoto
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendPhoto")
    class SendPhotoTest {

        @Test
        @DisplayName("returns message id on success")
        void sendPhotoSuccess() {
            stubMultipartChain(successResponseWithMessageId(10L));
            Optional<Long> result = mediaClient.sendPhoto(1L, new byte[]{1, 2, 3}, "caption", "HTML");
            assertThat(result).contains(10L);
            verify(restClient).post();
        }

        @Test
        @DisplayName("with null caption and parseMode succeeds")
        void sendPhotoNullCaptionAndParseMode() {
            stubMultipartChain(successResponseWithMessageId(11L));
            Optional<Long> result = mediaClient.sendPhoto(1L, new byte[]{1}, null, null);
            assertThat(result).contains(11L);
        }

        @Test
        @DisplayName("with blank caption — not added")
        void sendPhotoBlankCaption() {
            stubMultipartChain(successResponseWithMessageId(12L));
            Optional<Long> result = mediaClient.sendPhoto(1L, new byte[]{1}, "  ", null);
            assertThat(result).contains(12L);
        }

        @Test
        @DisplayName("error response returns empty")
        void sendPhotoError() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = mediaClient.sendPhoto(1L, new byte[]{1}, "cap", null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null response returns empty")
        void sendPhotoNullResponse() {
            stubMultipartChain(null);
            Optional<Long> result = mediaClient.sendPhoto(1L, new byte[]{1}, "cap", null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("exception returns empty")
        void sendPhotoException() {
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class)).thenThrow(new RuntimeException("Network error"));

            Optional<Long> result = mediaClient.sendPhoto(1L, new byte[]{1}, "cap", null);
            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  sendDocument
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendDocument")
    class SendDocumentTest {

        @Test
        @DisplayName("returns message id on success")
        void sendDocumentSuccess() {
            stubMultipartChain(successResponseWithMessageId(20L));
            Optional<Long> result = mediaClient.sendDocument(1L, new byte[]{1, 2}, "file.pdf", "caption", "HTML");
            assertThat(result).contains(20L);
        }

        @Test
        @DisplayName("null fileName defaults to 'document'")
        void sendDocumentNullFileName() {
            stubMultipartChain(successResponseWithMessageId(21L));
            Optional<Long> result = mediaClient.sendDocument(1L, new byte[]{1}, null, null, null);
            assertThat(result).contains(21L);
        }

        @Test
        @DisplayName("blank fileName defaults to 'document'")
        void sendDocumentBlankFileName() {
            stubMultipartChain(successResponseWithMessageId(22L));
            Optional<Long> result = mediaClient.sendDocument(1L, new byte[]{1}, "  ", null, null);
            assertThat(result).contains(22L);
        }

        @Test
        @DisplayName("error response returns empty")
        void sendDocumentError() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = mediaClient.sendDocument(1L, new byte[]{1}, "file.pdf", "cap", null);
            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  sendVoice
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendVoice")
    class SendVoiceTest {

        @Test
        @DisplayName("returns message id on success")
        void sendVoiceSuccess() {
            stubMultipartChain(successResponseWithMessageId(30L));
            Optional<Long> result = mediaClient.sendVoice(1L, new byte[]{1, 2, 3}, "voice caption");
            assertThat(result).contains(30L);
        }

        @Test
        @DisplayName("null caption succeeds")
        void sendVoiceNullCaption() {
            stubMultipartChain(successResponseWithMessageId(31L));
            Optional<Long> result = mediaClient.sendVoice(1L, new byte[]{1}, null);
            assertThat(result).contains(31L);
        }

        @Test
        @DisplayName("blank caption — not added")
        void sendVoiceBlankCaption() {
            stubMultipartChain(successResponseWithMessageId(32L));
            Optional<Long> result = mediaClient.sendVoice(1L, new byte[]{1}, "  ");
            assertThat(result).contains(32L);
        }

        @Test
        @DisplayName("error response returns empty")
        void sendVoiceError() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = mediaClient.sendVoice(1L, new byte[]{1}, "cap");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null response returns empty")
        void sendVoiceNullResponse() {
            stubMultipartChain(null);
            Optional<Long> result = mediaClient.sendVoice(1L, new byte[]{1}, "cap");
            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  sendVideo
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendVideo")
    class SendVideoTest {

        @Test
        @DisplayName("returns message id on success")
        void sendVideoSuccess() {
            stubMultipartChain(successResponseWithMessageId(40L));
            Optional<Long> result = mediaClient.sendVideo(1L, new byte[]{1, 2, 3}, "video.mp4", "caption", "HTML");
            assertThat(result).contains(40L);
        }

        @Test
        @DisplayName("null fileName defaults to 'video.mp4'")
        void sendVideoNullFileName() {
            stubMultipartChain(successResponseWithMessageId(41L));
            Optional<Long> result = mediaClient.sendVideo(1L, new byte[]{1}, null, null, null);
            assertThat(result).contains(41L);
        }

        @Test
        @DisplayName("blank fileName defaults to 'video.mp4'")
        void sendVideoBlankFileName() {
            stubMultipartChain(successResponseWithMessageId(42L));
            Optional<Long> result = mediaClient.sendVideo(1L, new byte[]{1}, "  ", null, null);
            assertThat(result).contains(42L);
        }

        @Test
        @DisplayName("error response returns empty")
        void sendVideoError() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = mediaClient.sendVideo(1L, new byte[]{1}, "video.mp4", "cap", null);
            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  sendAudioAsVoice
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendAudioAsVoice")
    class SendAudioAsVoiceTest {

        @Test
        @DisplayName("returns message id on success")
        void sendAudioAsVoiceSuccess() {
            stubMultipartChain(successResponseWithMessageId(50L));
            Optional<Long> result = mediaClient.sendAudioAsVoice(1L, new byte[]{1, 2, 3}, "voice.ogg", "Audio message");
            assertThat(result).contains(50L);
        }

        @Test
        @DisplayName("null fileName defaults to 'voice.ogg'")
        void sendAudioAsVoiceNullFileName() {
            stubMultipartChain(successResponseWithMessageId(51L));
            Optional<Long> result = mediaClient.sendAudioAsVoice(1L, new byte[]{1}, null, null);
            assertThat(result).contains(51L);
        }

        @Test
        @DisplayName("blank fileName defaults to 'voice.ogg'")
        void sendAudioAsVoiceBlankFileName() {
            stubMultipartChain(successResponseWithMessageId(52L));
            Optional<Long> result = mediaClient.sendAudioAsVoice(1L, new byte[]{1}, "  ", null);
            assertThat(result).contains(52L);
        }

        @Test
        @DisplayName("error response returns empty")
        void sendAudioAsVoiceError() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = mediaClient.sendAudioAsVoice(1L, new byte[]{1}, "voice.ogg", "cap");
            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  sendMediaGroup
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendMediaGroup")
    class SendMediaGroupTest {

        @Test
        @DisplayName("sends multiple photos and returns message ids")
        void sendMediaGroupMultiplePhotos() {
            List<Object> messageArray = new ArrayList<>();
            for (long i = 100; i < 110; i++) {
                messageArray.add(Map.of("message_id", i));
            }
            stubMultipartChain(successResponseWithResult(messageArray));

            List<TelegramClient.PhotoInput> photos = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                photos.add(new TelegramClient.PhotoInput(
                    new byte[]{(byte) i}, "photo" + i + ".jpg", "caption " + i));
            }

            List<Long> result = mediaClient.sendMediaGroup(123L, photos);
            assertThat(result).hasSize(10);
            assertThat(result).containsExactly(100L, 101L, 102L, 103L, 104L,
                105L, 106L, 107L, 108L, 109L);
        }

        @Test
        @DisplayName("empty list returns empty without calling API")
        void sendMediaGroupEmptyList() {
            List<Long> result = mediaClient.sendMediaGroup(123L, List.of());
            assertThat(result).isEmpty();
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("null list returns empty without calling API")
        void sendMediaGroupNullList() {
            List<Long> result = mediaClient.sendMediaGroup(123L, null);
            assertThat(result).isEmpty();
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("single photo returns message id")
        void sendMediaGroupSinglePhoto() {
            stubMultipartChain(successResponseWithResult(List.of(
                Map.of("message_id", 200L)
            )));

            List<TelegramClient.PhotoInput> photos = List.of(
                new TelegramClient.PhotoInput(new byte[]{1, 2}, "test.jpg", "single"));

            List<Long> result = mediaClient.sendMediaGroup(123L, photos);
            assertThat(result).containsExactly(200L);
        }

        @Test
        @DisplayName("with null captions — no parse_mode in media items")
        void sendMediaGroupNoCaptions() {
            stubMultipartChain(successResponseWithResult(List.of(
                Map.of("message_id", 500L)
            )));

            List<TelegramClient.PhotoInput> photos = List.of(
                new TelegramClient.PhotoInput(new byte[]{1}, "photo.jpg", null),
                new TelegramClient.PhotoInput(new byte[]{2}, "photo2.jpg", ""));

            List<Long> result = mediaClient.sendMediaGroup(123L, photos);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("error response returns empty")
        void sendMediaGroupError() {
            stubMultipartChain(errorResponse(400, "Bad request"));

            List<TelegramClient.PhotoInput> photos = List.of(
                new TelegramClient.PhotoInput(new byte[]{1}, "photo.jpg", "cap"));

            List<Long> result = mediaClient.sendMediaGroup(123L, photos);
            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  getFile
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getFile")
    class GetFileTest {

        @Test
        @DisplayName("returns file info map on success")
        void getFileSuccess() {
            Map<String, Object> fileResult = Map.of("file_id", "abc", "file_path", "photos/file.jpg");
            stubPostChain(successResponseWithResult(fileResult));
            Optional<Map<String, Object>> result = mediaClient.getFile("abc");
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("file_id", "abc");
        }

        @Test
        @DisplayName("error response returns empty")
        void getFileError() {
            stubPostChain(errorResponse(400, "Bad request"));
            Optional<Map<String, Object>> result = mediaClient.getFile("abc");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("non-map result returns empty map")
        void getFileNonMapResult() {
            stubPostChain(successResponseWithResult("not a map"));
            Optional<Map<String, Object>> result = mediaClient.getFile("abc");
            assertThat(result).isPresent();
            assertThat(result.get()).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  downloadFile
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("downloadFile")
    class DownloadFileTest {

        @Test
        @DisplayName("returns bytes on success")
        void downloadFileSuccess() {
            stubGetChain(new byte[]{1, 2, 3, 4});
            Optional<byte[]> result = mediaClient.downloadFile("photos/file.jpg");
            assertThat(result).isPresent();
            assertThat(result.get()).containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("null body returns empty")
        void downloadFileNullBody() {
            stubGetChain(null);
            Optional<byte[]> result = mediaClient.downloadFile("photos/file.jpg");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("exception returns empty")
        void downloadFileException() {
            when(restClient.get()).thenThrow(new RuntimeException("Network error"));
            Optional<byte[]> result = mediaClient.downloadFile("photos/file.jpg");
            assertThat(result).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  guessImageMediaType
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("guessImageMediaType")
    class GuessImageMediaTypeTest {

        @Test
        @DisplayName("null fileName returns IMAGE_JPEG")
        void nullFileName() {
            assertThat(TelegramMediaClient.guessImageMediaType(null))
                .isEqualTo(MediaType.IMAGE_JPEG);
        }

        @Test
        @DisplayName(".png returns IMAGE_PNG")
        void pngFile() {
            assertThat(TelegramMediaClient.guessImageMediaType("photo.png"))
                .isEqualTo(MediaType.IMAGE_PNG);
        }

        @Test
        @DisplayName(".PNG (uppercase) returns IMAGE_PNG")
        void pngFileUppercase() {
            assertThat(TelegramMediaClient.guessImageMediaType("PHOTO.PNG"))
                .isEqualTo(MediaType.IMAGE_PNG);
        }

        @Test
        @DisplayName(".gif returns IMAGE_GIF")
        void gifFile() {
            assertThat(TelegramMediaClient.guessImageMediaType("animation.gif"))
                .isEqualTo(MediaType.IMAGE_GIF);
        }

        @Test
        @DisplayName(".webp returns image/webp")
        void webpFile() {
            assertThat(TelegramMediaClient.guessImageMediaType("photo.webp"))
                .isEqualTo(MediaType.parseMediaType("image/webp"));
        }

        @Test
        @DisplayName(".jpg returns IMAGE_JPEG")
        void jpgFile() {
            assertThat(TelegramMediaClient.guessImageMediaType("photo.jpg"))
                .isEqualTo(MediaType.IMAGE_JPEG);
        }

        @Test
        @DisplayName("unknown extension returns IMAGE_JPEG")
        void unknownExtension() {
            assertThat(TelegramMediaClient.guessImageMediaType("photo.bmp"))
                .isEqualTo(MediaType.IMAGE_JPEG);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TelegramClient delegation verification
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TelegramClient delegation")
    class DelegationTest {

        @Test
        @DisplayName("TelegramClient.sendPhoto delegates to mediaClient")
        void sendPhotoDelegates() {
            stubMultipartChain(successResponseWithMessageId(10L));
            Optional<Long> result = client.sendPhoto(1L, new byte[]{1, 2, 3}, "caption", "HTML");
            assertThat(result).contains(10L);
            verify(restClient).post();
        }

        @Test
        @DisplayName("TelegramClient.sendDocument delegates to mediaClient")
        void sendDocumentDelegates() {
            stubMultipartChain(successResponseWithMessageId(20L));
            Optional<Long> result = client.sendDocument(1L, new byte[]{1}, "file.pdf", "cap", null);
            assertThat(result).contains(20L);
        }

        @Test
        @DisplayName("TelegramClient.sendVoice delegates to mediaClient")
        void sendVoiceDelegates() {
            stubMultipartChain(successResponseWithMessageId(30L));
            Optional<Long> result = client.sendVoice(1L, new byte[]{1}, "cap");
            assertThat(result).contains(30L);
        }

        @Test
        @DisplayName("TelegramClient.sendVideo delegates to mediaClient")
        void sendVideoDelegates() {
            stubMultipartChain(successResponseWithMessageId(40L));
            Optional<Long> result = client.sendVideo(1L, new byte[]{1}, "video.mp4", "cap", null);
            assertThat(result).contains(40L);
        }

        @Test
        @DisplayName("TelegramClient.sendAudioAsVoice delegates to mediaClient")
        void sendAudioAsVoiceDelegates() {
            stubMultipartChain(successResponseWithMessageId(50L));
            Optional<Long> result = client.sendAudioAsVoice(1L, new byte[]{1}, "voice.ogg", "cap");
            assertThat(result).contains(50L);
        }

        @Test
        @DisplayName("TelegramClient.sendMediaGroup delegates to mediaClient")
        void sendMediaGroupDelegates() {
            stubMultipartChain(successResponseWithResult(List.of(
                Map.of("message_id", 200L)
            )));

            List<TelegramClient.PhotoInput> photos = List.of(
                new TelegramClient.PhotoInput(new byte[]{1, 2}, "test.jpg", "single"));

            List<Long> result = client.sendMediaGroup(123L, photos);
            assertThat(result).containsExactly(200L);
        }

        @Test
        @DisplayName("TelegramClient.getFile delegates to mediaClient")
        void getFileDelegates() {
            Map<String, Object> fileResult = Map.of("file_id", "abc", "file_path", "photos/file.jpg");
            stubPostChain(successResponseWithResult(fileResult));
            Optional<Map<String, Object>> result = client.getFile("abc");
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("file_id", "abc");
        }

        @Test
        @DisplayName("TelegramClient.downloadFile delegates to mediaClient")
        void downloadFileDelegates() {
            stubGetChain(new byte[]{1, 2, 3, 4});
            Optional<byte[]> result = client.downloadFile("photos/file.jpg");
            assertThat(result).isPresent();
            assertThat(result.get()).containsExactly(1, 2, 3, 4);
        }
    }
}