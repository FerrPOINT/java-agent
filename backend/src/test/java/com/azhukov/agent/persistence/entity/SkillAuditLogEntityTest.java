package com.azhukov.agent.persistence.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * h77: Tests for SkillAuditLogEntity.
 */
class SkillAuditLogEntityTest {

    @Test
    void constructor_setsAllFields() {
        var entity = SkillAuditLogEntity.create("my-skill", "update", "user-123",
            "{\"version\":1}", "{\"version\":2}");

        assertThat(entity.getSkillName()).isEqualTo("my-skill");
        assertThat(entity.getAction()).isEqualTo("update");
        assertThat(entity.getUserId()).isEqualTo("user-123");
        assertThat(entity.getOldValue()).isEqualTo("{\"version\":1}");
        assertThat(entity.getNewValue()).isEqualTo("{\"version\":2}");
    }

    @Test
    void constructor_createAction() {
        var entity = SkillAuditLogEntity.create("new-skill", "create", "user-1",
            null, "{\"content\":\"new skill\"}");

        assertThat(entity.getAction()).isEqualTo("create");
        assertThat(entity.getOldValue()).isNull();
        assertThat(entity.getNewValue()).isEqualTo("{\"content\":\"new skill\"}");
    }

    @Test
    void constructor_deleteAction() {
        var entity = SkillAuditLogEntity.create("old-skill", "delete", "user-2",
            "{\"content\":\"old skill\"}", null);

        assertThat(entity.getAction()).isEqualTo("delete");
        assertThat(entity.getOldValue()).isEqualTo("{\"content\":\"old skill\"}");
        assertThat(entity.getNewValue()).isNull();
    }

    @Test
    void constructor_archiveAction() {
        var entity = SkillAuditLogEntity.create("stale-skill", "archive", "system",
            "{\"status\":\"active\"}", "{\"status\":\"archived\"}");

        assertThat(entity.getAction()).isEqualTo("archive");
        assertThat(entity.getOldValue()).contains("active");
        assertThat(entity.getNewValue()).contains("archived");
    }

    @Test
    void defaultConstructor_createsEmptyEntity() {
        var entity = new SkillAuditLogEntity();
        assertThat(entity.getId()).isNull();
        assertThat(entity.getSkillName()).isNull();
        assertThat(entity.getAction()).isNull();
    }
}