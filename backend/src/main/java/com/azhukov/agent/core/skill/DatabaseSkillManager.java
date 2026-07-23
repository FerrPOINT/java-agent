package com.azhukov.agent.core.skill;

import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
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
}
