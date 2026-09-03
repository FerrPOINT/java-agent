package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BusySessionHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LearnCommandTest {

    @Test
    void nameAndDescription() {
        var cmd = new LearnCommand(mock(BusySessionHandler.class));
        assertThat(cmd.name()).isEqualTo("learn");
        assertThat(cmd.description()).contains("skill");
    }

    @Test
    void withArgs_queuesPromptContainingRequest() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new LearnCommand(handler);
        UpdateEvent event = makeEvent("/opt/dev/project focus on auth");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Learning queued");
        verify(handler).queueMessage(eq(123L), argThat(e ->
            e.type() == Type.TEXT
                && e.text().contains("[/learn]")
                && e.text().contains("/opt/dev/project")
                && e.text().contains("focus on auth")
                && e.text().contains("skill_manage")));
    }

    @Test
    void emptyArgs_defaultsToConversationWorkflow() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new LearnCommand(handler);
        UpdateEvent event = makeEvent("");
        cmd.handle(event, null);
        verify(handler).queueMessage(eq(123L), argThat(e ->
            e.text().contains("workflow we just went through")));
    }

    @Test
    void promptCarriesSourceHygiene() {
        BusySessionHandler handler = mock(BusySessionHandler.class);
        var cmd = new LearnCommand(handler);
        cmd.handle(makeEvent("anything"), null);
        verify(handler).queueMessage(eq(123L), argThat(e ->
            e.text().contains("SOURCE HYGIENE")));
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", null, null, null, null, null, null, null, true, "learn", args);
    }
}
