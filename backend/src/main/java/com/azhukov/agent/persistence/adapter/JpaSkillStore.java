package com.azhukov.agent.persistence.adapter;

import com.azhukov.agent.core.ports.SkillStorePort;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * h12: JPA adapter implementing the core skill-store port.
 */
@Repository
@RequiredArgsConstructor
public class JpaSkillStore implements SkillStorePort {

    private final SkillRepository skillRepository;

    @Override
    public List<SkillEntity> findAll() {
        return skillRepository.findAll();
    }

    @Override
    public List<SkillEntity> findAllBy() {
        return skillRepository.findAllBy();
    }

    @Override
    public List<SkillEntity> findByArchivedFalse() {
        return skillRepository.findByArchivedFalse();
    }

    @Override
    public List<SkillEntity> findByArchivedFalse(int page, int size) {
        return skillRepository.findByArchivedFalse(org.springframework.data.domain.PageRequest.of(page, size)).getContent();
    }

    @Override
    public List<SkillEntity> findVisibleSkills(String userId) {
        return skillRepository.findVisibleSkills(userId);
    }

    @Override
    public Optional<SkillEntity> findByName(String name) {
        return skillRepository.findByName(name);
    }

    @Override
    public SkillEntity save(SkillEntity entity) {
        return skillRepository.save(entity);
    }

    @Override
    public List<SkillEntity> saveAll(List<SkillEntity> entities) {
        return skillRepository.saveAll(entities);
    }

    @Override
    public void delete(SkillEntity entity) {
        skillRepository.delete(entity);
    }
}
