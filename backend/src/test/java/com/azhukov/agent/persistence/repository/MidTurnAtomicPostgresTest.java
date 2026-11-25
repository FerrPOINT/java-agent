package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.service.MidTurnPersistenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("slow")
class MidTurnAtomicPostgresTest extends PostgresTestContainer {
    @Autowired SessionRepository sessions;
    @Autowired MessageRepository messages;
    @Autowired MessageMapper mapper;
    @Autowired TransactionTemplate transaction;
    @Autowired DataSource dataSource;

    @Test
    void sameTimestampUsesInsertionOrderAcrossHistoryReads() {
        var session = new SessionEntity();
        session.setUserId("ordered-history-owner");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        var sessionId = sessions.saveAndFlush(session).getId();
        var jdbc = new JdbcTemplate(dataSource);
        var timestamp = Timestamp.from(Instant.now());
        var legacy = new UUID(0, 4);
        var first = new UUID(0, 3);
        var archived = new UUID(0, 2);
        var last = new UUID(0, 1);
        var all = List.of(legacy, first, archived, last);
        var active = List.of(legacy, first, last);
        try {
            for (var id : all) {
                jdbc.update("""
                    INSERT INTO messages (id, session_id, role, content, turn_index, active, created_at)
                    VALUES (?, ?, 'user', 'history', 1, ?, ?)
                    """, id, sessionId, !id.equals(archived), timestamp);
            }
            jdbc.update("UPDATE messages SET insert_order = NULL WHERE id = ?", legacy);

            assertThat(messages.findBySessionIdOrderByCreatedAtAsc(sessionId))
                .extracting(MessageEntity::getId).containsExactlyElementsOf(all);
            assertThat(messages.findBySessionIdOrderByCreatedAtAsc(sessionId, PageRequest.of(1, 2)))
                .extracting(MessageEntity::getId).containsExactly(archived, last);
            assertThat(messages.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, 4)))
                .extracting(MessageEntity::getId).containsExactlyElementsOf(all.reversed());
            assertThat(messages.findBySessionIdAndTurnIndexOrderByCreatedAtAsc(sessionId, 1))
                .extracting(MessageEntity::getId).containsExactlyElementsOf(all);
            assertThat(messages.findBySessionIdAndActiveTrueOrderByCreatedAtAsc(sessionId))
                .extracting(MessageEntity::getId).containsExactlyElementsOf(active);
            assertThat(messages.findActivePageBySessionIdOrderByCreatedAtAsc(sessionId, 3, 0))
                .extracting(MessageEntity::getId).containsExactlyElementsOf(active);
            assertThat(messages.findActivePageBySessionIdOrderByCreatedAtDesc(sessionId, 3, 0))
                .extracting(MessageEntity::getId).containsExactlyElementsOf(active.reversed());
            assertThat(messages.findPageBySessionIdOrderByCreatedAtDesc(sessionId, 4, 0))
                .extracting(MessageEntity::getId).containsExactlyElementsOf(all.reversed());
        } finally {
            transaction.executeWithoutResult(status -> {
                messages.deleteBySessionId(sessionId);
                sessions.deleteById(sessionId);
            });
        }
    }

    @Test
    void concurrentBatchesKeepCountEqualToPersistedRows() throws Exception {
        SessionEntity entity = new SessionEntity();
        entity.setUserId("atomic-test-owner");
        entity.setTitle("Concurrent fixture");
        entity.setModelProvider("noop");
        entity.setModelName("noop");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        var id = sessions.saveAndFlush(entity).getId();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var service = new MidTurnPersistenceService(messages, mapper, transaction, sessions, null);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 24; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.persistNewMessages(id, List.of(Message.user("message " + index)), 0);
                }));
            }
            start.countDown();
            for (var future : futures) assertThat(future.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(messages.countBySessionId(id)).isEqualTo(24);
            assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isEqualTo(24);
        } finally {
            executor.shutdownNow();
            transaction.executeWithoutResult(status -> {
                messages.deleteBySessionId(id);
                sessions.deleteById(id);
            });
        }
    }

    @Test
    void failedMetadataUpdateRollsBackMessageAndSuccessfulRetryIsConsistent() {
        SessionEntity entity = new SessionEntity();
        entity.setUserId("atomic-test-owner");
        entity.setTitle("Atomic fixture");
        entity.setModelProvider("noop");
        entity.setModelName("noop");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        var id = sessions.saveAndFlush(entity).getId();
        try {
            SessionRepository failing = mock(SessionRepository.class);
            when(failing.findMessageParentId(id)).thenAnswer(invocation -> sessions.findMessageParentId(id));
            doThrow(new IllegalStateException("injected metadata failure"))
                .when(failing).incrementMessageCount(eq(id), any(), anyInt());
            var service = new MidTurnPersistenceService(messages, mapper, transaction, failing, null);
            assertThat(service.persistNewMessages(id, List.of(Message.user("test message")), 0)).isFalse();
            assertThat(messages.countBySessionId(id)).isZero();
            assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isZero();

            var normal = new MidTurnPersistenceService(messages, mapper, transaction, sessions, null);
            assertThat(normal.persistNewMessages(id, List.of(Message.user("test message")), 0)).isTrue();
            assertThat(messages.countBySessionId(id)).isEqualTo(1);
            assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isEqualTo(1);
        } finally {
            transaction.executeWithoutResult(status -> {
                messages.deleteBySessionId(id);
                sessions.deleteById(id);
            });
        }
    }
}
