package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReloadSkillsCommandTest {

    @Test
    void handle_callsBackendReloadSkills() {
        AgentBackendClient client = mock(AgentBackendClient.class);
        when(client.reloadSkills()).thenReturn("Skills reloaded.");

        var cmd = new ReloadSkillsCommand(client);
        UpdateEvent event = makeEvent("");

        String result = cmd.handle(event, null);

        assertThat(result).isEqualTo("Skills reloaded.");
        verify(client).reloadSkills();
    }

    @Test
    void nameAndDescription() {
        var cmd = new ReloadSkillsCommand(mock(AgentBackendClient.class));
        assertThat(cmd.name()).isEqualTo("reload_skills");
        assertThat(cmd.description()).isNotBlank();
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/reload_skills " + args,
            null, null, null, null, null, null, true, "reload_skills", args);
    }
}