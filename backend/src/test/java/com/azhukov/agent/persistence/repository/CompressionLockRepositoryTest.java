package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.CompressionLockEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
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
    "spring.datasource.url=jdbc:h2:mem:compressionlockrepo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
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
class CompressionLockRepositoryTest {

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final String MODEL_PROVIDER = "openai-compatible";
    private static final String MODEL_NAME = "kimi-k2.6";

    @Autowired
    private CompressionLockRepository compressionLockRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void saveAndFindById() {
        UUID sessionId = createSession().getId();

        CompressionLockEntity entity = new CompressionLockEntity();
        entity.setSessionId(sessionId);
        entity.setLockedAt(T1);

        CompressionLockEntity saved = compressionLockRepository.save(entity);
        UUID generatedId = saved.getId();

        assertThat(generatedId).isNotNull();

        CompressionLockEntity found = compressionLockRepository.findById(generatedId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(generatedId);
        assertThat(found.getSessionId()).isEqualTo(sessionId);
        assertThat(found.getLockedAt()).isEqualTo(T1);
    }

    @Test
    void findBySessionIdReturnsLock() {
        UUID sessionId = createSession().getId();

        CompressionLockEntity entity = new CompressionLockEntity();
        entity.setSessionId(sessionId);
        entity.setLockedAt(T1);
        compressionLockRepository.save(entity);

        CompressionLockEntity found = compressionLockRepository.findBySessionId(sessionId).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void findAllReturnsSavedEntities() {
        UUID sessionId1 = createSession().getId();
        UUID sessionId2 = createSession().getId();

        CompressionLockEntity lock1 = new CompressionLockEntity();
        lock1.setSessionId(sessionId1);
        lock1.setLockedAt(T1);

        CompressionLockEntity lock2 = new CompressionLockEntity();
        lock2.setSessionId(sessionId2);
        lock2.setLockedAt(T1);

        compressionLockRepository.save(lock1);
        compressionLockRepository.save(lock2);

        List<CompressionLockEntity> all = compressionLockRepository.findAll();

        assertThat(all).hasSize(2);
        assertThat(all)
            .extracting(CompressionLockEntity::getSessionId)
            .containsExactlyInAnyOrder(sessionId1, sessionId2);
    }

    @Test
    void deleteByIdRemovesEntity() {
        UUID sessionId = createSession().getId();

        CompressionLockEntity entity = new CompressionLockEntity();
        entity.setSessionId(sessionId);
        entity.setLockedAt(T1);

        CompressionLockEntity saved = compressionLockRepository.save(entity);
        UUID id = saved.getId();

        assertThat(compressionLockRepository.findById(id)).isPresent();

        compressionLockRepository.deleteById(id);

        assertThat(compressionLockRepository.findById(id)).isEmpty();
    }

    private SessionEntity createSession() {
        SessionEntity session = new SessionEntity();
        session.setUserId("11111111-1111-1111-1111-111111111111");
        session.setTitle("Test Session");
        session.setModelProvider(MODEL_PROVIDER);
        session.setModelName(MODEL_NAME);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return sessionRepository.save(session);
    }
}