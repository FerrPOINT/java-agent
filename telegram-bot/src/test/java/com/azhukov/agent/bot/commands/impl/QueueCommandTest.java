package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BusySessionHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QueueCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new QueueCommand(mock(BusySessionHandler.class));
        assertThat(cmd.name()).isEqualTo("queue");
        assertThat(cmd.description()).isEqualTo("Queue a prompt for the next turn");
    }

    @Test
    void emptyArgs_returnsUsage() {
        var cmd = new QueueCommand(mock(BusySessionHandler.class));
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Usage: /queue");
        assertThat(result).contains("queues your message");
    }

    @Test
    void nullArgs_returnsUsage() {
        var cmd = new QueueCommand(mock(BusySessionHandler.class));
        UpdateEvent event = makeEvent(null);
        String result = cmd.handle(event, null);
        assertThat(result).contains("Usage: /queue");
    }

    @Test
    void validArgs_queuesMessage() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new QueueCommand(handler);
        UpdateEvent event = makeEvent("do something");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Queued for next turn");
        assertThat(result).contains("do something");
        verify(handler).queueMessage(eq(123L), any(UpdateEvent.class));
    }

    @Test
    void queuedEventIsTextType() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new QueueCommand(handler);
        UpdateEvent event = makeEvent("hello world");
        cmd.handle(event, null);
        verify(handler).queueMessage(eq(123L), argThat(e -> e.type() == Type.TEXT && "hello world".equals(e.text())));
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/queue " + (args != null ? args : ""), null, null, null, null, null, null, true, "queue", args);
    }
}