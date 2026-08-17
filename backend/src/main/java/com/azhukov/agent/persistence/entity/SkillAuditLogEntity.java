package com.azhukov.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * h77: Curator audit ledger — records each skill mutation.
 * <p>
 * Each mutation records: skill_name, action (create/update/delete/archive),
 * user_id, timestamp, old_value (JSON), new_value (JSON).
 */
@Entity
@Table(name = "skill_audit_log")
@Data
public class SkillAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_name", nullable = false)
    private String skillName;

    /** Action: "create", "update", "delete", or "archive". */
    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @CreationTimestamp
    @Column(name = "timestamp")
    private Instant timestamp;

    public SkillAuditLogEntity() {}

    public SkillAuditLogEntity(String skillName, String action, String userId, String oldValue, String newValue) {
        this.skillName = skillName;
        this.action = action;
        this.userId = userId;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }
}