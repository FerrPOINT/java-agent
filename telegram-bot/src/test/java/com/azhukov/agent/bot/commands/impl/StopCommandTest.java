package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BusySessionHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StopCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new StopCommand(mock(BusySessionHandler.class));
        assertThat(cmd.name()).isEqualTo("stop");
        assertThat(cmd.description()).isEqualTo("Stop the current generation");
    }

    @Test
    void handleInterruptsAndReturnsMessage() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new StopCommand(handler);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        verify(handler).interrupt(123L);
        assertThat(result).isEqualTo("Stopping current generation...");
    }

    @Test
    void handleWithArgsStillInterrupts() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new StopCommand(handler);
        UpdateEvent event = makeEvent("ignored args");
        String result = cmd.handle(event, null);
        verify(handler).interrupt(123L);
        assertThat(result).contains("Stopping");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/stop " + args, null, null, null, null, null, null, true, "stop", args);
    }
}