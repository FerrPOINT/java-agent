package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.session.BotSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoiceCommandTest {

    @Mock
    private BotSessionStore sessionStore;

    private VoiceCommand cmd;
    private BotSessionEntity session;

    @BeforeEach
    void setUp() {
        cmd = new VoiceCommand(sessionStore);
        session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("voice");
        assertThat(cmd.description()).contains("Voice mode");
    }

    @Test
    void handleOn_enablesVoiceMode() {
        UpdateEvent event = makeEvent("on");
        String result = cmd.handle(event, session);
        assertThat(result).contains("Voice mode enabled");
        verify(sessionStore).setVoiceMode(session.getId(), true);
    }

    @Test
    void handleOff_disablesVoiceMode() {
        UpdateEvent event = makeEvent("off");
        String result = cmd.handle(event, session);
        assertThat(result).contains("Voice mode disabled");
        verify(sessionStore).setVoiceMode(session.getId(), false);
    }

    @Test
    void handleStatus_returnsCurrentState() {
        session.setVoiceMode(false);
        UpdateEvent event = makeEvent("status");
        String result = cmd.handle(event, session);
        assertThat(result).contains("Voice mode is currently OFF");
        verifyNoInteractions(sessionStore);
    }

    @Test
    void handleStatus_on_returnsOnMessage() {
        session.setVoiceMode(true);
        UpdateEvent event = makeEvent("status");
        String result = cmd.handle(event, session);
        assertThat(result).contains("Voice mode is currently ON");
    }

    @Test
    void handleNoArgs_defaultsToStatus() {
        session.setVoiceMode(false);
        UpdateEvent event = makeEvent("");
        String result = cmd.handle(event, session);
        assertThat(result).contains("Voice mode is currently OFF");
    }

    @Test
    void handleInvalidArgs_returnsUsage() {
        UpdateEvent event = makeEvent("invalid");
        String result = cmd.handle(event, session);
        assertThat(result).contains("Usage: /voice");
    }

    @Test
    void handleNullSession_returnsError() {
        UpdateEvent event = makeEvent("on");
        String result = cmd.handle(event, null);
        assertThat(result).contains("Session not found");
    }

    private UpdateEvent makeEvent(String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", "/voice " + args, null, null, null,
            null, null, null, true, "voice", args);
    }
}