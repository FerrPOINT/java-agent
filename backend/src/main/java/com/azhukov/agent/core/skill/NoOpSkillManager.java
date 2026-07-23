package com.azhukov.agent.core.skill;

import java.util.List;

public class NoOpSkillManager implements SkillManager {

    @Override
    public List<String> listSkillNames() {
        return List.of();
    }

    @Override
    public String getSkill(String name) {
        return null;
    }
}
