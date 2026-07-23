package com.azhukov.agent.core.skill;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
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
