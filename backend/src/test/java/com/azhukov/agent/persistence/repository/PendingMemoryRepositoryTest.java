package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.PendingMemoryEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:pendingmemoryrepo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
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
class PendingMemoryRepositoryTest {

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final String USER_ID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID_2 = "22222222-2222-2222-2222-222222222222";
    private static final String ACTION_ADD = "add";
    private static final String ACTION_UPDATE = "update";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_RESOLVED = "resolved";
    private static final String CONTENT_1 = "User likes coffee";
    private static final String CONTENT_2 = "User lives in Tokyo";
    private static final String SUMMARY_1 = "Coffee preference";
    private static final String SUMMARY_2 = "Location fact";

    @Autowired
    private PendingMemoryRepository pendingMemoryRepository;

    @Test
    void saveAndFindById() {
        PendingMemoryEntity entity = newPendingMemory(USER_ID_1, ACTION_ADD, CONTENT_1, SUMMARY_1, STATUS_PENDING);

        PendingMemoryEntity saved = pendingMemoryRepository.save(entity);
        UUID generatedId = saved.getId();

        assertThat(generatedId).isNotNull();

        PendingMemoryEntity found = pendingMemoryRepository.findById(generatedId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(generatedId);
        assertThat(found.getUserId()).isEqualTo(USER_ID_1);
        assertThat(found.getAction()).isEqualTo(ACTION_ADD);
        assertThat(found.getContent()).isEqualTo(CONTENT_1);
        assertThat(found.getSummary()).isEqualTo(SUMMARY_1);
        assertThat(found.getStatus()).isEqualTo(STATUS_PENDING);
    }

    @Test
    void findByUserIdAndStatusReturnsMatchingEntities() {
        pendingMemoryRepository.save(newPendingMemory(USER_ID_1, ACTION_ADD, CONTENT_1, SUMMARY_1, STATUS_PENDING));
        pendingMemoryRepository.save(newPendingMemory(USER_ID_1, ACTION_UPDATE, CONTENT_2, SUMMARY_2, STATUS_RESOLVED));
        pendingMemoryRepository.save(newPendingMemory(USER_ID_2, ACTION_ADD, CONTENT_1, SUMMARY_1, STATUS_PENDING));

        List<PendingMemoryEntity> results = pendingMemoryRepository.findByUserIdAndStatus(USER_ID_1, STATUS_PENDING);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getUserId()).isEqualTo(USER_ID_1);
        assertThat(results.get(0).getStatus()).isEqualTo(STATUS_PENDING);
    }

    @Test
    void findByIdAndUserIdReturnsEntity() {
        PendingMemoryEntity saved = pendingMemoryRepository.save(newPendingMemory(USER_ID_1, ACTION_ADD, CONTENT_1, SUMMARY_1, STATUS_PENDING));
        UUID id = saved.getId();

        PendingMemoryEntity found = pendingMemoryRepository.findByIdAndUserId(id, USER_ID_1).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findByIdAndUserIdReturnsEmptyForWrongUser() {
        PendingMemoryEntity saved = pendingMemoryRepository.save(newPendingMemory(USER_ID_1, ACTION_ADD, CONTENT_1, SUMMARY_1, STATUS_PENDING));
        UUID id = saved.getId();

        assertThat(pendingMemoryRepository.findByIdAndUserId(id, USER_ID_2)).isEmpty();
    }

    @Test
    void findAllReturnsSavedEntities() {
        pendingMemoryRepository.save(newPendingMemory(USER_ID_1, ACTION_ADD, CONTENT_1, SUMMARY_1, STATUS_PENDING));
        pendingMemoryRepository.save(newPendingMemory(USER_ID_2, ACTION_ADD, CONTENT_2, SUMMARY_2, STATUS_PENDING));

        List<PendingMemoryEntity> all = pendingMemoryRepository.findAll();

        assertThat(all).hasSize(2);
        assertThat(all)
            .extracting(PendingMemoryEntity::getUserId)
            .containsExactlyInAnyOrder(USER_ID_1, USER_ID_2);
    }

    @Test
    void deleteByIdRemovesEntity() {
        PendingMemoryEntity saved = pendingMemoryRepository.save(newPendingMemory(USER_ID_1, ACTION_ADD, CONTENT_1, SUMMARY_1, STATUS_PENDING));
        UUID id = saved.getId();

        assertThat(pendingMemoryRepository.findById(id)).isPresent();

        pendingMemoryRepository.deleteById(id);

        assertThat(pendingMemoryRepository.findById(id)).isEmpty();
    }

    private PendingMemoryEntity newPendingMemory(String userId, String action, String content, String summary, String status) {
        PendingMemoryEntity entity = new PendingMemoryEntity();
        entity.setUserId(userId);
        entity.setAction(action);
        entity.setTarget("memory");
        entity.setContent(content);
        entity.setSummary(summary);
        entity.setOrigin("foreground");
        entity.setStatus(status);
        entity.setCreatedAt(T1);
        return entity;
    }
}