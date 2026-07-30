package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.AuditLogEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:auditlogrepo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
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
class AuditLogRepositoryTest {

    private static final String SESSION_ID_1 = "session-001";
    private static final String SESSION_ID_2 = "session-002";
    private static final String ACTOR = "user";
    private static final String ACTION = "CREATE";
    private static final String RESOURCE = "memory";
    private static final String DETAILS = "Created memory entry";

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void saveAndFindById() {
        AuditLogEntity entity = new AuditLogEntity(SESSION_ID_1, ACTOR, ACTION, RESOURCE, DETAILS);

        AuditLogEntity saved = auditLogRepository.save(entity);
        Long generatedId = saved.getId();

        assertThat(generatedId).isNotNull();

        AuditLogEntity found = auditLogRepository.findById(generatedId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(generatedId);
        assertThat(found.getSessionId()).isEqualTo(SESSION_ID_1);
        assertThat(found.getActor()).isEqualTo(ACTOR);
        assertThat(found.getAction()).isEqualTo(ACTION);
        assertThat(found.getResource()).isEqualTo(RESOURCE);
        assertThat(found.getDetails()).isEqualTo(DETAILS);
    }

    @Test
    void findAllReturnsSavedEntities() {
        auditLogRepository.save(new AuditLogEntity(SESSION_ID_1, ACTOR, ACTION, RESOURCE, DETAILS));
        auditLogRepository.save(new AuditLogEntity(SESSION_ID_2, ACTOR, "DELETE", RESOURCE, "Deleted memory"));

        List<AuditLogEntity> all = auditLogRepository.findAll();

        assertThat(all).hasSize(2);
        assertThat(all)
            .extracting(AuditLogEntity::getSessionId)
            .containsExactlyInAnyOrder(SESSION_ID_1, SESSION_ID_2);
    }

    @Test
    void deleteByIdRemovesEntity() {
        AuditLogEntity saved = auditLogRepository.save(new AuditLogEntity(SESSION_ID_1, ACTOR, ACTION, RESOURCE, DETAILS));
        Long id = saved.getId();

        assertThat(auditLogRepository.findById(id)).isPresent();

        auditLogRepository.deleteById(id);

        assertThat(auditLogRepository.findById(id)).isEmpty();
    }

    @Test
    void saveUpdatesExistingEntity() {
        AuditLogEntity saved = auditLogRepository.save(new AuditLogEntity(SESSION_ID_1, ACTOR, ACTION, RESOURCE, DETAILS));
        Long id = saved.getId();

        saved.setAction("UPDATE");
        auditLogRepository.save(saved);

        AuditLogEntity found = auditLogRepository.findById(id).orElseThrow();
        assertThat(found.getAction()).isEqualTo("UPDATE");
    }
}