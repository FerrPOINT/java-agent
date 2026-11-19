package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.service.MidTurnPersistenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("slow")
class MidTurnAtomicPostgresTest extends PostgresTestContainer {
    @Autowired SessionRepository sessions;
    @Autowired MessageRepository messages;
    @Autowired MessageMapper mapper;
    @Autowired TransactionTemplate transaction;
    @Autowired javax.sql.DataSource dataSource;

    @Test
    void sameTimestampBatchRetainsInputOrderAcrossRead() {
        var entity = new SessionEntity();
        entity.setUserId("ordered-test-owner"); entity.setModelProvider("noop"); entity.setModelName("noop");
        entity.setCreatedAt(Instant.now()); entity.setUpdatedAt(Instant.now());
        var id = sessions.saveAndFlush(entity).getId();
        try {
            var service = new MidTurnPersistenceService(messages, mapper, transaction, sessions, null);
            var batch = List.of(Message.user("first"), Message.assistant("second", 0), Message.toolResult("call", "third", 0));
            assertThat(service.persistNewMessages(id, batch, 0)).isTrue();
            var rows = messages.findBySessionIdOrderByCreatedAtAsc(id);
            assertThat(rows).extracting(com.azhukov.agent.persistence.entity.MessageEntity::getContent)
                .containsExactly("first", "second", "third");
            assertThat(rows).extracting(com.azhukov.agent.persistence.entity.MessageEntity::getInsertOrder)
                .doesNotContainNull().doesNotHaveDuplicates();
        } finally {
            transaction.executeWithoutResult(status -> { messages.deleteBySessionId(id); sessions.deleteById(id); });
        }
    }

    @Test
    void activeHistoryLimitIgnoresNewerArchivedRows() {
        var entity = new SessionEntity();
        entity.setUserId("active-window-owner"); entity.setModelProvider("noop"); entity.setModelName("noop");
        entity.setCreatedAt(Instant.now()); entity.setUpdatedAt(Instant.now());
        var id = sessions.saveAndFlush(entity).getId();
        try {
            Instant timestamp = Instant.now();
            for (int i = 0; i < 7; i++) {
                var row = new com.azhukov.agent.persistence.entity.MessageEntity();
                row.setSessionId(id); row.setRole("user"); row.setContent("item-" + i);
                row.setCreatedAt(timestamp); row.setActive(i < 3);
                messages.save(row);
            }
            messages.flush();
            var adapter = new com.azhukov.agent.persistence.adapter.JpaMessageStore(messages);
            assertThat(adapter.findBySessionIdAndActiveTrueOrderByCreatedAtDesc(id, 3))
                .extracting(com.azhukov.agent.persistence.entity.MessageEntity::getContent)
                .containsExactly("item-2", "item-1", "item-0");
        } finally {
            transaction.executeWithoutResult(status -> { messages.deleteBySessionId(id); sessions.deleteById(id); });
        }
    }

    @Test
    void sameTimestampPaginationAndActiveReadsMatchFullHistoryOrder() {
        var entity = new SessionEntity();
        entity.setUserId("pagination-order-test"); entity.setModelProvider("noop"); entity.setModelName("noop");
        entity.setCreatedAt(Instant.now()); entity.setUpdatedAt(Instant.now());
        var id = sessions.saveAndFlush(entity).getId();
        try {
            var service = new MidTurnPersistenceService(messages, mapper, transaction, sessions, null);
            var batch = java.util.stream.IntStream.range(0, 45).mapToObj(i -> Message.user("item-" + i)).toList();
            assertThat(service.persistNewMessages(id, batch, 0)).isTrue();
            var expected = messages.findBySessionIdOrderByCreatedAtAsc(id).stream().map(e -> e.getId()).toList();
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.update("UPDATE messages SET content = content WHERE id = ?", expected.get(0));
            transaction.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL enable_indexscan = off");
                jdbc.execute("SET LOCAL enable_bitmapscan = off");
                assertThat(messages.findBySessionIdOrderByCreatedAtAsc(id, org.springframework.data.domain.PageRequest.of(0, 45)))
                    .extracting(com.azhukov.agent.persistence.entity.MessageEntity::getId).containsExactlyElementsOf(expected);
                assertThat(messages.findBySessionIdAndActiveTrueOrderByCreatedAtAsc(id))
                    .extracting(com.azhukov.agent.persistence.entity.MessageEntity::getId).containsExactlyElementsOf(expected);
                var reversedInside = new java.util.ArrayList<>(expected);
                java.util.Collections.reverse(reversedInside);
                assertThat(messages.findBySessionIdOrderByCreatedAtDesc(id, org.springframework.data.domain.PageRequest.of(0, 45)))
                    .extracting(com.azhukov.agent.persistence.entity.MessageEntity::getId).containsExactlyElementsOf(reversedInside);
            });
            assertThat(messages.findActivePageBySessionIdOrderByCreatedAtAsc(id, 45, 0))
                .extracting(com.azhukov.agent.persistence.entity.MessageEntity::getId).containsExactlyElementsOf(expected);
            assertThat(messages.findBySessionIdOrderByCreatedAtAsc(id, org.springframework.data.domain.PageRequest.of(0, 45)))
                .extracting(com.azhukov.agent.persistence.entity.MessageEntity::getId).containsExactlyElementsOf(expected);
            assertThat(messages.findBySessionIdAndActiveTrueOrderByCreatedAtAsc(id))
                .extracting(com.azhukov.agent.persistence.entity.MessageEntity::getId).containsExactlyElementsOf(expected);
            var reversed = new java.util.ArrayList<>(expected); java.util.Collections.reverse(reversed);
            assertThat(messages.findActivePageBySessionIdOrderByCreatedAtDesc(id, 45, 0))
                .extracting(com.azhukov.agent.persistence.entity.MessageEntity::getId).containsExactlyElementsOf(reversed);
        } finally {
            transaction.executeWithoutResult(status -> { messages.deleteBySessionId(id); sessions.deleteById(id); });
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
