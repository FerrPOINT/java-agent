package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CallbackQueryHandlerTest {

    private TelegramClient client;
    private ProviderKeyboardBuilder providerKeyboardBuilder;
    private ModelKeyboardBuilder modelKeyboardBuilder;
    private InlineKeyboardBuilder inlineKeyboardBuilder;
    private BotSessionStore sessionStore;
    private BotProperties properties;
    private CallbackQueryHandler handler;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        providerKeyboardBuilder = new ProviderKeyboardBuilder();
        modelKeyboardBuilder = new ModelKeyboardBuilder();
        inlineKeyboardBuilder = new InlineKeyboardBuilder(new com.fasterxml.jackson.databind.ObjectMapper());
        sessionStore = mock(BotSessionStore.class);
        properties = new BotProperties();
        handler = new CallbackQueryHandler(client, providerKeyboardBuilder, modelKeyboardBuilder,
            inlineKeyboardBuilder, sessionStore, properties);

        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(client.sendMessage(anyLong(), any())).thenReturn(Optional.of(1L));
        when(client.sendMessage(anyLong(), any(), any(), any(), any())).thenReturn(Optional.of(1L));
    }

    @Test
    void handle_mpCallback_setsModel() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);

        UpdateEvent event = callbackEvent("cq-1", "mp:kimi-k2");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("Model set to: kimi-k2");
        verify(sessionStore).setModelOverride(session.getId(), "kimi-k2");
    }

    @Test
    void handle_mpCallback_noSession_returnsError() {
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(null);

        UpdateEvent event = callbackEvent("cq-2", "mp:gpt-4");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("No active session");
    }

    @Test
    void handle_ppCallback_sendsProviderModels() {
        properties.getAvailableModels().add("kimi-k2");
        properties.getAvailableModels().add("gpt-4");

        UpdateEvent event = callbackEvent("cq-3", "pp:openai");
        String result = handler.handle(event);

        // Should send a message with inline keyboard
        verify(client).sendMessage(eq(123L), eq("Models for openai:"), any(), any(), any());
    }

    @Test
    void handle_ppBack_sendsProviderList() {
        UpdateEvent event = callbackEvent("cq-4", "pp:back");
        String result = handler.handle(event);

        verify(client).sendMessage(eq(123L), eq("Select a provider:"), any(), any(), any());
    }

    @Test
    void handle_mppCallback_sendsModelPage() {
        properties.getAvailableModels().add("model1");
        properties.getAvailableModels().add("model2");

        UpdateEvent event = callbackEvent("cq-5", "mpp:0");
        String result = handler.handle(event);

        verify(client).sendMessage(eq(123L), contains("Select a model"), any(), any(), any());
    }

    @Test
    void handle_unknownCommand_returnsUnknownAction() {
        UpdateEvent event = callbackEvent("cq-6", "unknown:foo");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("Unknown action: unknown");
    }

    @Test
    void handle_nullCallbackData_returnsNull() {
        UpdateEvent event = callbackEvent("cq-7", null);
        String result = handler.handle(event);

        assertThat(result).isNull();
        verify(client).answerCallbackQuery("cq-7", "Unknown action", false);
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

    // ─── Helpers ──────────────────────────────────────────────────

    private UpdateEvent callbackEvent(String callbackQueryId, String callbackData) {
        return new UpdateEvent(100L, UpdateEvent.Type.CALLBACK_QUERY, 123L, 456L,
            "jdoe", null, null, null, null,
            callbackQueryId, callbackData, null, false, null, null);
    }
}