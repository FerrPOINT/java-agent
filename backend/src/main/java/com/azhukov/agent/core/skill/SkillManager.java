package com.azhukov.agent.core.skill;

import java.util.List;

public interface SkillManager {

    List<String> listSkillNames();

    String getSkill(String name);
}
