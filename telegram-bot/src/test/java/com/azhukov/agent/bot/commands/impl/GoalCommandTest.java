package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.azhukov.agent.bot.core.AgentBackendClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoalCommandTest {

    private GoalCommand cmd;
    private GoalCommand goalCmd;
    private BotSessionEntity session;
    private AgentBackendClient backendClient;

    @BeforeEach
    void setUp() {
        backendClient = mock(AgentBackendClient.class);
        cmd = new GoalCommand(backendClient);
        goalCmd = new GoalCommand(backendClient);
        session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("goal");
        assertThat(cmd.description()).isEqualTo("Set or manage a standing goal");
    }

    @Test
    void noGoalShowsUsage() {
        String result = cmd.handle(textEvent("/goal", null), session);
        assertThat(result).contains("No standing goal");
        assertThat(result).contains("Usage:");
    }

    @Test
    void setGoal() {
        String result = cmd.handle(textEvent("/goal", "Fix all tests"), session);
        assertThat(result).contains("Fix all tests");
        assertThat(result).contains("Goal set");
    }

    @Test
    void statusShowsGoal() {
        cmd.handle(textEvent("/goal", "Fix all tests"), session);
        String result = cmd.handle(textEvent("/goal", "status"), session);
        assertThat(result).contains("Fix all tests");
        assertThat(result).contains("active");
    }

    @Test
    void pauseAndResume() {
        cmd.handle(textEvent("/goal", "Fix all tests"), session);
        cmd.handle(textEvent("/goal", "pause"), session);
        String result = cmd.handle(textEvent("/goal", "status"), session);
        assertThat(result).contains("paused");

        cmd.handle(textEvent("/goal", "resume"), session);
        result = cmd.handle(textEvent("/goal", "status"), session);
        assertThat(result).contains("active");
    }

    @Test
    void clearRemovesGoal() {
        cmd.handle(textEvent("/goal", "Fix all tests"), session);
        String result = cmd.handle(textEvent("/goal", "clear"), session);
        assertThat(result).contains("cleared");

        result = cmd.handle(textEvent("/goal", "status"), session);
        assertThat(result).contains("No standing goal");
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, "goal", args != null ? args : "");
    }
}