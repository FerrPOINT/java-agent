package com.azhukov.agent.gateway.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class TelegramBotApiClientMultipartTest {

    private static final String BOT_TOKEN = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";
    private static final String SEND_PHOTO_URL = "https://api.telegram.org/bot123456%3AABC-DEF1234ghIkl-zyx57W2v1u123ew11/sendPhoto";
    private static final String SEND_DOC_URL = "https://api.telegram.org/bot123456%3AABC-DEF1234ghIkl-zyx57W2v1u123ew11/sendDocument";

    @Test
    @DisplayName("sendPhoto returns message_id on success")
    void sendPhotoReturnsMessageIdOnSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_PHOTO_URL))
            .andExpect(request -> {
                MediaType ct = request.getHeaders().getContentType();
                assertThat(ct).isNotNull();
                assertThat(ct.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
            })
            .andRespond(withSuccess("""
                {"ok":true,"result":{"message_id":99}}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendPhoto(12345L, new byte[]{1, 2, 3}, "test caption");

        assertThat(result).hasValue("99");
        server.verify();
    }

    @Test
    @DisplayName("sendPhoto returns empty when bot token is blank")
    void sendPhotoReturnsEmptyWhenTokenBlank() {
        RestClient restClient = RestClient.builder().build();
        TelegramBotApiClient client = new TelegramBotApiClient("", restClient);
        Optional<String> result = client.sendPhoto(12345L, new byte[]{1, 2, 3}, "caption");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sendPhoto returns empty when image is null")
    void sendPhotoReturnsEmptyWhenImageNull() {
        RestClient restClient = RestClient.builder().build();
        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendPhoto(12345L, null, "caption");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sendPhoto returns empty when image is empty")
    void sendPhotoReturnsEmptyWhenImageEmpty() {
        RestClient restClient = RestClient.builder().build();
        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendPhoto(12345L, new byte[0], "caption");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sendPhoto handles null caption")
    void sendPhotoHandlesNullCaption() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_PHOTO_URL))
            .andRespond(withSuccess("""
                {"ok":true,"result":{"message_id":100}}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendPhoto(12345L, new byte[]{1}, null);
        assertThat(result).hasValue("100");
        server.verify();
    }

    @Test
    @DisplayName("sendPhoto returns empty on HTTP error")
    void sendPhotoReturnsEmptyOnHttpError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_PHOTO_URL))
            .andRespond(withServerError().body("Internal Server Error"));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendPhoto(12345L, new byte[]{1, 2}, "caption");
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("sendDocument returns message_id on success")
    void sendDocumentReturnsMessageIdOnSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_DOC_URL))
            .andExpect(request -> {
                MediaType ct = request.getHeaders().getContentType();
                assertThat(ct).isNotNull();
                assertThat(ct.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
            })
            .andRespond(withSuccess("""
                {"ok":true,"result":{"message_id":200}}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendDocument(12345L, new byte[]{4, 5, 6}, "file.pdf", "doc caption");

        assertThat(result).hasValue("200");
        server.verify();
    }

    @Test
    @DisplayName("sendDocument returns empty when bot token is blank")
    void sendDocumentReturnsEmptyWhenTokenBlank() {
        RestClient restClient = RestClient.builder().build();
        TelegramBotApiClient client = new TelegramBotApiClient("", restClient);
        Optional<String> result = client.sendDocument(12345L, new byte[]{1}, "file.pdf", "caption");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sendDocument returns empty when document is null")
    void sendDocumentReturnsEmptyWhenDocumentNull() {
        RestClient restClient = RestClient.builder().build();
        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendDocument(12345L, null, "file.pdf", "caption");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sendDocument returns empty when document is empty")
    void sendDocumentReturnsEmptyWhenDocumentEmpty() {
        RestClient restClient = RestClient.builder().build();
        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendDocument(12345L, new byte[0], "file.pdf", "caption");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("sendDocument handles null fileName and null caption")
    void sendDocumentHandlesNullFileNameAndCaption() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_DOC_URL))
            .andRespond(withSuccess("""
                {"ok":true,"result":{"message_id":300}}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendDocument(12345L, new byte[]{1, 2}, null, null);
        assertThat(result).hasValue("300");
        server.verify();
    }

    @Test
    @DisplayName("sendDocument returns empty on HTTP error")
    void sendDocumentReturnsEmptyOnHttpError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_DOC_URL))
            .andRespond(withServerError().body("Internal Server Error"));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendDocument(12345L, new byte[]{1}, "file.pdf", "caption");
        assertThat(result).isEmpty();
        server.verify();
    }
}