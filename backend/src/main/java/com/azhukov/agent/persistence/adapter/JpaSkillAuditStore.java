package com.azhukov.agent.persistence.adapter;

import com.azhukov.agent.core.ports.SkillAuditPort;
import com.azhukov.agent.persistence.entity.SkillAuditLogEntity;
import com.azhukov.agent.persistence.repository.SkillAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * h12: JPA adapter implementing the core skill-audit port.
 */
@Repository
@RequiredArgsConstructor
public class JpaSkillAuditStore implements SkillAuditPort {

    private final SkillAuditLogRepository skillAuditLogRepository;

    @Override
    public SkillAuditLogEntity save(SkillAuditLogEntity entity) {
        return skillAuditLogRepository.save(entity);
    }
}
