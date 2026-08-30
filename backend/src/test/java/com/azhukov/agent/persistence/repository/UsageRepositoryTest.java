package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.PostgresTestContainer;
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
class UsageRepositoryTest extends PostgresTestContainer {

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T12:00:00Z");
    private static final Instant T3 = Instant.parse("2026-01-03T12:00:00Z");
    private static final String USER_ID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID_2 = "22222222-2222-2222-2222-222222222222";
    private static final String MODEL_1 = "kimi-k2.6";
    private static final String MODEL_2 = "gpt-4o";

    @Autowired
    private UsageRepository usageRepository;

    @Autowired
    private SessionRepository sessionRepository;

    /** V21 added FK usage_log.session_id -> sessions(id): random UUIDs violate it. */
    private UUID newSessionId() {
        SessionEntity s = new SessionEntity();
        s.setUserId("usage-test-user");
        s.setTitle("usage-test");
        s.setModelProvider("openai-compatible");
        s.setModelName("test-model");
        s.setCreatedAt(Instant.now());
        sessionRepository.save(s);
        return s.getId();
    }

    @Test
    void saveAndFindById() {
        UsageEntity entity = newUsage(newSessionId(), USER_ID_1, MODEL_1, 100, 200, 300, 0.05, T1);

        UsageEntity saved = usageRepository.save(entity);
        UUID generatedId = saved.getId();

        assertThat(generatedId).isNotNull();

        UsageEntity found = usageRepository.findById(generatedId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(generatedId);
        assertThat(found.getUserId()).isEqualTo(USER_ID_1);
        assertThat(found.getModel()).isEqualTo(MODEL_1);
        assertThat(found.getPromptTokens()).isEqualTo(100);
        assertThat(found.getCompletionTokens()).isEqualTo(200);
        assertThat(found.getTotalTokens()).isEqualTo(300);
        assertThat(found.getCost()).isEqualTo(0.05);
    }

    @Test
    void findBySessionIdReturnsEntities() {
        UUID sessionId = newSessionId();
        usageRepository.save(newUsage(sessionId, USER_ID_1, MODEL_1, 50, 100, 150, 0.02, T1));
        usageRepository.save(newUsage(sessionId, USER_ID_1, MODEL_1, 60, 120, 180, 0.03, T2));
        usageRepository.save(newUsage(newSessionId(), USER_ID_2, MODEL_2, 70, 140, 210, 0.04, T1));

        List<UsageEntity> results = usageRepository.findBySessionId(sessionId);

        assertThat(results).hasSize(2);
        assertThat(results)
            .extracting(UsageEntity::getSessionId)
            .containsOnly(sessionId);
    }

    @Test
    void findByUserIdAndCreatedAtBetweenReturnsEntities() {
        usageRepository.save(newUsage(newSessionId(), USER_ID_1, MODEL_1, 10, 20, 30, 0.01, T1));
        usageRepository.save(newUsage(newSessionId(), USER_ID_1, MODEL_1, 40, 50, 90, 0.02, T2));
        usageRepository.save(newUsage(newSessionId(), USER_ID_1, MODEL_1, 15, 25, 40, 0.01, T3));
        usageRepository.save(newUsage(newSessionId(), USER_ID_2, MODEL_2, 10, 20, 30, 0.01, T2));

        List<UsageEntity> results = usageRepository.findByUserIdAndCreatedAtBetween(USER_ID_1, T2, T3);

        assertThat(results).hasSize(2);
        assertThat(results)
            .extracting(UsageEntity::getUserId)
            .containsOnly(USER_ID_1);
    }

    @Test
    void findAllReturnsSavedEntities() {
        usageRepository.save(newUsage(newSessionId(), USER_ID_1, MODEL_1, 100, 200, 300, 0.05, T1));
        usageRepository.save(newUsage(newSessionId(), USER_ID_2, MODEL_2, 200, 400, 600, 0.10, T1));

        List<UsageEntity> all = usageRepository.findAll();

        // The shared PostgreSQL container is also used by unrelated slow E2E
        // contexts, so validate this test's rows without assuming global emptiness.
        assertThat(all)
            .extracting(UsageEntity::getUserId)
            .contains(USER_ID_1, USER_ID_2);
    }

    @Test
    void deleteByIdRemovesEntity() {
        UsageEntity saved = usageRepository.save(newUsage(newSessionId(), USER_ID_1, MODEL_1, 100, 200, 300, 0.05, T1));
        UUID id = saved.getId();

        assertThat(usageRepository.findById(id)).isPresent();

        usageRepository.deleteById(id);

        assertThat(usageRepository.findById(id)).isEmpty();
    }

    @Test
    void saveUpdatesExistingEntity() {
        UsageEntity saved = usageRepository.save(newUsage(newSessionId(), USER_ID_1, MODEL_1, 100, 200, 300, 0.05, T1));
        UUID id = saved.getId();

        saved.setTotalTokens(999);
        usageRepository.save(saved);

        UsageEntity found = usageRepository.findById(id).orElseThrow();
        assertThat(found.getTotalTokens()).isEqualTo(999);
    }

    private UsageEntity newUsage(UUID sessionId, String userId, String model, int promptTokens,
                                  int completionTokens, int totalTokens, Double cost, Instant createdAt) {
        UsageEntity entity = new UsageEntity();
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setModel(model);
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setTotalTokens(totalTokens);
        entity.setCost(cost);
        entity.setCreatedAt(createdAt);
        return entity;
    }
}