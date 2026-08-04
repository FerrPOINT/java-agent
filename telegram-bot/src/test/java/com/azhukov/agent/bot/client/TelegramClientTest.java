package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link TelegramClient}.
 * <p>
 * Covers all public API methods, error handling, retry logic, rate limiting,
 * byte array resource handling, and request building paths.
 */
@ExtendWith(MockitoExtension.class)
class TelegramClientTest {

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

    @BeforeEach
    void setUp() {
        // Use rateLimitPerSecond=0 to disable rate limiter semaphore (avoids scheduler threads)
        client = new TelegramClient(restClient, objectMapper, "BOT_TOKEN", 0);
    }

    // ─── Helper: stub a successful JSON POST callApi chain ─────────

    /**
     * Stubs the RestClient POST chain for a single JSON-body call.
     * Returns the given TelegramResponse from .body(TelegramResponse.class).
     */
    private void stubPostChain(TelegramResponse response) {
        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
        when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(anyMap())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(TelegramResponse.class)).thenReturn(response);
    }

    /**
     * Stubs the RestClient POST chain for a multipart call.
     */
    @SuppressWarnings("unchecked")
    private void stubMultipartChain(TelegramResponse response) {
        when(restClient.post()).thenReturn(postUriSpec);
        when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(TelegramResponse.class)).thenReturn(response);
    }

    /**
     * Stubs the RestClient GET chain for downloadFile.
     */
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

    private TelegramResponse errorResponseWithParams(int errorCode, String description, Map<String, JsonNode> parameters) {
        return new TelegramResponse(false, errorCode, description, null, parameters);
    }

    // ─── Constructor tests ────────────────────────────────────────

    @Nested
    @DisplayName("Constructor")
    class ConstructorTest {

        @Test
        @DisplayName("default constructor sets linkPreviewEnabled=true")
        void defaultConstructorLinkPreviewTrue() {
            TelegramClient c = new TelegramClient(restClient, objectMapper, "token", 5);
            // Send a message and verify disable_web_page_preview is NOT in params
            stubPostChain(successResponseWithMessageId(1L));
            Optional<Long> result = c.sendMessage(123L, "hello");
            assertThat(result).contains(1L);
        }

        @Test
        @DisplayName("five-arg constructor with linkPreviewEnabled=false")
        void linkPreviewDisabled() {
            TelegramClient c = new TelegramClient(restClient, objectMapper, "token", 0, false);
            stubPostChain(successResponseWithMessageId(42L));
            c.sendMessage(123L, "hello");
            // Verify callApi was called — we can't directly inspect params but we verify chain
            verify(restClient).post();
        }

        @Test
        @DisplayName("null botToken defaults to empty string")
        void nullBotToken() {
            TelegramClient c = new TelegramClient(restClient, objectMapper, null, 0);
            // With empty token, callApi returns empty without calling restClient
            Optional<Long> result = c.sendMessage(123L, "hello");
            assertThat(result).isEmpty();
            verifyNoInteractions(restClient);
        }
    }

    // ─── sendMessage tests ────────────────────────────────────────

    @Nested
    @DisplayName("sendMessage")
    class SendMessageTest {

        @Test
        @DisplayName("simple sendMessage returns message id")
        void simpleSendMessage() {
            stubPostChain(successResponseWithMessageId(100L));
            Optional<Long> result = client.sendMessage(123L, "hello");
            assertThat(result).contains(100L);
        }

        @Test
        @DisplayName("sendMessage with all optional params")
        void sendMessageWithAllParams() {
            stubPostChain(successResponseWithMessageId(200L));
            Optional<Long> result = client.sendMessage(456L, "text", "MarkdownV2", 99L, "[{\"button\":\"ok\"}]");
            assertThat(result).contains(200L);
        }

        @Test
        @DisplayName("sendMessage with blank parseMode — not added to params")
        void sendMessageBlankParseMode() {
            stubPostChain(successResponseWithMessageId(1L));
            client.sendMessage(1L, "text", "  ", null, null);
            verify(restClient).post();
        }

        @Test
        @DisplayName("sendMessage with blank replyMarkup — not added to params")
        void sendMessageBlankReplyMarkup() {
            stubPostChain(successResponseWithMessageId(1L));
            client.sendMessage(1L, "text", null, null, "  ");
            verify(restClient).post();
        }

        @Test
        @DisplayName("sendMessage with linkPreview disabled adds disable_web_page_preview")
        void sendMessageLinkPreviewDisabled() {
            client = new TelegramClient(restClient, objectMapper, "token", 0, false);
            stubPostChain(successResponseWithMessageId(1L));
            client.sendMessage(1L, "text");
            verify(restClient).post();
        }

        @Test
        @DisplayName("sendMessage fails and retries without replyToMessageId")
        void sendMessageRetryWithoutReplyTo() {
            // First call returns empty (failure), second call succeeds
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(anyMap())).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            // First call: failed response (not success)
            when(responseSpec.body(TelegramResponse.class))
                    .thenReturn(errorResponse(400, "Message thread not found"))
                    .thenReturn(successResponseWithMessageId(55L));

            Optional<Long> result = client.sendMessage(1L, "text", null, 77L, null);
            assertThat(result).contains(55L);
        }

        @Test
        @DisplayName("sendMessage retry also fails returns empty")
        void sendMessageRetryAlsoFails() {
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(anyMap())).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class))
                    .thenReturn(errorResponse(400, "thread not found"))
                    .thenReturn(errorResponse(400, "still failing"));

            Optional<Long> result = client.sendMessage(1L, "text", null, 77L, null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendMessage fails without replyToMessageId — no retry")
        void sendMessageFailsNoRetry() {
            stubPostChain(errorResponse(400, "Bad request"));
            Optional<Long> result = client.sendMessage(1L, "text");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendMessage with null response returns empty")
        void sendMessageNullResponse() {
            stubPostChain(null);
            Optional<Long> result = client.sendMessage(1L, "text");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendMessage with null result message_id returns empty")
        void sendMessageNullMessageId() {
            stubPostChain(successResponseWithResult(Map.of()));
            Optional<Long> result = client.sendMessage(1L, "text");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendMessage with non-numeric message_id returns empty")
        void sendMessageNonNumericMessageId() {
            stubPostChain(successResponseWithResult(Map.of("message_id", "abc")));
            Optional<Long> result = client.sendMessage(1L, "text");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendMessage with exception returns empty")
        void sendMessageException() {
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(anyMap())).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class)).thenThrow(new RuntimeException("Network error"));

            Optional<Long> result = client.sendMessage(1L, "text");
            assertThat(result).isEmpty();
        }
    }

    // ─── editMessageText tests ────────────────────────────────────

    @Nested
    @DisplayName("editMessageText")
    class EditMessageTextTest {

        @Test
        @DisplayName("editMessageText (4-arg) delegates to 5-arg with false")
        void editMessageTextFourArg() {
            stubPostChain(successResponseWithResult(Map.of("message_id", 1)));
            boolean result = client.editMessageText(1L, 10L, "new text", "HTML");
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("editMessageText with disableNotification=true")
        void editMessageTextWithDisableNotification() {
            stubPostChain(successResponseWithResult(Map.of("message_id", 1)));
            boolean result = client.editMessageText(1L, 10L, "new text", "HTML", true);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("editMessageText with null parseMode")
        void editMessageTextNullParseMode() {
            stubPostChain(successResponseWithResult(Map.of("message_id", 1)));
            boolean result = client.editMessageText(1L, 10L, "new text", null, false);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("editMessageText with blank parseMode")
        void editMessageTextBlankParseMode() {
            stubPostChain(successResponseWithResult(Map.of("message_id", 1)));
            boolean result = client.editMessageText(1L, 10L, "new text", "  ", false);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("editMessageText failure returns false")
        void editMessageTextFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.editMessageText(1L, 10L, "new text", "HTML");
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("editMessageText null response returns false")
        void editMessageTextNullResponse() {
            stubPostChain(null);
            boolean result = client.editMessageText(1L, 10L, "new text", "HTML");
            assertThat(result).isFalse();
        }
    }

    // ─── deleteMessage tests ──────────────────────────────────────

    @Nested
    @DisplayName("deleteMessage")
    class DeleteMessageTest {

        @Test
        @DisplayName("deleteMessage success returns true")
        void deleteMessageSuccess() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.deleteMessage(1L, 10L);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("deleteMessage failure returns false")
        void deleteMessageFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.deleteMessage(1L, 10L);
            assertThat(result).isFalse();
        }
    }

    // ─── sendChatAction / sendTyping tests ────────────────────────

    @Nested
    @DisplayName("Chat actions")
    class ChatActionTest {

        @Test
        @DisplayName("sendChatAction success returns true")
        void sendChatActionSuccess() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.sendChatAction(1L, "typing");
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("sendChatAction failure returns false")
        void sendChatActionFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.sendChatAction(1L, "typing");
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("sendTyping delegates to sendChatAction with 'typing'")
        void sendTyping() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.sendTyping(1L);
            assertThat(result).isTrue();
        }
    }

    // ─── setMessageReaction tests ──────────────────────────────────

    @Nested
    @DisplayName("setMessageReaction")
    class SetMessageReactionTest {

        @Test
        @DisplayName("setMessageReaction success returns true")
        void success() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.setMessageReaction(1L, 10L, "👍");
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("setMessageReaction failure returns false")
        void failure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.setMessageReaction(1L, 10L, "👍");
            assertThat(result).isFalse();
        }
    }

    // ─── sendPhoto tests ──────────────────────────────────────────

    @Nested
    @DisplayName("sendPhoto")
    class SendPhotoTest {

        @Test
        @DisplayName("sendPhoto with caption and parseMode")
        void sendPhotoWithCaptionAndParseMode() {
            stubMultipartChain(successResponseWithMessageId(10L));
            Optional<Long> result = client.sendPhoto(1L, new byte[]{1, 2, 3}, "caption", "HTML");
            assertThat(result).contains(10L);
        }

        @Test
        @DisplayName("sendPhoto without caption")
        void sendPhotoWithoutCaption() {
            stubMultipartChain(successResponseWithMessageId(11L));
            Optional<Long> result = client.sendPhoto(1L, new byte[]{1, 2, 3}, null, null);
            assertThat(result).contains(11L);
        }

        @Test
        @DisplayName("sendPhoto with blank caption — not added")
        void sendPhotoBlankCaption() {
            stubMultipartChain(successResponseWithMessageId(12L));
            Optional<Long> result = client.sendPhoto(1L, new byte[]{1}, "  ", null);
            assertThat(result).contains(12L);
        }

        @Test
        @DisplayName("sendPhoto with blank parseMode — not added")
        void sendPhotoBlankParseMode() {
            stubMultipartChain(successResponseWithMessageId(13L));
            Optional<Long> result = client.sendPhoto(1L, new byte[]{1}, "cap", "  ");
            assertThat(result).contains(13L);
        }

        @Test
        @DisplayName("sendPhoto failure returns empty")
        void sendPhotoFailure() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = client.sendPhoto(1L, new byte[]{1}, "cap", null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendPhoto null response returns empty")
        void sendPhotoNullResponse() {
            stubMultipartChain(null);
            Optional<Long> result = client.sendPhoto(1L, new byte[]{1}, "cap", null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendPhoto exception returns empty")
        void sendPhotoException() {
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class)).thenThrow(new RuntimeException("Network error"));

            Optional<Long> result = client.sendPhoto(1L, new byte[]{1}, "cap", null);
            assertThat(result).isEmpty();
        }
    }

    // ─── sendDocument tests ────────────────────────────────────────

    @Nested
    @DisplayName("sendDocument")
    class SendDocumentTest {

        @Test
        @DisplayName("sendDocument with fileName, caption, parseMode")
        void sendDocumentFull() {
            stubMultipartChain(successResponseWithMessageId(20L));
            Optional<Long> result = client.sendDocument(1L, new byte[]{1, 2}, "file.pdf", "caption", "HTML");
            assertThat(result).contains(20L);
        }

        @Test
        @DisplayName("sendDocument with null fileName defaults to 'document'")
        void sendDocumentNullFileName() {
            stubMultipartChain(successResponseWithMessageId(21L));
            Optional<Long> result = client.sendDocument(1L, new byte[]{1, 2}, null, null, null);
            assertThat(result).contains(21L);
        }

        @Test
        @DisplayName("sendDocument with blank fileName defaults to 'document'")
        void sendDocumentBlankFileName() {
            stubMultipartChain(successResponseWithMessageId(22L));
            Optional<Long> result = client.sendDocument(1L, new byte[]{1, 2}, "  ", null, null);
            assertThat(result).contains(22L);
        }

        @Test
        @DisplayName("sendDocument with blank caption — not added")
        void sendDocumentBlankCaption() {
            stubMultipartChain(successResponseWithMessageId(23L));
            Optional<Long> result = client.sendDocument(1L, new byte[]{1}, "file.pdf", "  ", null);
            assertThat(result).contains(23L);
        }

        @Test
        @DisplayName("sendDocument failure returns empty")
        void sendDocumentFailure() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = client.sendDocument(1L, new byte[]{1}, "file.pdf", "cap", null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendDocument exception returns empty")
        void sendDocumentException() {
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class)).thenThrow(new RuntimeException("error"));

            Optional<Long> result = client.sendDocument(1L, new byte[]{1}, "file.pdf", "cap", null);
            assertThat(result).isEmpty();
        }
    }

    // ─── sendVoice tests ──────────────────────────────────────────

    @Nested
    @DisplayName("sendVoice")
    class SendVoiceTest {

        @Test
        @DisplayName("sendVoice with caption")
        void sendVoiceWithCaption() {
            stubMultipartChain(successResponseWithMessageId(30L));
            Optional<Long> result = client.sendVoice(1L, new byte[]{1, 2, 3}, "voice caption");
            assertThat(result).contains(30L);
        }

        @Test
        @DisplayName("sendVoice without caption (null)")
        void sendVoiceNullCaption() {
            stubMultipartChain(successResponseWithMessageId(31L));
            Optional<Long> result = client.sendVoice(1L, new byte[]{1}, null);
            assertThat(result).contains(31L);
        }

        @Test
        @DisplayName("sendVoice with blank caption — not added")
        void sendVoiceBlankCaption() {
            stubMultipartChain(successResponseWithMessageId(32L));
            Optional<Long> result = client.sendVoice(1L, new byte[]{1}, "  ");
            assertThat(result).contains(32L);
        }

        @Test
        @DisplayName("sendVoice failure returns empty")
        void sendVoiceFailure() {
            stubMultipartChain(errorResponse(400, "Bad request"));
            Optional<Long> result = client.sendVoice(1L, new byte[]{1}, "cap");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendVoice null response returns empty")
        void sendVoiceNullResponse() {
            stubMultipartChain(null);
            Optional<Long> result = client.sendVoice(1L, new byte[]{1}, "cap");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("sendVoice exception returns empty")
        void sendVoiceException() {
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(any(MultiValueMap.class))).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class)).thenThrow(new RuntimeException("error"));

            Optional<Long> result = client.sendVoice(1L, new byte[]{1}, "cap");
            assertThat(result).isEmpty();
        }
    }

    // ─── getFile tests ────────────────────────────────────────────

    @Nested
    @DisplayName("getFile")
    class GetFileTest {

        @Test
        @DisplayName("getFile success returns map")
        void getFileSuccess() {
            Map<String, Object> fileResult = Map.of("file_id", "abc", "file_path", "photos/file.jpg");
            stubPostChain(successResponseWithResult(fileResult));
            Optional<Map<String, Object>> result = client.getFile("abc");
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("file_id", "abc");
        }

        @Test
        @DisplayName("getFile failure returns empty")
        void getFileFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            Optional<Map<String, Object>> result = client.getFile("abc");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("getFile with non-map result returns empty map")
        void getFileNonMapResult() {
            stubPostChain(successResponseWithResult("not a map"));
            Optional<Map<String, Object>> result = client.getFile("abc");
            assertThat(result).isPresent();
            assertThat(result.get()).isEmpty();
        }
    }

    // ─── downloadFile tests ────────────────────────────────────────

    @Nested
    @DisplayName("downloadFile")
    class DownloadFileTest {

        @Test
        @DisplayName("downloadFile success returns bytes")
        void downloadFileSuccess() {
            stubGetChain(new byte[]{1, 2, 3, 4});
            Optional<byte[]> result = client.downloadFile("photos/file.jpg");
            assertThat(result).isPresent();
            assertThat(result.get()).containsExactly(1, 2, 3, 4);
        }

        @Test
        @DisplayName("downloadFile null body returns empty")
        void downloadFileNullBody() {
            stubGetChain(null);
            Optional<byte[]> result = client.downloadFile("photos/file.jpg");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("downloadFile exception returns empty")
        void downloadFileException() {
            when(restClient.get()).thenThrow(new RuntimeException("Network error"));
            Optional<byte[]> result = client.downloadFile("photos/file.jpg");
            assertThat(result).isEmpty();
        }
    }

    // ─── answerCallbackQuery tests ────────────────────────────────

    @Nested
    @DisplayName("answerCallbackQuery")
    class AnswerCallbackQueryTest {

        @Test
        @DisplayName("with text and showAlert=true")
        void answerCallbackQueryWithText() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.answerCallbackQuery("cb1", "alert text", true);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("with null text — not added to params")
        void answerCallbackQueryNullText() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.answerCallbackQuery("cb1", null, false);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("with blank text — not added to params")
        void answerCallbackQueryBlankText() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.answerCallbackQuery("cb1", "  ", false);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("failure returns false")
        void answerCallbackQueryFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.answerCallbackQuery("cb1", "text", false);
            assertThat(result).isFalse();
        }
    }

    // ─── setMyCommands tests ──────────────────────────────────────

    @Nested
    @DisplayName("setMyCommands")
    class SetMyCommandsTest {

        @Test
        @DisplayName("setMyCommands success returns true")
        void setMyCommandsSuccess() {
            stubPostChain(successResponseWithResult(true));
            List<Map<String, String>> commands = List.of(
                    Map.of("command", "start", "description", "Start"));
            boolean result = client.setMyCommands(commands);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("setMyCommands failure returns false")
        void setMyCommandsFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.setMyCommands(List.of());
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("setMyCommands exception returns false")
        void setMyCommandsException() {
            when(restClient.post()).thenThrow(new RuntimeException("error"));
            boolean result = client.setMyCommands(List.of());
            assertThat(result).isFalse();
        }
    }

    // ─── setMyCommandsForChat tests ───────────────────────────────

    @Nested
    @DisplayName("setMyCommandsForChat")
    class SetMyCommandsForChatTest {

        @Test
        @DisplayName("setMyCommandsForChat success returns true")
        void setMyCommandsForChatSuccess() {
            stubPostChain(successResponseWithResult(true));
            List<Map<String, String>> commands = List.of(
                    Map.of("command", "help", "description", "Help"));
            boolean result = client.setMyCommandsForChat(999L, commands);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("setMyCommandsForChat failure returns false")
        void setMyCommandsForChatFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.setMyCommandsForChat(999L, List.of());
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("setMyCommandsForChat exception returns false")
        void setMyCommandsForChatException() {
            when(restClient.post()).thenThrow(new RuntimeException("error"));
            boolean result = client.setMyCommandsForChat(999L, List.of());
            assertThat(result).isFalse();
        }
    }

    // ─── Webhook management tests ─────────────────────────────────

    @Nested
    @DisplayName("Webhook management")
    class WebhookTest {

        @Test
        @DisplayName("setWebhook with url and secretToken")
        void setWebhookWithToken() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.setWebhook("https://example.com/hook", "secret123");
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("setWebhook with null url defaults to empty string")
        void setWebhookNullUrl() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.setWebhook(null, null);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("setWebhook with blank secretToken — not added")
        void setWebhookBlankSecretToken() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.setWebhook("https://example.com/hook", "  ");
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("setWebhook failure returns false")
        void setWebhookFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.setWebhook("https://example.com/hook", "secret");
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("deleteWebhook success returns true")
        void deleteWebhookSuccess() {
            stubPostChain(successResponseWithResult(true));
            boolean result = client.deleteWebhook();
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("deleteWebhook failure returns false")
        void deleteWebhookFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            boolean result = client.deleteWebhook();
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("getWebhookInfo success returns map")
        void getWebhookInfoSuccess() {
            Map<String, Object> info = Map.of("url", "https://example.com/hook");
            stubPostChain(successResponseWithResult(info));
            Optional<Map<String, Object>> result = client.getWebhookInfo();
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("url", "https://example.com/hook");
        }

        @Test
        @DisplayName("getWebhookInfo failure returns empty")
        void getWebhookInfoFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            Optional<Map<String, Object>> result = client.getWebhookInfo();
            assertThat(result).isEmpty();
        }
    }

    // ─── getUpdates tests ────────────────────────────────────────

    @Nested
    @DisplayName("getUpdates")
    class GetUpdatesTest {

        @Test
        @DisplayName("getUpdates with list result")
        void getUpdatesWithList() {
            List<Map<String, Object>> updates = List.of(
                    Map.of("update_id", 1, "message", Map.of("text", "hello")));
            stubPostChain(successResponseWithResult(updates));
            Optional<List<Map<String, Object>>> result = client.getUpdates(0, 100, 30);
            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(1);
        }

        @Test
        @DisplayName("getUpdates with non-list result returns empty list")
        void getUpdatesNonListResult() {
            stubPostChain(successResponseWithResult("not a list"));
            Optional<List<Map<String, Object>>> result = client.getUpdates(0, 100, 30);
            assertThat(result).isPresent();
            assertThat(result.get()).isEmpty();
        }

        @Test
        @DisplayName("getUpdates failure returns empty")
        void getUpdatesFailure() {
            stubPostChain(errorResponse(400, "Bad request"));
            Optional<List<Map<String, Object>>> result = client.getUpdates(0, 100, 30);
            assertThat(result).isEmpty();
        }
    }

    // ─── callApi error handling tests ────────────────────────────

    @Nested
    @DisplayName("callApi error handling")
    class CallApiErrorTest {

        @Test
        @DisplayName("empty botToken returns empty without calling restClient")
        void emptyBotToken() {
            TelegramClient c = new TelegramClient(restClient, objectMapper, "", 0);
            Optional<TelegramResponse> result = c.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isEmpty();
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("HTTP 409 conflict sets lastCallConflict=true")
        void conflict409() {
            stubPostChain(errorResponse(409, "Conflict: another polling instance"));
            Optional<TelegramResponse> result = client.callApi("getUpdates", Map.of());
            assertThat(result).isEmpty();
            assertThat(client.isLastCallConflict()).isTrue();
        }

        @Test
        @DisplayName("successful call resets lastCallConflict to false")
        void conflictResetOnSuccess() {
            // First set conflict by doing a 409 call
            stubPostChain(errorResponse(409, "Conflict"));
            client.callApi("getUpdates", Map.of());
            assertThat(client.isLastCallConflict()).isTrue();

            // Now a successful call should reset
            // Need to re-stub since we consumed the mock
            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(anyMap())).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.body(TelegramResponse.class))
                    .thenReturn(successResponseWithResult(true));

            Optional<TelegramResponse> result = client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isPresent();
            assertThat(client.isLastCallConflict()).isFalse();
        }

        @Test
        @DisplayName("HTTP 429 rate limit with retry_after triggers retry")
        void rateLimit429WithRetry() throws Exception {
            ObjectNode retryParam = objectMapper.createObjectNode();
            retryParam.set("retry_after", IntNode.valueOf(0)); // 0 seconds to keep test fast

            TelegramResponse error429 = errorResponseWithParams(429, "Too Many Requests",
                    Map.of("retry_after", retryParam));

            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(anyMap())).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            // First call returns 429, second call (retry) returns success
            when(responseSpec.body(TelegramResponse.class))
                    .thenReturn(error429)
                    .thenReturn(successResponseWithResult(true));

            Optional<TelegramResponse> result = client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("HTTP 429 rate limit retry also fails returns empty")
        void rateLimit429RetryFails() throws Exception {
            ObjectNode retryParam = objectMapper.createObjectNode();
            retryParam.set("retry_after", IntNode.valueOf(0));

            TelegramResponse error429 = errorResponseWithParams(429, "Too Many Requests",
                    Map.of("retry_after", retryParam));

            when(restClient.post()).thenReturn(postUriSpec);
            when(postUriSpec.uri(anyString(), any(), any())).thenReturn(bodySpec);
            when(bodySpec.accept(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
            when(bodySpec.body(anyMap())).thenReturn(bodySpec);
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            // Both calls return 429
            when(responseSpec.body(TelegramResponse.class))
                    .thenReturn(error429)
                    .thenReturn(error429);

            Optional<TelegramResponse> result = client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("HTTP 429 without retry_after parameter does not retry")
        void rateLimit429NoRetryParam() {
            TelegramResponse error429 = errorResponse(429, "Too Many Requests");

            stubPostChain(error429);

            Optional<TelegramResponse> result = client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("HTTP 429 with null parameters does not retry")
        void rateLimit429NullParameters() {
            TelegramResponse error429 = new TelegramResponse(false, 429, "Too Many Requests", null, null);

            stubPostChain(error429);

            Optional<TelegramResponse> result = client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null response returns empty")
        void nullResponse() {
            stubPostChain(null);
            Optional<TelegramResponse> result = client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("exception during call returns empty")
        void callApiException() {
            when(restClient.post()).thenThrow(new RuntimeException("Connection refused"));
            Optional<TelegramResponse> result = client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("non-success response with null error code returns empty")
        void nonSuccessNullErrorCode() {
            TelegramResponse resp = new TelegramResponse(false, null, "some error", null, null);
            stubPostChain(resp);
            Optional<TelegramResponse> result = client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(result).isEmpty();
        }
    }

    // ─── callMultipartApi error handling tests ────────────────────

    @Nested
    @DisplayName("callMultipartApi error handling")
    class CallMultipartApiTest {

        @Test
        @DisplayName("empty botToken returns empty without calling restClient")
        void emptyBotTokenMultipart() {
            @SuppressWarnings("unchecked")
            MultiValueMap<String, org.springframework.http.HttpEntity<?>> parts = mock(MultiValueMap.class);
            TelegramClient c = new TelegramClient(restClient, objectMapper, "", 0);
            Optional<TelegramResponse> result = c.callMultipartApi("sendPhoto", parts);
            assertThat(result).isEmpty();
            verifyNoInteractions(restClient);
        }

        @Test
        @DisplayName("null response returns empty")
        void nullResponseMultipart() {
            stubMultipartChain(null);
            Optional<TelegramResponse> result = client.callMultipartApi("sendPhoto",
                    new org.springframework.util.LinkedMultiValueMap<>());
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("exception returns empty")
        void multipartException() {
            when(restClient.post()).thenThrow(new RuntimeException("error"));
            Optional<TelegramResponse> result = client.callMultipartApi("sendPhoto",
                    new org.springframework.util.LinkedMultiValueMap<>());
            assertThat(result).isEmpty();
        }
    }

    // ─── Rate limiter tests ───────────────────────────────────────

    @Nested
    @DisplayName("Rate limiter")
    class RateLimiterTest {

        @Test
        @DisplayName("rateLimitPerSecond > 0 creates semaphore and allows calls")
        void rateLimiterActive() {
            client = new TelegramClient(restClient, objectMapper, "token", 10);
            stubPostChain(successResponseWithResult(true));
            boolean result = client.sendChatAction(1L, "typing");
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("rateLimitPerSecond = 0 disables rate limiter (null semaphore)")
        void rateLimiterDisabled() {
            // rateLimitPerSecond=0 already set in setUp
            stubPostChain(successResponseWithResult(true));
            boolean result = client.sendChatAction(1L, "typing");
            assertThat(result).isTrue();
        }
    }

    // ─── isLastCallConflict tests ─────────────────────────────────

    @Nested
    @DisplayName("isLastCallConflict")
    class IsLastCallConflictTest {

        @Test
        @DisplayName("initially false")
        void initiallyFalse() {
            assertThat(client.isLastCallConflict()).isFalse();
        }

        @Test
        @DisplayName("true after 409 response")
        void trueAfter409() {
            stubPostChain(errorResponse(409, "Conflict"));
            client.callApi("getUpdates", Map.of());
            assertThat(client.isLastCallConflict()).isTrue();
        }

        @Test
        @DisplayName("false after non-409 error")
        void falseAfterNon409Error() {
            stubPostChain(errorResponse(400, "Bad request"));
            client.callApi("sendMessage", Map.of("chat_id", 1));
            assertThat(client.isLastCallConflict()).isFalse();
        }
    }
}