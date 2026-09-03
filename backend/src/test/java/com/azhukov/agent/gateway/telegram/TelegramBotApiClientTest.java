package com.azhukov.agent.gateway.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class TelegramBotApiClientTest {

    private static final String BOT_TOKEN = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";
    private static final String SEND_MESSAGE_URL = "https://api.telegram.org/bot123456%3AABC-DEF1234ghIkl-zyx57W2v1u123ew11/sendMessage";
    private static final String SEND_CHAT_ACTION_URL = "https://api.telegram.org/bot123456%3AABC-DEF1234ghIkl-zyx57W2v1u123ew11/sendChatAction";
    private static final String SET_MESSAGE_REACTION_URL = "https://api.telegram.org/bot123456%3AABC-DEF1234ghIkl-zyx57W2v1u123ew11/setMessageReaction";
    private static final String CHAT_ID = "12345";
    private static final String TEXT = "hello world";

    @Test
    void sendMessageReturnsMessageIdOnSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_MESSAGE_URL))
            .andExpect(method(POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""
                {"chat_id":12345,"text":"hello world"}
                """))
            .andRespond(withSuccess("""
                {"ok":true,"result":{"message_id":42}}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendMessage(12345L, TEXT);

        assertThat(result).hasValue("42");
        server.verify();
    }

    @Test
    void sendMessageReturnsEmptyWhenTelegramReturnsOkFalse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_MESSAGE_URL))
            .andRespond(withSuccess("""
                {"ok":false,"description":"Unauthorized"}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendMessage(12345L, TEXT);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void sendMessageReturnsEmptyOnHttp500() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_MESSAGE_URL))
            .andRespond(withServerError().body("Internal Server Error"));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        Optional<String> result = client.sendMessage(12345L, TEXT);

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void sendMessageReturnsEmptyWhenBotTokenIsBlank() {
        RestClient.Builder builder = RestClient.builder();
        RestClient restClient = builder.build();

        TelegramBotApiClient client = new TelegramBotApiClient("", restClient);
        Optional<String> result = client.sendMessage(12345L, TEXT);

        assertThat(result).isEmpty();
    }

    @Test
    void sendChatActionReturnsTrueOnSuccessAndFalseOnFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SEND_CHAT_ACTION_URL))
            .andExpect(method(POST))
            .andRespond(withSuccess("""
                {"ok":true,"result":{"message_id":1}}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        assertThat(client.sendChatAction(12345L, "typing")).isTrue();

        server.reset();
        server.expect(requestTo(SEND_CHAT_ACTION_URL))
            .andRespond(withSuccess("""
                {"ok":false,"description":"Bad Request: chat not found"}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.sendChatAction(12345L, "typing")).isFalse();
        server.verify();
    }

    @Test
    void setMessageReactionSendsEmojiReactionAndClearPayloads() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SET_MESSAGE_REACTION_URL))
            .andExpect(method(POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""
                {"chat_id":12345,"message_id":99,"reaction":[{"type":"emoji","emoji":"👍"}]}
                """))
            .andRespond(withSuccess("""
                {"ok":true,"result":true}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        assertThat(client.setMessageReaction(12345L, 99L, "👍")).isTrue();

        server.reset();
        server.expect(requestTo(SET_MESSAGE_REACTION_URL))
            .andExpect(method(POST))
            .andExpect(content().json("""
                {"chat_id":12345,"message_id":99,"reaction":[]}
                """))
            .andRespond(withSuccess("""
                {"ok":true,"result":true}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.setMessageReaction(12345L, 99L, "")).isTrue();
        server.verify();
    }

    @Test
    void setMessageReactionReturnsFalseOnTelegramFailureOrBlankToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo(SET_MESSAGE_REACTION_URL))
            .andRespond(withSuccess("""
                {"ok":false,"description":"Bad Request: message not found"}
                """, MediaType.APPLICATION_JSON));

        TelegramBotApiClient client = new TelegramBotApiClient(BOT_TOKEN, restClient);
        assertThat(client.setMessageReaction(12345L, 99L, "👍")).isFalse();
        server.verify();

        assertThat(new TelegramBotApiClient("", RestClient.create()).setMessageReaction(12345L, 99L, "👍")).isFalse();
    }
}
