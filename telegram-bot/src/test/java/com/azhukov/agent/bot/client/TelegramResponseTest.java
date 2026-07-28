package com.azhukov.agent.bot.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramResponseTest {

    @Test
    void successResponse_parsesCorrectly() {
        TelegramResponse response = new TelegramResponse(true, null, null,
            Map.of("message_id", 42, "text", "hello"));
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.resultMessageId()).isEqualTo("42");
        assertThat(response.resultMessageIdAsLong()).isEqualTo(42L);
    }

    @Test
    void errorResponse_returnsErrorMessage() {
        TelegramResponse response = new TelegramResponse(false, 400, "Bad Request", null);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorMessage()).isEqualTo("400: Bad Request");
    }

    @Test
    void errorResponse_noErrorCode_returnsDescription() {
        TelegramResponse response = new TelegramResponse(false, null, "Timeout", null);
        assertThat(response.errorMessage()).isEqualTo("Timeout");
    }

    @Test
    void resultAsMap_nonMapResult_returnsEmpty() {
        TelegramResponse response = new TelegramResponse(true, null, null, "not a map");
        assertThat(response.resultAsMap()).isEmpty();
    }

    @Test
    void resultAsList_listResult_returnsList() {
        TelegramResponse response = new TelegramResponse(true, null, null, java.util.List.of(Map.of("a", 1)));
        assertThat(response.resultAsList()).hasSize(1);
    }
}