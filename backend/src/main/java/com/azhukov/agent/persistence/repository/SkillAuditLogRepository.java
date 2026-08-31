package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SkillAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SkillAuditLogRepository extends JpaRepository<SkillAuditLogEntity, Long> {

    List<SkillAuditLogEntity> findBySkillNameOrderByTimestampDesc(String skillName);

    // ── Multi-user: userId-scoped queries ──

    /** Find audit entries for a skill, scoped to a specific user. */
    List<SkillAuditLogEntity> findBySkillNameAndUserIdOrderByTimestampDesc(String skillName, String userId);

    /** Find all audit entries for a specific user. */
    List<SkillAuditLogEntity> findByUserIdOrderByTimestampDesc(String userId);
}