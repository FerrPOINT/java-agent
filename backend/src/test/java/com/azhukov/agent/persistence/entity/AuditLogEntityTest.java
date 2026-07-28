package com.azhukov.agent.persistence.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogEntityTest {

    @Test
    @DisplayName("No-arg constructor creates instance with null fields")
    void noArgConstructorCreatesInstanceWithNullFields() {
        AuditLogEntity entity = new AuditLogEntity();

        assertThat(entity.getId()).isNull();
        assertThat(entity.getSessionId()).isNull();
        assertThat(entity.getActor()).isNull();
        assertThat(entity.getAction()).isNull();
        assertThat(entity.getResource()).isNull();
        assertThat(entity.getDetails()).isNull();
        assertThat(entity.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("All-args constructor sets all fields")
    void allArgsConstructorSetsAllFields() {
        String sessionId = "session-123";
        String actor = "user-456";
        String action = "CREATE";
        String resource = "session/session-123";
        String details = "Created new session";

        AuditLogEntity entity = new AuditLogEntity(sessionId, actor, action, resource, details);

        assertThat(entity.getSessionId()).isEqualTo(sessionId);
        assertThat(entity.getActor()).isEqualTo(actor);
        assertThat(entity.getAction()).isEqualTo(action);
        assertThat(entity.getResource()).isEqualTo(resource);
        assertThat(entity.getDetails()).isEqualTo(details);
    }

    @Test
    @DisplayName("All getters return correct values after all-args constructor")
    void allGettersReturnCorrectValues() {
        String sessionId = "sess-abc";
        String actor = "admin";
        String action = "DELETE";
        String resource = "file/test.txt";
        String details = "Deleted test.txt";

        AuditLogEntity entity = new AuditLogEntity(sessionId, actor, action, resource, details);

        assertThat(entity.getSessionId()).isEqualTo("sess-abc");
        assertThat(entity.getActor()).isEqualTo("admin");
        assertThat(entity.getAction()).isEqualTo("DELETE");
        assertThat(entity.getResource()).isEqualTo("file/test.txt");
        assertThat(entity.getDetails()).isEqualTo("Deleted test.txt");
    }

    @Test
    @DisplayName("createdAt is null on new entity (not persisted)")
    void createdAtIsNullOnNewEntity() {
        AuditLogEntity entity = new AuditLogEntity("sess", "user", "READ", "res", "details");

        // createdAt is @CreationTimestamp, so it's null until persisted by Hibernate
        assertThat(entity.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("getId returns null on new entity (not persisted)")
    void getIdReturnsNullOnNewEntity() {
        AuditLogEntity entity = new AuditLogEntity("sess", "user", "READ", "res", "details");

        // id is @GeneratedValue, so it's null until persisted
        assertThat(entity.getId()).isNull();
    }

    @Test
    @DisplayName("No-arg constructor can be instantiated without error")
    void noArgConstructorCanBeInstantiated() {
        AuditLogEntity entity = new AuditLogEntity();
        assertThat(entity).isNotNull();
        assertThat(entity).isInstanceOf(AuditLogEntity.class);
    }
}