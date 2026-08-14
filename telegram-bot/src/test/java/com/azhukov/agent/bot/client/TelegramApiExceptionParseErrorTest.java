package com.azhukov.agent.bot.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TelegramApiException#isParseError()} — P1-6.
 */
class TelegramApiExceptionParseErrorTest {

    @Test
    void isParseError_trueForCanParseEntities() {
        TelegramApiException ex = new TelegramApiException(400, "Bad Request: can't parse entities: Character '-' is reserved");
        assertThat(ex.isParseError()).isTrue();
    }

    @Test
    void isParseError_trueForCanParseEntitiesNoApostrophe() {
        TelegramApiException ex = new TelegramApiException(400, "Bad Request: can_parse_entities at byte offset 42");
        assertThat(ex.isParseError()).isTrue();
    }

    @Test
    void isParseError_falseForRateLimit() {
        TelegramApiException ex = new TelegramApiException(429, "Too Many Requests");
        assertThat(ex.isParseError()).isFalse();
    }

    @Test
    void isParseError_falseForMessageTooLong() {
        TelegramApiException ex = new TelegramApiException(400, "Bad Request: message is too long");
        assertThat(ex.isParseError()).isFalse();
    }

    @Test
    void isParseError_falseForNullDescription() {
        TelegramApiException ex = new TelegramApiException(400, null);
        assertThat(ex.isParseError()).isFalse();
    }

    @Test
    void isParseError_caseInsensitive() {
        TelegramApiException ex = new TelegramApiException(400, "CAN_PARSE_ENTITIES error");
        assertThat(ex.isParseError()).isTrue();
    }
}