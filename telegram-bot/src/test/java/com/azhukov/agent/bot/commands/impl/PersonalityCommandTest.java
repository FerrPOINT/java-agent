package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalityCommandTest {

    private BotProperties properties;
    private PersonalityCommand cmd;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        cmd = new PersonalityCommand(org.mockito.Mockito.mock(com.azhukov.agent.bot.session.BotSessionStore.class), properties);
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("personality");
        assertThat(cmd.description()).isEqualTo("Set or show agent personality");
    }

    @Test
    void showsCurrentPersonality() {
        properties.setAgentName("TestBot");

        String result = cmd.handle(textEvent("/personality", null), null);

        assertThat(result).contains("TestBot");
        assertThat(result).contains("Usage:");
    }

    @Test
    void setsPersonality() {
        String result = cmd.handle(textEvent("/personality", "CoolBot"), null);

        assertThat(result).contains("CoolBot");
        assertThat(properties.getAgentName()).isEqualTo("CoolBot");
    }

    @Test
    void resetsPersonality() {
        properties.setAgentName("CustomName");

        String result = cmd.handle(textEvent("/personality", "reset"), null);

        assertThat(properties.getAgentName()).isEqualTo("Джава агент");
        assertThat(result).contains("reset");
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "personality", args != null ? args : "");
    }
}