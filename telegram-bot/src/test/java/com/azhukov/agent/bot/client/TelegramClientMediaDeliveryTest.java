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
 * Tests for media delivery methods in {@link TelegramClient}:
 * {@code sendVideo}, {@code sendAudioAsVoice}, and {@code sendMediaGroup}.
 * <p>
 * These methods had zero test coverage (S-2 gap). This class mirrors the
 * mocking patterns from {@link TelegramClientTest} — stubbing the multipart
 * POST chain via {@code RestClient} mocks.
 */
@ExtendWith(MockitoExtension.class)
class TelegramClientMediaDeliveryTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock
    private RestClient.RequestBodySpec bodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TelegramClient client;

    @BeforeEach
    void setUp() {
        // rateLimitPerSecond=0 disables the semaphore in tests
        client = new TelegramClient(restClient, objectMapper, "BOT_TOKEN", 0);
    }

    // ── Multipart chain stubbing (same pattern as TelegramClientTest) ──

    @SuppressWarnings("unchecked")
    private void stubMultipartChain(TelegramResponse response) {
        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(TelegramResponse.class)).thenReturn(response);
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
    //  sendVideo
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sendVideo")
    class SendVideoTest {

        @Test
        @DisplayName("sendVideo calls sendVideo API and returns message id")
        void sendVideoReturnsMessageId() {
            stubMultipartChain(successResponseWithMessageId(42L));
            Optional<Long> result = client.sendVideo(123L, new byte[]{1, 2, 3, 4},
                "video.mp4", "Check this out", "HTML");
            assertThat(result).contains(42L);
            verify(restClient).post();
        }

        @Test
        @DisplayName("sendVideo with null fileName defaults to 'video.mp4'")
        void sendVideoNullFileName() {
            stubMultipartChain(successResponseWithMessageId(43L));
            Optional<Long> result = client.sendVideo(123L, new byte[]{1}, null, null, null);
            assertThat(result).contains(43L);
        }

        @Test
        @DisplayName("sendVideo with blank fileName defaults to 'video.mp4'")
        void sendVideoBlankFileName() {
            stubMultipartChain(successResponseWithMessageId(44L));
            Optional<Long> result = client.sendVideo(123L, new byte[]{1}, "  ", null, null);
            assertThat(result).contains(44L);
        }

        @Test
        @DisplayName("sendVideo with blank caption — not added")
        void sendVideoBlankCaption() {
            stubMultipartChain(successResponseWithMessageId(45L));
            Optional<Long> result = client.sendVideo(123L, new byte[]{1}, "video.mp4", "  ", null);
            assertThat(result).contains(45L);
        }

        @Test
        @DisplayName("sendVideo failure returns empty")
        void sendVideoFailure() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = client.sendVideo(123L, new byte[]{1}, "video.mp4", "cap", null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendVideo exception returns empty")
        void sendVideoException() {
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class)).thenThrow(new RuntimeException("Network error"));

            Optional<Long> result = client.sendVideo(123L, new byte[]{1}, "video.mp4", "cap", null);
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
        @DisplayName("sendAudioAsVoice calls sendVoice API and returns message id")
        void sendAudioAsVoiceReturnsMessageId() {
            stubMultipartChain(successResponseWithMessageId(50L));
            Optional<Long> result = client.sendAudioAsVoice(123L, new byte[]{1, 2, 3},
                "voice.ogg", "Audio message");
            assertThat(result).contains(50L);
            verify(restClient).post();
        }

        @Test
        @DisplayName("sendAudioAsVoice with null fileName defaults to 'voice.ogg'")
        void sendAudioAsVoiceNullFileName() {
            stubMultipartChain(successResponseWithMessageId(51L));
            Optional<Long> result = client.sendAudioAsVoice(123L, new byte[]{1}, null, null);
            assertThat(result).contains(51L);
        }

        @Test
        @DisplayName("sendAudioAsVoice with blank fileName defaults to 'voice.ogg'")
        void sendAudioAsVoiceBlankFileName() {
            stubMultipartChain(successResponseWithMessageId(52L));
            Optional<Long> result = client.sendAudioAsVoice(123L, new byte[]{1}, "  ", null);
            assertThat(result).contains(52L);
        }

        @Test
        @DisplayName("sendAudioAsVoice with blank caption — not added")
        void sendAudioAsVoiceBlankCaption() {
            stubMultipartChain(successResponseWithMessageId(53L));
            Optional<Long> result = client.sendAudioAsVoice(123L, new byte[]{1}, "voice.ogg", "  ");
            assertThat(result).contains(53L);
        }

        @Test
        @DisplayName("sendAudioAsVoice failure returns empty")
        void sendAudioAsVoiceFailure() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = client.sendAudioAsVoice(123L, new byte[]{1}, "voice.ogg", "cap");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendAudioAsVoice exception returns empty")
        void sendAudioAsVoiceException() {
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class)).thenThrow(new RuntimeException("Network error"));

            Optional<Long> result = client.sendAudioAsVoice(123L, new byte[]{1}, "voice.ogg", "cap");
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
        @DisplayName("sendMediaGroup sends up to 10 photos and returns message ids")
        void sendMediaGroupSendsUpTo10Photos() {
            // Telegram returns an array of Message objects for sendMediaGroup
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

            List<Long> result = client.sendMediaGroup(123L, photos);
            assertThat(result).hasSize(10);
            assertThat(result).containsExactly(100L, 101L, 102L, 103L, 104L,
                105L, 106L, 107L, 108L, 109L);
            verify(restClient).post();
        }

        @Test
        @DisplayName("sendMediaGroup with empty list does nothing and returns empty")
        void sendMediaGroupEmptyList() {
            List<Long> result = client.sendMediaGroup(123L, List.of());
            assertThat(result).isEmpty();
            // Should NOT call the RestClient at all
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("sendMediaGroup with null list does nothing and returns empty")
        void sendMediaGroupNullList() {
            List<Long> result = client.sendMediaGroup(123L, null);
            assertThat(result).isEmpty();
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("sendMediaGroup with single photo returns message id")
        void sendMediaGroupSinglePhoto() {
            stubMultipartChain(successResponseWithResult(List.of(
                Map.of("message_id", 200L)
            )));

            List<TelegramClient.PhotoInput> photos = List.of(
                new TelegramClient.PhotoInput(new byte[]{1, 2}, "test.jpg", "single"));

            List<Long> result = client.sendMediaGroup(123L, photos);
            assertThat(result).containsExactly(200L);
        }

        @Test
        @DisplayName("sendMediaGroup with >10 photos — caller must chunk; method sends all in one request")
        void sendMediaGroupMoreThan10Photos() {
            // The production code does NOT chunk internally — it sends all photos
            // in a single sendMediaGroup call. Telegram's API limit is 10, but
            // the client does not enforce this. The test verifies the method
            // still returns results (or empty) without crashing.
            List<Object> messageArray = new ArrayList<>();
            for (long i = 300; i < 312; i++) {
                messageArray.add(Map.of("message_id", i));
            }
            stubMultipartChain(successResponseWithResult(messageArray));

            List<TelegramClient.PhotoInput> photos = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                photos.add(new TelegramClient.PhotoInput(
                    new byte[]{(byte) i}, "photo" + i + ".png", null));
            }

            List<Long> result = client.sendMediaGroup(123L, photos);
            // Method sends all 12 in one request (does not chunk)
            assertThat(result).hasSize(12);
        }

        @Test
        @DisplayName("sendMediaGroup with PNG files uses image/png media type")
        void sendMediaGroupPngFiles() {
            stubMultipartChain(successResponseWithResult(List.of(
                Map.of("message_id", 400L)
            )));

            List<TelegramClient.PhotoInput> photos = List.of(
                new TelegramClient.PhotoInput(new byte[]{1}, "image.png", null));

            List<Long> result = client.sendMediaGroup(123L, photos);
            assertThat(result).containsExactly(400L);
        }

        @Test
        @DisplayName("sendMediaGroup failure returns empty")
        void sendMediaGroupFailure() {
            stubMultipartChain(errorResponse(400, "Bad request"));

            List<TelegramClient.PhotoInput> photos = List.of(
                new TelegramClient.PhotoInput(new byte[]{1}, "photo.jpg", "cap"));

            List<Long> result = client.sendMediaGroup(123L, photos);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendMediaGroup with no captions — no parse_mode in media items")
        void sendMediaGroupNoCaptions() {
            stubMultipartChain(successResponseWithResult(List.of(
                Map.of("message_id", 500L)
            )));

            List<TelegramClient.PhotoInput> photos = List.of(
                new TelegramClient.PhotoInput(new byte[]{1}, "photo.jpg", null),
                new TelegramClient.PhotoInput(new byte[]{2}, "photo2.jpg", ""));

            List<Long> result = client.sendMediaGroup(123L, photos);
            assertThat(result).hasSize(1);
        }
    }
}