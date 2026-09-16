package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.client.TelegramClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClarificationStateStoreTest {

    private TelegramClient telegramClient;
    private ClarificationStateStore store;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        when(telegramClient.sendMessage(anyLong(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
            .thenReturn(Optional.of(99L));
        store = new ClarificationStateStore(new ObjectMapper(), new InlineKeyboardBuilder(new ObjectMapper()));
    }

    @Test
    void presentsChoicesAndReturnsSelectedAnswer() {
        store.present(123L, 0L, "{\"question\":\"Deploy where?\",\"choices\":[\"dev\",\"prod\"]}", telegramClient);

        ClarificationStateStore.CallbackOutcome outcome = store.handleCallback(123L, 99L, "1:0", telegramClient);

        assertThat(outcome.complete()).isTrue();
        assertThat(outcome.answer()).isEqualTo("Answer to clarification question 'Deploy where?': dev");
        verify(telegramClient).editMessageReplyMarkup(123L, 99L, null);
    }

    @Test
    void otherChoiceConsumesNextTypedAnswer() {
        store.present(123L, 0L, "{\"question\":\"Pick target\",\"choices\":[\"dev\",\"prod\"]}", telegramClient);

        ClarificationStateStore.CallbackOutcome outcome = store.handleCallback(123L, 99L, "1:other", telegramClient);

        assertThat(outcome.complete()).isFalse();
        assertThat(store.consumeTypedAnswer(123L, "staging"))
            .isEqualTo("Answer to clarification question 'Pick target': staging");
    }

    @Test
    void multiSelectWaitsForDoneAndKeepsSelections() {
        store.present(123L, 0L,
            "{\"question\":\"Choose regions\",\"choices\":[\"EU\",\"US\"],\"multi_select\":true}", telegramClient);

        assertThat(store.handleCallback(123L, 99L, "1:0", telegramClient).complete()).isFalse();
        assertThat(store.handleCallback(123L, 99L, "1:1", telegramClient).complete()).isFalse();
        ClarificationStateStore.CallbackOutcome done = store.handleCallback(123L, 99L, "1:done", telegramClient);

        assertThat(done.complete()).isTrue();
        assertThat(done.answer()).isEqualTo("Answer to clarification question 'Choose regions': EU, US");
    }
}
