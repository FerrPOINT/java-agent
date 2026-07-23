package com.azhukov.agent.core.skill;

import java.util.List;

public interface SkillManager {

    List<String> listSkillNames();

    String getSkill(String name);

    void saveSkill(String name, String content);

    boolean deleteSkill(String name);
}
