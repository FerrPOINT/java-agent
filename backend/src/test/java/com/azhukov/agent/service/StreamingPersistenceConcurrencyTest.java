package com.azhukov.agent.service;

import com.azhukov.agent.core.agent.SessionMutationLock;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The external title callback must see a committed transcript and an available mutation mutex. */
@Tag("slow")
class StreamingPersistenceConcurrencyTest {
    @Test
    void titleCallbackRunsAfterCommitAndMutationUnlock() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(UsagePersistenceConcurrencyTest.DatabaseConfig.class)) {
            var fixture = fixture(context);
            try {
                doAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    try (var peer = Executors.newSingleThreadExecutor()) {
                        int visible = peer.submit(() -> SessionMutationLock.withLock(fixture.id,
                            () -> fixture.jdbc.queryForObject("SELECT count(*) FROM " + fixture.schema + ".messages WHERE session_id=?", Integer.class, fixture.id)))
                            .get(5, TimeUnit.SECONDS);
                        assertEquals(1, visible, "a separate connection sees the committed message while holding the released mutex");
                    }
                    return null;
                }).when(fixture.title).maybeUpdateTitle(eq(fixture.id), anyList(), eq(true));
                persist(fixture);
                verify(fixture.title).maybeUpdateTitle(eq(fixture.id), anyList(), eq(true));
            } finally { cleanup(fixture); }
        }
    }

    @Test
    void failedTranscriptCommitDoesNotStartTitleGeneration() {
        try (var context = new AnnotationConfigApplicationContext(UsagePersistenceConcurrencyTest.DatabaseConfig.class)) {
            var fixture = fixture(context);
            try {
                // Fault injection affects only this test's disposable schema.
                fixture.jdbc.execute("DROP TABLE " + fixture.schema + ".messages");
                assertDoesNotThrow(() -> persist(fixture));
                verifyNoInteractions(fixture.title);
            } finally { cleanup(fixture); }
        }
    }

    private static Fixture fixture(AnnotationConfigApplicationContext context) {
        var sessions = context.getBean(SessionRepository.class);
        var session = new SessionEntity();
        session.setUserId("user-1"); session.setModelProvider("noop"); session.setModelName("qa"); session.setTitle("New chat");
        UUID id = sessions.saveAndFlush(session).getId();
        var streaming = mock(AgentStreamingService.class, CALLS_REAL_METHODS);
        var title = mock(SessionTitleService.class);
        ReflectionTestUtils.setField(streaming, "sessionRepository", sessions);
        ReflectionTestUtils.setField(streaming, "messageRepository", context.getBean(MessageRepository.class));
        ReflectionTestUtils.setField(streaming, "messageMapper", MessageMapper.INSTANCE);
        ReflectionTestUtils.setField(streaming, "transactionTemplate", new TransactionTemplate(context.getBean(PlatformTransactionManager.class)));
        ReflectionTestUtils.setField(streaming, "sessionTitleService", title);
        return new Fixture(id, context.getBean("usageSchema", String.class), new JdbcTemplate(context.getBean(DataSource.class)), streaming, title);
    }
    private static void persist(Fixture fixture) {
        var session = new Session(fixture.id, "user-1", "New chat", "noop", "qa", null, Map.of(), null);
        ReflectionTestUtils.invokeMethod(fixture.streaming, "persistTurn", session, List.of(Message.user("qa")), true, 0);
    }
    private static void cleanup(Fixture fixture) { fixture.jdbc.execute("DROP SCHEMA " + fixture.schema + " CASCADE"); }
    private record Fixture(UUID id, String schema, JdbcTemplate jdbc, AgentStreamingService streaming, SessionTitleService title) {}
}
