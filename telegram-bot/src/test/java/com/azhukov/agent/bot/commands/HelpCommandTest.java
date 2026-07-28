package com.azhukov.agent.bot.commands;

import com.azhukov.agent.bot.commands.impl.HelpCommand;
import com.azhukov.agent.bot.commands.impl.NewSessionCommand;
import com.azhukov.agent.bot.commands.impl.StatusCommand;
import com.azhukov.agent.bot.commands.impl.YoloCommand;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HelpCommandTest {

    @Test
    void helpText_containsAllCommands() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        HelpCommand helpCommand = new HelpCommand(provider);

        CommandRegistry registry = new CommandRegistry(java.util.List.of(
            helpCommand,
            new NewSessionCommand(),
            new StatusCommand(),
            new YoloCommand()
        ));
        when(provider.getIfAvailable()).thenReturn(registry);

        UpdateEvent event = new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/help", null, null, null, null, null, null, true, "help", "");

        String result = helpCommand.handle(event, null);
        assertThat(result).contains("/new");
        assertThat(result).contains("/status");
        assertThat(result).contains("/yolo");
        assertThat(result).contains("/help");
    }

    @Test
    void noRegistry_returnsFallback() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        HelpCommand helpCommand = new HelpCommand(provider);
        UpdateEvent event = new UpdateEvent(1, Type.COMMAND, 123, 456, "user",
            "/help", null, null, null, null, null, null, true, "help", "");

        String result = helpCommand.handle(event, null);
        assertThat(result).isEqualTo("No help available.");
    }

    @Test
    void nameAndDescription_correct() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CommandRegistry> provider = mock(ObjectProvider.class);
        HelpCommand helpCommand = new HelpCommand(provider);
        assertThat(helpCommand.name()).isEqualTo("help");
        assertThat(helpCommand.description()).isNotBlank();
    }
}