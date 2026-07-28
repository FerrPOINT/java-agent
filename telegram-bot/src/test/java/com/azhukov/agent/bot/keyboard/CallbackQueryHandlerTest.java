package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CallbackQueryHandlerTest {

    private TelegramClient client;
    private CallbackQueryHandler handler;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        handler = new CallbackQueryHandler(client);
    }

    @Test
    void handle_modelCallback_returnsModelResponse() {
        UpdateEvent event = callbackEvent("cq-1", "model:gpt-4");
        when(client.answerCallbackQuery("cq-1", "Model switched to gpt-4", false))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Model switched to gpt-4");
        verify(client).answerCallbackQuery("cq-1", "Model switched to gpt-4", false);
    }

    @Test
    void handle_modeCallback_returnsModeResponse() {
        UpdateEvent event = callbackEvent("cq-2", "mode:verbose");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Mode set to verbose");
    }

    @Test
    void handle_confirmCallback_returnsConfirmResponse() {
        UpdateEvent event = callbackEvent("cq-3", "confirm:delete");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Confirmed: delete");
    }

    @Test
    void handle_cancelCallback_returnsCancelResponse() {
        UpdateEvent event = callbackEvent("cq-4", "cancel:noop");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Action cancelled");
    }

    @Test
    void handle_selectCallback_returnsSelectResponse() {
        UpdateEvent event = callbackEvent("cq-5", "select:option3");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Selected: option3");
    }

    @Test
    void handle_unknownCommand_returnsUnknownAction() {
        UpdateEvent event = callbackEvent("cq-6", "unknown:foo");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Unknown action: unknown");
    }

    @Test
    void handle_noColon_treatsEntireDataAsCommand() {
        UpdateEvent event = callbackEvent("cq-7", "cancel");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Action cancelled");
    }

    @Test
    void handle_nullCallbackData_answersUnknownAction() {
        UpdateEvent event = callbackEvent("cq-8", null);
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isNull();
        verify(client).answerCallbackQuery("cq-8", "Unknown action", false);
    }

    @Test
    void handle_blankCallbackData_answersUnknownAction() {
        UpdateEvent event = callbackEvent("cq-9", "");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isNull();
        verify(client).answerCallbackQuery("cq-9", "Unknown action", false);
    }

    @Test
    void handle_nonCallbackEvent_returnsNull() {
        UpdateEvent event = new UpdateEvent(200L, UpdateEvent.Type.TEXT, 123L, 456L,
            "jdoe", "Hello", null, null, null,
            null, null, null, false, null, null);

        String result = handler.handle(event);

        assertThat(result).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void handle_nullEvent_returnsNull() {
        String result = handler.handle(null);

        assertThat(result).isNull();
        verifyNoInteractions(client);
    }

    @Test
    void handle_alwaysAnswersCallbackQuery() {
        UpdateEvent event = callbackEvent("cq-10", "model:kimi-k2");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        handler.handle(event);

        verify(client).answerCallbackQuery(eq("cq-10"), anyString(), eq(false));
    }

    @Test
    void handle_emptyCommand_returnsUnknownCommand() {
        UpdateEvent event = callbackEvent("cq-11", ":value");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Unknown command");
    }

    @Test
    void handle_emptyValue_stillWorks() {
        UpdateEvent event = callbackEvent("cq-12", "model:");
        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean()))
            .thenReturn(true);

        String result = handler.handle(event);

        assertThat(result).isEqualTo("Model switched to ");
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private UpdateEvent callbackEvent(String callbackQueryId, String callbackData) {
        return new UpdateEvent(100L, UpdateEvent.Type.CALLBACK_QUERY, 123L, 456L,
            "jdoe", null, null, null, null,
            callbackQueryId, callbackData, null, false, null, null);
    }
}