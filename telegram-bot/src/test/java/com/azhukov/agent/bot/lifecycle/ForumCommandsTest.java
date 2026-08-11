package com.azhukov.agent.bot.lifecycle;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.config.BotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ForumCommandsTest {

    private TelegramClient telegramClient;
    private BotProperties properties;
    private CommandRegistry commandRegistry;

    @BeforeEach
    void setUp() {
        telegramClient = mock(TelegramClient.class);
        properties = new BotProperties();
        properties.setToken("test-token");
        properties.setRegisterCommands(true);
        properties.setMode("polling");
        commandRegistry = mock(CommandRegistry.class);
        // Return a minimal list of commands so buildCommandList works
        when(commandRegistry.all()).thenReturn(List.of(
            new TestCommandHandler("new", "Start new session"),
            new TestCommandHandler("reset", "Full reset"),
            new TestCommandHandler("help", "Show commands")
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forumCommands_registeredWhenAllowedTopicsConfigured() {
        properties.getGroup().getAllowedTopics().add("123456789");
        properties.getGroup().getAllowedTopics().add("987654321");
        when(telegramClient.setMyCommands(any(List.class))).thenReturn(true);
        when(telegramClient.setMyCommandsForChat(anyLong(), any(List.class))).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        // Global registration
        verify(telegramClient).setMyCommands(any());
        // Per-forum registration: 2 calls
        verify(telegramClient, times(2)).setMyCommandsForChat(anyLong(), any(List.class));
        verify(telegramClient).setMyCommandsForChat(eq(123456789L), any(List.class));
        verify(telegramClient).setMyCommandsForChat(eq(987654321L), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forumCommands_skippedWhenNoAllowedTopics() {
        // allowedTopics is empty by default
        when(telegramClient.setMyCommands(any(List.class))).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient).setMyCommands(any());
        verify(telegramClient, never()).setMyCommandsForChat(anyLong(), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void forumCommands_skipsInvalidChatId() {
        properties.getGroup().getAllowedTopics().add("not-a-number");
        properties.getGroup().getAllowedTopics().add("111222333");
        when(telegramClient.setMyCommands(any(List.class))).thenReturn(true);
        when(telegramClient.setMyCommandsForChat(anyLong(), any(List.class))).thenReturn(true);

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        // Only valid chat_id should be registered
        verify(telegramClient, times(1)).setMyCommandsForChat(eq(111222333L), any(List.class));
        verify(telegramClient, never()).setMyCommandsForChat(eq(0L), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildCommandList_generatesFromRegistry() {
        when(telegramClient.setMyCommands(any(List.class))).thenAnswer(invocation -> {
            List<Map<String, String>> cmds = invocation.getArgument(0);
            // Verify the commands were generated from the registry
            assertThat(cmds).hasSize(3);
            assertThat(cmds.get(0).get("command")).isEqualTo("new");
            assertThat(cmds.get(0).get("description")).isEqualTo("Start new session");
            return true;
        });

        new BotLifecycleManager(telegramClient, properties, commandRegistry).onStartup();

        verify(telegramClient).setMyCommands(any());
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