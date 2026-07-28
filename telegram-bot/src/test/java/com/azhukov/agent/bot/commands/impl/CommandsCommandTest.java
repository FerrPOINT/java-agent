package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.commands.CommandRegistry;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommandsCommandTest {

    @Test
    @SuppressWarnings("unchecked")
    void listsAllCommands() {
        CommandHandler handler1 = mock(CommandHandler.class);
        when(handler1.name()).thenReturn("foo");
        when(handler1.description()).thenReturn("Foo command");
        CommandHandler handler2 = mock(CommandHandler.class);
        when(handler2.name()).thenReturn("bar");
        when(handler2.description()).thenReturn("Bar command");
        CommandRegistry registry = mock(CommandRegistry.class);
        when(registry.all()).thenReturn(List.of(handler1, handler2));
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        var cmd = new CommandsCommand(provider);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("All commands (2)");
        assertThat(result).contains("/foo — Foo command");
        assertThat(result).contains("/bar — Bar command");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noRegistry_returnsNoCommands() {
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        var cmd = new CommandsCommand(provider);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("No commands available");
    }

    @Test
    void nameAndDescription() {
        @SuppressWarnings("unchecked")
        var cmd = new CommandsCommand(mock(ObjectProvider.class));
        assertThat(cmd.name()).isEqualTo("commands");
        assertThat(cmd.description()).isEqualTo("List all available commands");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/commands " + args, null, null, null, null, null, null, true, "commands", args);
    }
}