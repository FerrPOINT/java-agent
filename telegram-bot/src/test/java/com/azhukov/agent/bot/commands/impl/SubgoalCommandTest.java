package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubgoalCommandTest {

    private SubgoalCommand cmd;
    private GoalCommand goalCmd;
    private BotSessionEntity session;

    @BeforeEach
    void setUp() {
        cmd = new SubgoalCommand();
        goalCmd = new GoalCommand();
        session = new BotSessionEntity();
        session.setId(UUID.randomUUID());
    }

    @Test
    void nameAndDescription() {
        assertThat(cmd.name()).isEqualTo("subgoal");
        assertThat(cmd.description()).isEqualTo("Add criteria to the active goal");
    }

    @Test
    void noGoalReturnsError() {
        String result = cmd.handle(textEvent("/subgoal", "test criterion"), session);
        assertThat(result).contains("No active goal");
    }

    @Test
    void addSubgoal() {
        goalCmd.handle(textEvent("/goal", "Fix tests"), session);
        String result = cmd.handle(textEvent("/subgoal", "all pass"), session);
        assertThat(result).contains("Subgoal added");
        assertThat(result).contains("all pass");
    }

    @Test
    void listSubgoals() {
        goalCmd.handle(textEvent("/goal", "Fix tests"), session);
        cmd.handle(textEvent("/subgoal", "criterion 1"), session);
        cmd.handle(textEvent("/subgoal", "criterion 2"), session);

        String result = cmd.handle(textEvent("/subgoal", null), session);
        assertThat(result).contains("1. criterion 1");
        assertThat(result).contains("2. criterion 2");
    }

    @Test
    void removeSubgoal() {
        goalCmd.handle(textEvent("/goal", "Fix tests"), session);
        cmd.handle(textEvent("/subgoal", "criterion 1"), session);
        cmd.handle(textEvent("/subgoal", "criterion 2"), session);

        String result = cmd.handle(textEvent("/subgoal", "remove 1"), session);
        assertThat(result).contains("removed");

        String list = cmd.handle(textEvent("/subgoal", null), session);
        assertThat(list).contains("criterion 2");
        assertThat(list).doesNotContain("criterion 1");
    }

    @Test
    void clearSubgoals() {
        goalCmd.handle(textEvent("/goal", "Fix tests"), session);
        cmd.handle(textEvent("/subgoal", "criterion 1"), session);
        cmd.handle(textEvent("/subgoal", "criterion 2"), session);

        String result = cmd.handle(textEvent("/subgoal", "clear"), session);
        assertThat(result).contains("cleared");

        String list = cmd.handle(textEvent("/subgoal", null), session);
        assertThat(list).contains("No subgoals");
    }

    private UpdateEvent textEvent(String text, String args) {
        return new UpdateEvent(1, Type.COMMAND, 123, 456, "user", text, null, null, null, null, null, null, true, text.substring(1), args != null ? args : "");
    }
}