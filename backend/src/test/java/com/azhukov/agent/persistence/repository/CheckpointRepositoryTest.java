package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CheckpointEntity;
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
    "spring.datasource.url=jdbc:h2:mem:checkpointrepo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
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
class CheckpointRepositoryTest {

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final String DESCRIPTION_1 = "Initial checkpoint";
    private static final String DESCRIPTION_2 = "Second checkpoint";
    private static final String FILES_JSON = "[]";

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Test
    void saveAndFindById() {
        CheckpointEntity entity = newCheckpoint(DESCRIPTION_1, 3, 1024L);

        CheckpointEntity saved = checkpointRepository.save(entity);
        UUID generatedId = saved.getId();

        assertThat(generatedId).isNotNull();

        CheckpointEntity found = checkpointRepository.findById(generatedId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(generatedId);
        assertThat(found.getDescription()).isEqualTo(DESCRIPTION_1);
        assertThat(found.getFileCount()).isEqualTo(3);
        assertThat(found.getTotalSizeBytes()).isEqualTo(1024L);
        assertThat(found.getFilesJson()).isEqualTo(FILES_JSON);
    }

    @Test
    void findAllReturnsSavedEntities() {
        checkpointRepository.save(newCheckpoint(DESCRIPTION_1, 1, 100L));
        checkpointRepository.save(newCheckpoint(DESCRIPTION_2, 5, 5000L));

        List<CheckpointEntity> all = checkpointRepository.findAll();

        assertThat(all).hasSize(2);
        assertThat(all)
            .extracting(CheckpointEntity::getDescription)
            .containsExactlyInAnyOrder(DESCRIPTION_1, DESCRIPTION_2);
    }

    @Test
    void deleteByIdRemovesEntity() {
        CheckpointEntity saved = checkpointRepository.save(newCheckpoint(DESCRIPTION_1, 2, 2048L));
        UUID id = saved.getId();

        assertThat(checkpointRepository.findById(id)).isPresent();

        checkpointRepository.deleteById(id);

        assertThat(checkpointRepository.findById(id)).isEmpty();
    }

    @Test
    void saveUpdatesExistingEntity() {
        CheckpointEntity saved = checkpointRepository.save(newCheckpoint(DESCRIPTION_1, 1, 100L));
        UUID id = saved.getId();

        saved.setDescription("Updated description");
        checkpointRepository.save(saved);

        CheckpointEntity found = checkpointRepository.findById(id).orElseThrow();
        assertThat(found.getDescription()).isEqualTo("Updated description");
    }

    private CheckpointEntity newCheckpoint(String description, int fileCount, long totalSizeBytes) {
        CheckpointEntity entity = new CheckpointEntity();
        entity.setDescription(description);
        entity.setFileCount(fileCount);
        entity.setTotalSizeBytes(totalSizeBytes);
        entity.setCreatedAt(T1);
        entity.setFilesJson(FILES_JSON);
        return entity;
    }
}