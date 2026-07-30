package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkillRepository extends JpaRepository<SkillEntity, UUID> {

    Optional<SkillEntity> findByName(String name);

    // S2: Curator — find non-archived skills
    List<SkillEntity> findByArchivedFalse();

    // S2: Curator — find all skills (including archived)
    List<SkillEntity> findAllBy();

    // S7: Find skills not accessed since a given time (stale detection)
    @Query("SELECT s FROM SkillEntity s WHERE s.archived = false AND (s.lastActivityAt IS NULL OR s.lastActivityAt < :before)")
    List<SkillEntity> findStaleSkills(Instant before);
}