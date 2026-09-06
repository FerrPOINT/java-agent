package com.azhukov.agent.core.ports;

import com.azhukov.agent.persistence.entity.SkillEntity;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port (h12): skill storage slice used by the agent core.
 * Implemented by the JPA {@code SkillRepository}.
 */
public interface SkillStorePort {

    List<SkillEntity> findAll();

    List<SkillEntity> findAllBy();

    List<SkillEntity> findByArchivedFalse();

    List<SkillEntity> findByArchivedFalse(int page, int size);

    List<SkillEntity> findVisibleSkills(String userId);

    Optional<SkillEntity> findByName(String name);

    SkillEntity save(SkillEntity entity);

    List<SkillEntity> saveAll(List<SkillEntity> entities);

    void delete(SkillEntity entity);
}
