package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.MemoryEntity;
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
    "spring.datasource.url=jdbc:h2:mem:memoryrepo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
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
class MemoryRepositoryTest {

    private static final String USER_ID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID_2 = "22222222-2222-2222-2222-222222222222";
    private static final String UNKNOWN_USER_ID = "99999999-9999-9999-9999-999999999999";

    private static final String CATEGORY_PREFERENCE = "preference";
    private static final String CATEGORY_FACT = "fact";

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T12:00:01Z");
    private static final Instant T3 = Instant.parse("2026-01-01T12:00:02Z");

    @Autowired
    private MemoryRepository memoryRepository;

    @Test
    void saveMemoryForUser() {
        MemoryEntity memory = newMemory(USER_ID_1, CATEGORY_PREFERENCE, "User prefers dark mode", T1);

        MemoryEntity saved = memoryRepository.save(memory);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(USER_ID_1);
        assertThat(saved.getCategory()).isEqualTo(CATEGORY_PREFERENCE);
        assertThat(saved.getFact()).isEqualTo("User prefers dark mode");
        assertThat(saved.getCreatedAt()).isEqualTo(T1);
    }

    @Test
    void findByUserIdReturnsUsersMemories() {
        MemoryEntity memory1 = memoryRepository.save(newMemory(USER_ID_1, CATEGORY_PREFERENCE, "Dark mode", T1));
        MemoryEntity memory2 = memoryRepository.save(newMemory(USER_ID_1, CATEGORY_FACT, "Lives in Berlin", T2));
        memoryRepository.save(newMemory(USER_ID_2, CATEGORY_FACT, "Lives in Paris", T1));

        List<MemoryEntity> user1Memories = memoryRepository.findByUserIdOrderByCreatedAtDesc(USER_ID_1);

        assertThat(user1Memories).hasSize(2);
        assertThat(user1Memories)
            .extracting(MemoryEntity::getId)
            .containsExactlyInAnyOrder(memory1.getId(), memory2.getId());
        assertThat(user1Memories)
            .extracting(MemoryEntity::getFact)
            .containsExactlyInAnyOrder("Dark mode", "Lives in Berlin");
    }

    @Test
    void searchByUserIdAndContentContainingKeyword() {
        memoryRepository.save(newMemory(USER_ID_1, CATEGORY_PREFERENCE, "Dark mode is preferred", T1));
        memoryRepository.save(newMemory(USER_ID_1, CATEGORY_FACT, "Uses VS Code editor", T2));
        memoryRepository.save(newMemory(USER_ID_2, CATEGORY_PREFERENCE, "Light mode preferred", T3));
        memoryRepository.save(newMemory(USER_ID_1, CATEGORY_FACT, "Runs every morning", T3));

        List<MemoryEntity> results = memoryRepository.findByUserIdAndFactLikeIgnoreCase(USER_ID_1, "%dark%");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getFact()).isEqualTo("Dark mode is preferred");
        assertThat(results.get(0).getUserId()).isEqualTo(USER_ID_1);
    }

    @Test
    void deleteMemory() {
        MemoryEntity memory = memoryRepository.save(newMemory(USER_ID_1, CATEGORY_FACT, "To be deleted", T1));
        UUID memoryId = memory.getId();

        assertThat(memoryRepository.findById(memoryId)).isPresent();

        memoryRepository.deleteById(memoryId);

        assertThat(memoryRepository.findById(memoryId)).isEmpty();
        assertThat(memoryRepository.findByUserIdOrderByCreatedAtDesc(USER_ID_1)).isEmpty();
    }

    @Test
    void orderingByCreatedAtDesc() {
        MemoryEntity oldest = memoryRepository.save(newMemory(USER_ID_2, CATEGORY_PREFERENCE, "First memory", T1));
        MemoryEntity middle = memoryRepository.save(newMemory(USER_ID_2, CATEGORY_FACT, "Second memory", T2));
        MemoryEntity newest = memoryRepository.save(newMemory(USER_ID_2, CATEGORY_PREFERENCE, "Third memory", T3));

        List<MemoryEntity> memories = memoryRepository.findByUserIdOrderByCreatedAtDesc(USER_ID_2);

        assertThat(memories).hasSize(3);
        assertThat(memories)
            .extracting(MemoryEntity::getId)
            .containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(memories)
            .extracting(MemoryEntity::getCreatedAt)
            .containsExactly(T3, T2, T1);
    }

    @Test
    void findByUserIdReturnsEmptyForUnknownUser() {
        memoryRepository.save(newMemory(USER_ID_1, CATEGORY_FACT, "Existing memory", T1));

        List<MemoryEntity> results = memoryRepository.findByUserIdOrderByCreatedAtDesc(UNKNOWN_USER_ID);

        assertThat(results).isEmpty();
    }

    private MemoryEntity newMemory(String userId, String category, String fact, Instant createdAt) {
        MemoryEntity memory = new MemoryEntity();
        memory.setUserId(userId);
        memory.setCategory(category);
        memory.setFact(fact);
        memory.setCreatedAt(createdAt);
        return memory;
    }
}
