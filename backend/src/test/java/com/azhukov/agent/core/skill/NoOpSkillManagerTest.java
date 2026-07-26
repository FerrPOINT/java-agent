package com.azhukov.agent.core.skill;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpSkillManagerTest {

    @Test
    void returnsEmptyDefaults() {
        NoOpSkillManager m = new NoOpSkillManager();
        assertThat(m.listSkillNames()).isEmpty();
        assertThat(m.getSkill("x")).isNull();
        assertThat(m.deleteSkill("x")).isFalse();
    }

    @Test
    void saveDoesNothing() {
        new NoOpSkillManager().saveSkill("x", "c");
    }
}
