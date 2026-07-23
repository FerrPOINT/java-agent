package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;

import java.time.Instant;
import java.util.List;

public class DatabaseSkillManager implements SkillManager {

    private final SkillRepository skillRepository;

    public DatabaseSkillManager(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @Override
    public List<String> listSkillNames() {
        return skillRepository.findAll().stream()
            .map(SkillEntity::getName)
            .toList();
    }

    @Override
    public String getSkill(String name) {
        return skillRepository.findByName(name)
            .map(SkillEntity::getContent)
            .orElse(null);
    }

    @Override
    public void saveSkill(String name, String content) {
        SkillEntity e = skillRepository.findByName(name).orElse(new SkillEntity());
        e.setName(name);
        e.setContent(content);
        e.setUpdatedAt(Instant.now());
        if (e.getCreatedAt() == null) {
            e.setCreatedAt(Instant.now());
        }
        skillRepository.save(e);
    }

    @Override
    public boolean deleteSkill(String name) {
        return skillRepository.findByName(name).map(e -> {
            skillRepository.delete(e);
            return true;
        }).orElse(false);
    }
}
