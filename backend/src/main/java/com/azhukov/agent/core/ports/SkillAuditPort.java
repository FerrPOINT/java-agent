package com.azhukov.agent.core.ports;

import com.azhukov.agent.persistence.entity.SkillAuditLogEntity;

/**
 * Persistence port (h12): skill mutation audit slice.
 * Implemented by the JPA {@code SkillAuditLogRepository}.
 */
public interface SkillAuditPort {

    SkillAuditLogEntity save(SkillAuditLogEntity entity);
}
