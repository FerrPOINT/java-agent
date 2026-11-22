package com.azhukov.agent.bot.keyboard;

import com.azhukov.agent.bot.auth.AuthorizationService;
import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.AgentBackendClient;
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
    private AuthorizationService authorizationService;
    private AgentBackendClient backendClient;
    private CallbackQueryHandler handler;

    @BeforeEach
    void setUp() {
        client = mock(TelegramClient.class);
        providerKeyboardBuilder = new ProviderKeyboardBuilder();
        modelKeyboardBuilder = new ModelKeyboardBuilder();
        inlineKeyboardBuilder = new InlineKeyboardBuilder(new com.fasterxml.jackson.databind.ObjectMapper());
        sessionStore = mock(BotSessionStore.class);
        properties = new BotProperties();
        authorizationService = mock(AuthorizationService.class);
        backendClient = mock(AgentBackendClient.class);
        // By default, allow all users (allow-by-default = true)
        when(authorizationService.isAuthorized(anyLong(), anyString(), anyLong())).thenReturn(true);
        handler = new CallbackQueryHandler(client, providerKeyboardBuilder, modelKeyboardBuilder,
            inlineKeyboardBuilder, sessionStore, properties, authorizationService,
            mock(ClarifyInteractionRenderer.class));

        when(client.answerCallbackQuery(anyString(), anyString(), anyBoolean())).thenReturn(true);
        when(client.sendMessage(anyLong(), any())).thenReturn(Optional.of(1L));
        when(client.sendMessage(anyLong(), any(), any(), any(), any())).thenReturn(Optional.of(1L));
        when(client.editMessageReplyMarkup(anyLong(), anyLong(), any())).thenReturn(true);
    }

    @Test
    void handle_mpCallback_setsModel() {
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
        org.mockito.Mockito.lenient().when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(session);

        UpdateEvent event = callbackEvent("cq-1", "mp:kimi-k2");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("Model set to: kimi-k2");
        verify(sessionStore).setModelOverride(session.getId(), "kimi-k2");
    }

    @Test
    void forgedModelCallbackCannotSelectOutsideConfiguredList() {
        properties.getAvailableModels().add("app-test");
        assertThat(handler.handle(callbackEvent("cq-deny", "mp:unavailable")))
            .contains("Model not available");
        verify(sessionStore, never()).setModelOverride(any(), any());
        verify(sessionStore, never()).resolveOrCreate(anyString(), anyString(), anyString());
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

    // ─── P2-19: Provider keyboard filtering tests ──────────────────

    @Test
    void handle_ppCallback_filtersModelsByProviderPrefix() {
        properties.getAvailableModels().add("openai:gpt-4");
        properties.getAvailableModels().add("openai:gpt-4o");
        properties.getAvailableModels().add("anthropic:claude-3");

        UpdateEvent event = callbackEvent("cq-pp-1", "pp:openai");
        handler.handle(event);

        // Should send "Models for openai:" with filtered models
        verify(client).sendMessage(eq(123L), eq("Models for openai:"), any(), any(), any());
    }

    @Test
    void handle_ppCallback_filtersByProviderNamePattern() {
        // No prefix — match by name pattern
        properties.getAvailableModels().add("gpt-4");
        properties.getAvailableModels().add("claude-3");
        properties.getAvailableModels().add("gemini-pro");

        UpdateEvent event = callbackEvent("cq-pp-2", "pp:openai");
        handler.handle(event);

        verify(client).sendMessage(eq(123L), eq("Models for openai:"), any(), any(), any());
    }

    @Test
    void handle_ppCallback_anthropicFiltersClaudeModels() {
        properties.getAvailableModels().add("gpt-4");
        properties.getAvailableModels().add("claude-3-opus");
        properties.getAvailableModels().add("claude-3-sonnet");

        UpdateEvent event = callbackEvent("cq-pp-3", "pp:anthropic");
        handler.handle(event);

        verify(client).sendMessage(eq(123L), eq("Models for anthropic:"), any(), any(), any());
    }

    @Test
    void handle_ppCallback_unknownProviderShowsAllModelsAsFallback() {
        properties.getAvailableModels().add("gpt-4");
        properties.getAvailableModels().add("claude-3");

        UpdateEvent event = callbackEvent("cq-pp-4", "pp:unknown-provider");
        handler.handle(event);

        // Should still send the message — fallback to all models
        verify(client).sendMessage(eq(123L), eq("Models for unknown-provider:"), any(), any(), any());
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

    // ─── B4: Authorization tests ──────────────────────────────────

    @Test
    void handle_unauthorizedUser_answersNotAuthorized() {
        when(authorizationService.isAuthorized(456L, "jdoe", 123L)).thenReturn(false);

        UpdateEvent event = callbackEvent("cq-auth-1", "mp:kimi-k2");
        String result = handler.handle(event);

        assertThat(result).isNull();
        verify(client).answerCallbackQuery("cq-auth-1", "Not authorized", true);
        verifyNoInteractions(sessionStore);
    }

    @Test
    void handle_authorizedUser_proceedsNormally() {
        when(authorizationService.isAuthorized(456L, "jdoe", 123L)).thenReturn(true);
        BotSessionEntity session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
        when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString())).thenReturn(session);
        org.mockito.Mockito.lenient().when(sessionStore.resolveOrCreate(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(session);

        UpdateEvent event = callbackEvent("cq-auth-2", "mp:gpt-4");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("Model set to: gpt-4");
    }

    @Test
    void completedClarifyCallbackSuppressesTheSelectedToast() {
        UpdateEvent event = callbackEvent("cq-clarify", "clfy:prompt-1:0");
        ClarifyInteractionRenderer renderer = mock(ClarifyInteractionRenderer.class);
        CallbackQueryHandler clarifyHandler = new CallbackQueryHandler(client, providerKeyboardBuilder,
            modelKeyboardBuilder, inlineKeyboardBuilder, sessionStore, properties, authorizationService,
            renderer);
        when(renderer.handleCallback(123L, 456L, "prompt-1:0"))
            .thenReturn(new ClarifyInteractionRenderer.CallbackResult("Selected", true));

        String result = clarifyHandler.handle(event);

        assertThat(result).isNull();
        verify(renderer).handleCallback(123L, 456L, "prompt-1:0");
        verify(client).answerCallbackQuery("cq-clarify", null, false);
    }

    @Test
    void pendingClarifyCallbackKeepsOnlyItsEphemeralInstruction() {
        UpdateEvent event = callbackEvent("cq-clarify-other", "clfy:prompt-1:other");
        ClarifyInteractionRenderer renderer = mock(ClarifyInteractionRenderer.class);
        CallbackQueryHandler clarifyHandler = new CallbackQueryHandler(client, providerKeyboardBuilder,
            modelKeyboardBuilder, inlineKeyboardBuilder, sessionStore, properties, authorizationService,
            renderer);
        when(renderer.handleCallback(123L, 456L, "prompt-1:other"))
            .thenReturn(new ClarifyInteractionRenderer.CallbackResult("Type your answer", false));

        assertThat(clarifyHandler.handle(event)).isNull();

        verify(client).answerCallbackQuery("cq-clarify-other", "Type your answer", false);
        verify(client, never()).sendMessage(eq(123L), contains("Type your answer"));
    }

    private UpdateEvent callbackEvent(String callbackQueryId, String callbackData) {
        // Use 17-arg constructor with messageId=456L (same as userId for test simplicity)
        return new UpdateEvent(100L, UpdateEvent.Type.CALLBACK_QUERY, 123L, 456L,
            "jdoe", null, null, null, null,
            callbackQueryId, callbackData, null, false, null, null,
            456L, null, 0L);
    }

    @Test
    void unproducedApprovalCallbackProtocolsCannotResolveCurrentApprovals() {
        assertThat(handler.handle(callbackEvent("old-ea", "ea:once:1"))).isEqualTo("Unknown action: ea");
        assertThat(handler.handle(callbackEvent("old-sc", "sc:once:1"))).isEqualTo("Unknown action: sc");
        verifyNoInteractions(backendClient);
    }
}
