package com.azhukov.agent.core.skill;

import java.time.Instant;
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

    @Override
    public void saveSkill(String name, String content) {
    }

    @Override
    public boolean deleteSkill(String name) {
        return false;
    }

    @Override
    public void saveSkill(String name, String content, WriteOrigin origin) {
    }

    @Override
    public void incrementViewCount(String name) {}

    @Override
    public void incrementManageCount(String name) {}

    @Override
    public List<SkillInfo> listSkills() {
        return List.of();
    }

    @Override
    public SkillInfo getSkillInfo(String name) {
        return null;
    }

    @Override
    public boolean patchSkill(String name, String oldText, String newText) {
        return false;
    }

    @Override
    public void writeSupportFile(String skillName, String filePath, String content) {}

    @Override
    public boolean removeSupportFile(String skillName, String filePath) {
        return false;
    }

    @Override
    public String readSupportFile(String skillName, String filePath) {
        return null;
    }

    @Override
    public List<String> listSupportFiles(String skillName) {
        return List.of();
    }

    @Override
    public boolean archiveSkill(String name) {
        return false;
    }

    @Override
    public boolean unarchiveSkill(String name) {
        return false;
    }
}