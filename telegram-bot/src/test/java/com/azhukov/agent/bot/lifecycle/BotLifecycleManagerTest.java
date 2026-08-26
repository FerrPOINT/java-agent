package com.azhukov.agent.bot.lifecycle;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BotLifecycleManager} covering lifecycle start/stop,
 * command registration, webhook setup, and edge cases.
 * <p>
 * Note: {@code ForumCommandsTest} already covers forum-specific command
 * registration scenarios; these tests focus on the remaining branches:
 * empty token skip, polling mode, webhook mode (success/fail/missing
 * config), command registration failure, and registerCommands=false.
 */
class BotLifecycleManagerTest {

    private TelegramClient telegramClient;
    private BotProperties properties;
    private CommandRegistry commandRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        properties = new BotProperties();
        properties.setToken("test-token");
        properties.setRegisterCommands(true);
        properties.setMode("polling");
        commandRegistry = mock(CommandRegistry.class);
        when(commandRegistry.all()).thenReturn(List.of(
            new TestCommandHandler("new", "Start new session"),
            new TestCommandHandler("help", "Show commands")
        ));
        // Default: global command registration succeeds
        when(telegramClient.setMyCommands(any(List.class))).thenReturn(true);
    }

    // ─── Lifecycle: empty/blank token ────────────────────────────────────

    @Test
    void onStartup_blankToken_skipsAllSetup() {
        properties.setToken("");

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).setMyCommands(any());
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    @Test
    void onStartup_nullToken_skipsAllSetup() {
        properties.setToken(null);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).setMyCommands(any());
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    // ─── Lifecycle: polling mode ─────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_pollingMode_registersCommandsButNoWebhook() {
        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient).setMyCommands(any(List.class));
        verify(telegramClient, never()).deleteWebhook();
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    // ─── Lifecycle: registerCommands disabled ────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_registerCommandsFalse_skipsCommandRegistration() {
        properties.setRegisterCommands(false);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).setMyCommands(any());
        // No webhook setup in polling mode
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_registerCommandsFalse_webhookModeStillSetsWebhook() {
        properties.setRegisterCommands(false);
        properties.setMode("webhook");
        properties.getWebhook().setUrl("https://example.com/webhook");
        properties.getWebhook().setSecret("super-secret");
        when(telegramClient.setWebhook(any(), any())).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).setMyCommands(any());
        verify(telegramClient).deleteWebhook();
        verify(telegramClient).setWebhook("https://example.com/webhook", "super-secret");
    }

    // ─── Lifecycle: command registration failure ─────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_commandRegistrationFails_doesNotThrow() {
        when(telegramClient.setMyCommands(any(List.class))).thenReturn(false);

        // Should not throw
        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient).setMyCommands(any(List.class));
    }

    // ─── Lifecycle: webhook mode ─────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_webhookMode_setsWebhookWhenUrlAndSecretConfigured() {
        properties.setMode("webhook");
        properties.getWebhook().setUrl("https://example.com/webhook");
        properties.getWebhook().setSecret("my-secret");
        when(telegramClient.setWebhook(any(), any())).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        // Commands are still registered (registerCommands=true)
        verify(telegramClient).setMyCommands(any(List.class));
        // Webhook flow: delete stale first, then set
        verify(telegramClient).deleteWebhook();
        verify(telegramClient).setWebhook("https://example.com/webhook", "my-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_webhookMode_setWebhookFails_doesNotThrow() {
        properties.setMode("webhook");
        properties.getWebhook().setUrl("https://example.com/webhook");
        properties.getWebhook().setSecret("my-secret");
        when(telegramClient.setWebhook(any(), any())).thenReturn(false);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient).deleteWebhook();
        verify(telegramClient).setWebhook("https://example.com/webhook", "my-secret");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_webhookMode_emptyUrl_skipsWebhookSetup() {
        properties.setMode("webhook");
        properties.getWebhook().setUrl("");
        properties.getWebhook().setSecret("my-secret");

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        // Commands still registered
        verify(telegramClient).setMyCommands(any(List.class));
        // No webhook calls
        verify(telegramClient, never()).deleteWebhook();
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_webhookMode_nullUrl_skipsWebhookSetup() {
        properties.setMode("webhook");
        properties.getWebhook().setUrl(null);
        properties.getWebhook().setSecret("my-secret");

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).deleteWebhook();
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_webhookMode_emptySecret_skipsWebhookSetup() {
        properties.setMode("webhook");
        properties.getWebhook().setUrl("https://example.com/webhook");
        properties.getWebhook().setSecret("");

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        // Fail-closed: no webhook setup without secret
        verify(telegramClient, never()).deleteWebhook();
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_webhookMode_nullSecret_skipsWebhookSetup() {
        properties.setMode("webhook");
        properties.getWebhook().setUrl("https://example.com/webhook");
        properties.getWebhook().setSecret(null);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).deleteWebhook();
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    // ─── Edge case: blank (whitespace-only) token ────────────────────────

    @Test
    void onStartup_whitespaceToken_skipsAllSetup() {
        properties.setToken("   ");

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).setMyCommands(any());
    }

    // ─── Edge case: blank (whitespace-only) webhook url/secret ───────────

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_webhookMode_whitespaceUrl_skipsWebhookSetup() {
        properties.setMode("webhook");
        properties.getWebhook().setUrl("   ");
        properties.getWebhook().setSecret("my-secret");

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).deleteWebhook();
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_webhookMode_whitespaceSecret_skipsWebhookSetup() {
        properties.setMode("webhook");
        properties.getWebhook().setUrl("https://example.com/webhook");
        properties.getWebhook().setSecret("   ");

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient, never()).deleteWebhook();
        verify(telegramClient, never()).setWebhook(any(), any());
    }

    // ─── Edge case: empty command registry ───────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_emptyCommandRegistry_stillRegistersZeroCommands() {
        when(commandRegistry.all()).thenReturn(List.of());

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient).setMyCommands(any(List.class));
    }

    // ─── Edge case: forum registration all invalid chat IDs ──────────────

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_allForumChatIdsInvalid_noForumRegistrations() {
        properties.getGroup().getAllowedTopics().add("abc");
        properties.getGroup().getAllowedTopics().add("def");
        when(telegramClient.setMyCommandsForChat(anyLong(), any(List.class))).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        // Global registration still happens
        verify(telegramClient).setMyCommands(any(List.class));
        // No forum chat registrations
        verify(telegramClient, never()).setMyCommandsForChat(anyLong(), any(List.class));
    }

    // ─── Edge case: forum registration partial failure ───────────────────

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_forumRegistrationPartialFailure_continuesWithOthers() {
        properties.getGroup().getAllowedTopics().add("111");
        properties.getGroup().getAllowedTopics().add("222");
        when(telegramClient.setMyCommandsForChat(eq(111L), any(List.class))).thenReturn(false);
        when(telegramClient.setMyCommandsForChat(eq(222L), any(List.class))).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient).setMyCommandsForChat(eq(111L), any(List.class));
        verify(telegramClient).setMyCommandsForChat(eq(222L), any(List.class));
    }

    /** Simple test command handler. */
    private record TestCommandHandler(String name, String description) implements CommandHandler {
        @Override
        public String name() { return name; }

        @Override
        public String description() { return description; }

        @Override
        public String handle(com.azhukov.agent.bot.polling.UpdateEvent event,
                             com.azhukov.agent.bot.session.BotSessionEntity session) {
            return "";
        }
    }
}