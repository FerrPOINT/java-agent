package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.commands.impl.NewSessionCommand;
import com.azhukov.agent.bot.commands.impl.StatusCommand;
import com.azhukov.agent.bot.commands.impl.YoloCommand;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CommandRegistryTest {

    @Test
    void register_handlersLookupByName() {
        var registry = new CommandRegistry(List.of(
            new NewSessionCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class)),
            new StatusCommand(new BotProperties(), mock(AgentBackendClient.class)),
            new YoloCommand(mock(BotSessionStore.class))
        ));
        assertThat(registry.has("new")).isTrue();
        assertThat(registry.has("status")).isTrue();
        assertThat(registry.has("yolo")).isTrue();
        assertThat(registry.get("new")).isInstanceOf(NewSessionCommand.class);
    }

    @Test
    void unknownCommand_returnsNull() {
        var registry = new CommandRegistry(List.of(
            new NewSessionCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class))
        ));
        assertThat(registry.get("nonexistent")).isNull();
        assertThat(registry.has("nonexistent")).isFalse();
    }

    @Test
    void helpText_listsAllCommands() {
        var registry = new CommandRegistry(List.of(
            new NewSessionCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class)),
            new StatusCommand(new BotProperties(), mock(AgentBackendClient.class))
        ));
        String help = registry.helpText();
        assertThat(help).contains("/new");
        assertThat(help).contains("Start a new chat session");
        assertThat(help).contains("/status");
        assertThat(help).contains("Show current session status");
    }

    @Test
    void emptyHandlerList_works() {
        var registry = new CommandRegistry(List.of());
        assertThat(registry.all()).isEmpty();
        assertThat(registry.helpText()).contains("Available commands");
    }

    @Test
    void all_returnsImmutableCopy() {
        var registry = new CommandRegistry(List.of(
            new NewSessionCommand(mock(BotSessionStore.class), mock(AgentBackendClient.class))
        ));
        var all = registry.all();
        assertThatThrownBy(() -> all.add(new StatusCommand(new BotProperties(), mock(AgentBackendClient.class))))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}