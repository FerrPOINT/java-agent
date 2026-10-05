package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.MemoryWriteAuditEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true",
    "spring.flyway.baseline-on-migrate=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.jpa.hibernate.ddl-auto=none",
    "agent.model.provider=noop",
    "agent.memory.enabled=false",
    "agent.skills.enabled=false",
    "agent.mcp.enabled=false",
    "agent.mcp.servers=",
    "agent.chromium.auto-start=false",
    "agent.chromium.auto-install=false"
})
@Transactional
class MemoryWriteAuditRepositoryTest extends PostgresTestContainer {

    @Autowired
    private MemoryWriteAuditRepository repository;

    @Test
    void persistsMemoryMutationProvenanceInPostgres() {
        MemoryWriteAuditEntity audit = new MemoryWriteAuditEntity();
        audit.setUserId("audit-user");
        audit.setTarget("memory");
        audit.setAction("replace");
        audit.setMemoryId(java.util.UUID.randomUUID());
        audit.setFact("new durable fact");
        audit.setOldFact("old durable fact");
        audit.setWriteOrigin("BACKGROUND_REVIEW");
        audit.setExecutionContext("background_review");
        audit.setSourceSessionId("source-session");
        audit.setParentSessionId("parent-session");
        audit.setPlatform("telegram");
        audit.setToolName("memory");

        MemoryWriteAuditEntity saved = repository.saveAndFlush(audit);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(repository.findBySourceSessionIdOrderByCreatedAtDesc("source-session"))
            .singleElement()
            .extracting(MemoryWriteAuditEntity::getMemoryId,
                MemoryWriteAuditEntity::getUserId,
                MemoryWriteAuditEntity::getAction,
                MemoryWriteAuditEntity::getFact,
                MemoryWriteAuditEntity::getOldFact,
                MemoryWriteAuditEntity::getWriteOrigin,
                MemoryWriteAuditEntity::getExecutionContext,
                MemoryWriteAuditEntity::getParentSessionId,
                MemoryWriteAuditEntity::getPlatform,
                MemoryWriteAuditEntity::getToolName)
            .containsExactly(audit.getMemoryId(), "audit-user", "replace", "new durable fact", "old durable fact",
                "BACKGROUND_REVIEW", "background_review", "parent-session", "telegram", "memory");
    }
}
