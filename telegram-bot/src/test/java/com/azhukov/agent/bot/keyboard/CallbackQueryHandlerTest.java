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
    private ApprovalStateStore approvalStateStore;
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
        approvalStateStore = new ApprovalStateStore();
        // By default, allow all users (allow-by-default = true)
        when(authorizationService.isAuthorized(anyLong(), anyString(), anyLong())).thenReturn(true);
        handler = new CallbackQueryHandler(client, providerKeyboardBuilder, modelKeyboardBuilder,
            inlineKeyboardBuilder, sessionStore, properties, authorizationService,
            backendClient, approvalStateStore);

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

        UpdateEvent event = callbackEvent("cq-auth-2", "mp:gpt-4");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("Model set to: gpt-4");
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private UpdateEvent callbackEvent(String callbackQueryId, String callbackData) {
        // Use 17-arg constructor with messageId=456L (same as userId for test simplicity)
        return new UpdateEvent(100L, UpdateEvent.Type.CALLBACK_QUERY, 123L, 456L,
            "jdoe", null, null, null, null,
            callbackQueryId, callbackData, null, false, null, null,
            456L, null, 0L);
    }

    // ─── P1-12: Exec approval (ea:) callback tests ──────────────────

    @Test
    void handle_eaOnce_approvesAndReturnsLabel() {
        int id = approvalStateStore.registerExecApproval("session-abc");
        when(backendClient.resolveApproval("session-abc", "once")).thenReturn("Approved");

        UpdateEvent event = callbackEvent("cq-ea-1", "ea:once:" + id);
        String result = handler.handle(event);

        assertThat(result).isEqualTo("✅ Approved once");
        verify(backendClient).resolveApproval("session-abc", "once");
        verify(client).editMessageReplyMarkup(eq(123L), eq(456L), eq(null));
    }

    @Test
    void handle_eaDeny_deniesAndReturnsLabel() {
        int id = approvalStateStore.registerExecApproval("session-xyz");
        when(backendClient.resolveApproval("session-xyz", "deny")).thenReturn("Denied");

        UpdateEvent event = callbackEvent("cq-ea-2", "ea:deny:" + id);
        String result = handler.handle(event);

        assertThat(result).isEqualTo("❌ Denied");
        verify(backendClient).resolveApproval("session-xyz", "deny");
        verify(client).editMessageReplyMarkup(eq(123L), eq(456L), eq(null));
    }

    @Test
    void handle_eaAlreadyResolved_returnsAlreadyResolvedMessage() {
        // Register and consume the approval
        int id = approvalStateStore.registerExecApproval("session-gone");
        approvalStateStore.popExecApproval(id);

        UpdateEvent event = callbackEvent("cq-ea-3", "ea:once:" + id);
        String result = handler.handle(event);

        assertThat(result).isEqualTo("This approval has already been resolved.");
        verifyNoInteractions(backendClient);
        // Should not edit message reply markup for already-resolved
        verify(client, never()).editMessageReplyMarkup(anyLong(), anyLong(), any());
    }

    @Test
    void handle_eaInvalidId_returnsInvalidMessage() {
        UpdateEvent event = callbackEvent("cq-ea-4", "ea:once:notanumber");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("Invalid approval ID");
        verifyNoInteractions(backendClient);
        verify(client, never()).editMessageReplyMarkup(anyLong(), anyLong(), any());
    }

    @Test
    void handle_eaMissingColon_returnsInvalidMessage() {
        UpdateEvent event = callbackEvent("cq-ea-5", "ea:once");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("Invalid approval data");
        verifyNoInteractions(backendClient);
        verify(client, never()).editMessageReplyMarkup(anyLong(), anyLong(), any());
    }

    @Test
    void handle_eaSession_approvesForSession() {
        int id = approvalStateStore.registerExecApproval("session-789");
        when(backendClient.resolveApproval("session-789", "session")).thenReturn("Approved for session");

        UpdateEvent event = callbackEvent("cq-ea-6", "ea:session:" + id);
        String result = handler.handle(event);

        assertThat(result).isEqualTo("✅ Approved for session");
        verify(backendClient).resolveApproval("session-789", "session");
        verify(client).editMessageReplyMarkup(eq(123L), eq(456L), eq(null));
    }

    @Test
    void handle_eaAlways_approvesPermanently() {
        int id = approvalStateStore.registerExecApproval("session-perm");
        when(backendClient.resolveApproval("session-perm", "always")).thenReturn("Approved always");

        UpdateEvent event = callbackEvent("cq-ea-7", "ea:always:" + id);
        String result = handler.handle(event);

        assertThat(result).isEqualTo("✅ Approved permanently");
        verify(backendClient).resolveApproval("session-perm", "always");
        verify(client).editMessageReplyMarkup(eq(123L), eq(456L), eq(null));
    }

    // ─── P1-13: Slash-confirm (sc:) callback tests ──────────────────

    @Test
    void handle_scOnce_approvesAndReturnsLabel() {
        approvalStateStore.registerSlashConfirm("confirm-1", "session-sc-1");
        when(backendClient.resolveSlashConfirm("session-sc-1", "confirm-1", "once")).thenReturn("Approved");

        UpdateEvent event = callbackEvent("cq-sc-1", "sc:once:confirm-1");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("✅ Approved once");
        verify(backendClient).resolveSlashConfirm("session-sc-1", "confirm-1", "once");
    }

    @Test
    void handle_scAlways_approvesAndReturnsLabel() {
        approvalStateStore.registerSlashConfirm("confirm-2", "session-sc-2");
        when(backendClient.resolveSlashConfirm("session-sc-2", "confirm-2", "always")).thenReturn("Approved always");

        UpdateEvent event = callbackEvent("cq-sc-2", "sc:always:confirm-2");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("🔒 Always approve");
        verify(backendClient).resolveSlashConfirm("session-sc-2", "confirm-2", "always");
    }

    @Test
    void handle_scCancel_deniesAndReturnsLabel() {
        approvalStateStore.registerSlashConfirm("confirm-3", "session-sc-3");
        when(backendClient.resolveSlashConfirm("session-sc-3", "confirm-3", "cancel")).thenReturn("Denied");

        UpdateEvent event = callbackEvent("cq-sc-3", "sc:cancel:confirm-3");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("❌ Cancelled");
        verify(backendClient).resolveSlashConfirm("session-sc-3", "confirm-3", "cancel");
    }

    @Test
    void handle_scAlreadyResolved_returnsAlreadyResolvedMessage() {
        approvalStateStore.registerSlashConfirm("confirm-gone", "session-gone");
        approvalStateStore.popSlashConfirm("confirm-gone");

        UpdateEvent event = callbackEvent("cq-sc-4", "sc:once:confirm-gone");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("This prompt has already been resolved.");
        verifyNoInteractions(backendClient);
    }

    @Test
    void handle_scMissingColon_returnsInvalidMessage() {
        UpdateEvent event = callbackEvent("cq-sc-5", "sc:once");
        String result = handler.handle(event);

        assertThat(result).isEqualTo("Invalid confirm data");
        verifyNoInteractions(backendClient);
    }
}