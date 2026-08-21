package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.SessionEntity;
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
class SessionRepositoryTest extends PostgresTestContainer {

    private static final String USER_ID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String USER_ID_2 = "22222222-2222-2222-2222-222222222222";
    private static final String UNKNOWN_USER_ID = "99999999-9999-9999-9999-999999999999";

    private static final String TITLE_1 = "First Session";
    private static final String TITLE_2 = "Second Session";
    private static final String TITLE_3 = "Third Session";
    private static final String UPDATED_TITLE = "Updated Session Title";

    private static final String MODEL_PROVIDER = "openai-compatible";
    private static final String MODEL_NAME = "kimi-k2.6";

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void saveAndFindById() {
        SessionEntity session = newSession(USER_ID_1, TITLE_1);

        SessionEntity saved = sessionRepository.save(session);
        UUID generatedId = saved.getId();

        assertThat(generatedId).isNotNull();

        SessionEntity found = sessionRepository.findById(generatedId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(generatedId);
        assertThat(found.getUserId()).isEqualTo(USER_ID_1);
        assertThat(found.getTitle()).isEqualTo(TITLE_1);
        assertThat(found.getModelProvider()).isEqualTo(MODEL_PROVIDER);
        assertThat(found.getModelName()).isEqualTo(MODEL_NAME);
    }

    @Test
    void findByUserIdReturnsSavedEntity() {
        sessionRepository.save(newSession(USER_ID_2, TITLE_2));

        SessionEntity found = sessionRepository.findByUserId(USER_ID_2);

        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo(USER_ID_2);
        assertThat(found.getTitle()).isEqualTo(TITLE_2);
    }

    @Test
    void findByUserIdReturnsNullForUnknownUser() {
        SessionEntity found = sessionRepository.findByUserId(UNKNOWN_USER_ID);

        assertThat(found).isNull();
    }

    @Test
    void saveUpdatesExistingEntityTitle() {
        SessionEntity session = newSession(USER_ID_1, TITLE_3);
        SessionEntity saved = sessionRepository.save(session);
        UUID id = saved.getId();

        saved.setTitle(UPDATED_TITLE);
        sessionRepository.save(saved);

        SessionEntity found = sessionRepository.findById(id).orElseThrow();
        assertThat(found.getTitle()).isEqualTo(UPDATED_TITLE);
    }

    @Test
    void findAllByUserIdReturnsMultipleSessions() {
        sessionRepository.save(newSession(USER_ID_1, "Session A"));
        sessionRepository.save(newSession(USER_ID_1, "Session B"));
        sessionRepository.save(newSession(USER_ID_2, "Session C"));

        List<SessionEntity> user1Sessions = sessionRepository.findAllByUserId(USER_ID_1);

        assertThat(user1Sessions).hasSize(2);
        assertThat(user1Sessions)
            .extracting(SessionEntity::getTitle)
            .containsExactlyInAnyOrder("Session A", "Session B");
    }

    private SessionEntity newSession(String userId, String title) {
        SessionEntity session = new SessionEntity();
        session.setUserId(userId);
        session.setTitle(title);
        session.setModelProvider(MODEL_PROVIDER);
        session.setModelName(MODEL_NAME);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }
}
