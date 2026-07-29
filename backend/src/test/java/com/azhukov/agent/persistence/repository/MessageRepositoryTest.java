package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("slow")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:messagerepo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
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
class MessageRepositoryTest {

    private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String MODEL_PROVIDER = "openai-compatible";
    private static final String MODEL_NAME = "kimi-k2.6";

    private static final String SESSION_TITLE = "Message Test Session";

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T12:00:01Z");
    private static final Instant T3 = Instant.parse("2026-01-01T12:00:02Z");

    private static final String UNKNOWN_SESSION_ID = "77777777-7777-7777-7777-777777777777";

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void saveMessageLinkedToSession() {
        SessionEntity session = sessionRepository.save(newSession());
        MessageEntity message = newMessage(session.getId(), "user", "Hello", 0, T1);

        MessageEntity saved = messageRepository.save(message);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSessionId()).isEqualTo(session.getId());
        assertThat(saved.getRole()).isEqualTo("user");
        assertThat(saved.getContent()).isEqualTo("Hello");
        assertThat(saved.getTurnIndex()).isZero();
        assertThat(saved.getCreatedAt()).isEqualTo(T1);
    }

    @Test
    void findBySessionIdOrderByCreatedAtAscReturnsMessagesInOrder() {
        SessionEntity session = sessionRepository.save(newSession());
        UUID sessionId = session.getId();

        messageRepository.save(newMessage(sessionId, "assistant", "Second", 1, T2));
        messageRepository.save(newMessage(sessionId, "user", "First", 0, T1));
        messageRepository.save(newMessage(sessionId, "assistant", "Third", 1, T3));

        List<MessageEntity> found = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        assertThat(found).hasSize(3);
        assertThat(found).extracting(MessageEntity::getContent)
            .containsExactly("First", "Second", "Third");
        assertThat(found).extracting(MessageEntity::getCreatedAt)
            .containsExactly(T1, T2, T3);
    }

    @Test
    void findBySessionIdAndTurnIndexOrderByCreatedAtAscFiltersByTurnIndex() {
        SessionEntity session = sessionRepository.save(newSession());
        UUID sessionId = session.getId();

        messageRepository.save(newMessage(sessionId, "user", "Turn 0 user", 0, T1));
        messageRepository.save(newMessage(sessionId, "assistant", "Turn 1 assistant", 1, T2));
        messageRepository.save(newMessage(sessionId, "user", "Turn 0 second user", 0, T3));

        List<MessageEntity> turn0 = messageRepository.findBySessionIdAndTurnIndexOrderByCreatedAtAsc(sessionId, 0);

        assertThat(turn0).hasSize(2);
        assertThat(turn0).extracting(MessageEntity::getContent)
            .containsExactly("Turn 0 user", "Turn 0 second user");
        assertThat(turn0).extracting(MessageEntity::getTurnIndex).containsOnly(0);

        List<MessageEntity> turn1 = messageRepository.findBySessionIdAndTurnIndexOrderByCreatedAtAsc(sessionId, 1);
        assertThat(turn1).hasSize(1);
        assertThat(turn1.get(0).getContent()).isEqualTo("Turn 1 assistant");
    }

    @Test
    void countBySessionIdReturnsCorrectCount() {
        SessionEntity session = sessionRepository.save(newSession());
        UUID sessionId = session.getId();

        messageRepository.save(newMessage(sessionId, "user", "One", 0, T1));
        messageRepository.save(newMessage(sessionId, "assistant", "Two", 1, T2));
        messageRepository.save(newMessage(sessionId, "user", "Three", 2, T3));

        long count = messageRepository.countBySessionId(sessionId);

        assertThat(count).isEqualTo(3);
        assertThat(messageRepository.countBySessionId(UUID.fromString(UNKNOWN_SESSION_ID))).isZero();
    }

    @Test
    void messageWithoutSessionFailsForeignKeyConstraint() {
        MessageEntity orphan = newMessage(
            UUID.fromString(UNKNOWN_SESSION_ID),
            "user",
            "No parent session",
            0,
            T1
        );

        assertThatThrownBy(() -> {
            messageRepository.save(orphan);
            messageRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private SessionEntity newSession() {
        SessionEntity session = new SessionEntity();
        session.setUserId(USER_ID);
        session.setTitle(SESSION_TITLE);
        session.setModelProvider(MODEL_PROVIDER);
        session.setModelName(MODEL_NAME);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return session;
    }

    private MessageEntity newMessage(UUID sessionId, String role, String content, int turnIndex, Instant createdAt) {
        MessageEntity message = new MessageEntity();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setTurnIndex(turnIndex);
        message.setCreatedAt(createdAt);
        return message;
    }
}
