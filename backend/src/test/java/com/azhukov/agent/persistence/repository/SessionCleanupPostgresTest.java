package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@Transactional
class SessionCleanupPostgresTest extends PostgresTestContainer {
    @Autowired SessionRepository sessions;
    @Autowired MessageRepository messages;
    @Autowired AgentRuntimeService runtime;
    @Autowired com.azhukov.agent.core.agent.SessionTurnLockManager turnLocks;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired com.azhukov.agent.api.SessionController controller;

    @AfterEach
    void clearIdentity() { UserContext.clear(); }

    @Test
    void cleanupMustRejectActiveStreamingTurnAndAllowRetry() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setUserId("cleanup-owner");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        UUID id = sessions.saveAndFlush(session).getId();
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread streaming = new Thread(() -> {
            if (!turnLocks.tryAcquire(id, 1)) return;
            try { acquired.countDown(); release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { turnLocks.release(id); }
        });
        streaming.start();
        try {
            assertThat(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.cleanupSessionState(id))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("busy");
        } finally { release.countDown(); streaming.join(5000); }
        runtime.cleanupSessionState(id);
    }

    @Test
    void busyResetLeavesPersistedMessagesAndCountUnchanged() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setUserId("cleanup-owner");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setMessageCount(1);
        UUID id = sessions.saveAndFlush(session).getId();
        MessageEntity row = new MessageEntity();
        row.setSessionId(id);
        row.setRole("user");
        row.setContent("fixture");
        row.setTurnIndex(0);
        row.setCreatedAt(Instant.now());
        messages.saveAndFlush(row);
        var acquired = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread turn = new Thread(() -> {
            if (!turnLocks.tryAcquire(id, 1)) return;
            try { acquired.countDown(); release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { turnLocks.release(id); }
        });
        turn.start();
        try {
            assertThat(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.resetSession(id))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("busy");
            assertThat(messages.countBySessionId(id)).isEqualTo(1);
            assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isEqualTo(1);
        } finally { release.countDown(); turn.join(5000); }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void committedResetHoldsTurnLockUntilCommitAndRollbackRestoresHistory() {
        UUID id = tx.execute(status -> {
            var session = new SessionEntity();
            session.setUserId("cleanup-owner");
            session.setModelProvider("noop");
            session.setModelName("noop");
            session.setCreatedAt(Instant.now());
            session.setUpdatedAt(Instant.now());
            session.setMessageCount(1);
            UUID sessionId = sessions.saveAndFlush(session).getId();
            var row = new MessageEntity();
            row.setSessionId(sessionId);
            row.setRole("user");
            row.setContent("rollback fixture");
            row.setTurnIndex(0);
            row.setCreatedAt(Instant.now());
            messages.saveAndFlush(row);
            return sessionId;
        });
        try {
            tx.executeWithoutResult(status -> {
                runtime.resetSession(id);
                var contender = java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    assertThat(contender.submit(() -> {
                        boolean locked = turnLocks.tryAcquire(id, 0);
                        if (locked) turnLocks.release(id);
                        return locked;
                    }).get(5, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
                } catch (Exception e) { throw new RuntimeException(e); }
                finally { contender.shutdownNow(); }
                status.setRollbackOnly();
            });
            assertThat(messages.countBySessionId(id)).isEqualTo(1);
            assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isEqualTo(1);
            runtime.resetSession(id);
            assertThat(messages.countBySessionId(id)).isZero();
            assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isZero();
            assertThat(turnLocks.tryAcquire(id, 0)).isTrue();
            turnLocks.release(id);
        } finally {
            tx.executeWithoutResult(status -> { messages.deleteBySessionId(id); sessions.deleteById(id); });
        }
    }

    @Test
    void cleanupHttpBoundaryEnforcesOwnerAndPreservesStoredMessages() throws Exception {
        SessionEntity session = new SessionEntity();
        session.setUserId("cleanup-owner");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        UUID id = sessions.saveAndFlush(session).getId();
        MessageEntity row = new MessageEntity();
        row.setSessionId(id);
        row.setRole("user");
        row.setContent("http fixture");
        row.setTurnIndex(0);
        row.setCreatedAt(Instant.now());
        messages.saveAndFlush(row);
        var props = new com.azhukov.agent.config.AgentProperties();
        props.getSecurity().setApiKey("gateway-fixture");
        var identities = org.mockito.Mockito.mock(com.azhukov.agent.service.UserAccessService.class);
        org.mockito.Mockito.when(identities.authenticate("owner-fixture"))
            .thenReturn(new com.azhukov.agent.service.UserAccessService.AuthenticatedUser("cleanup-owner", "user"));
        org.mockito.Mockito.when(identities.authenticate("foreign-fixture"))
            .thenReturn(new com.azhukov.agent.service.UserAccessService.AuthenticatedUser("another-owner", "user"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new com.azhukov.agent.api.filter.ApiKeyAuthFilter(props, identities))
            .setControllerAdvice(new com.azhukov.agent.api.GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper()))
            .build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/agent/session/{sessionId}/cleanup", id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/agent/session/{sessionId}/cleanup", id).header("X-API-Key", "foreign-fixture"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertThat(messages.countBySessionId(id)).isEqualTo(1);
        for (int i = 0; i < 2; i++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/v1/agent/session/{sessionId}/cleanup", id).header("X-API-Key", "owner-fixture"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
        assertThat(messages.countBySessionId(id)).isEqualTo(1);
    }

    @Test
    void cleanupRejectsForeignOwnerAndDoesNotDeleteHistory() {
        SessionEntity session = new SessionEntity();
        session.setUserId("cleanup-owner");
        session.setTitle("Ownership fixture");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        UUID id = sessions.saveAndFlush(session).getId();
        MessageEntity msg = new MessageEntity();
        msg.setSessionId(id);
        msg.setRole("user");
        msg.setContent("Saved conversation");
        msg.setTurnIndex(0);
        msg.setCreatedAt(Instant.now());
        messages.saveAndFlush(msg);

        UserContext.set("another-owner", UserContext.ROLE_USER);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.cleanupSessionState(id))
            .isInstanceOf(SecurityException.class);
        assertThat(messages.findBySessionIdOrderByCreatedAtAsc(id)).hasSize(1);
        UserContext.set("cleanup-owner", UserContext.ROLE_USER);
        runtime.cleanupSessionState(id);
        assertThat(messages.findBySessionIdOrderByCreatedAtAsc(id)).hasSize(1);
    }

    @Test
    void undoUpdatesCountToMatchRemainingMessages() {
        SessionEntity session = new SessionEntity();
        session.setUserId("cleanup-owner");
        session.setTitle("Undo fixture");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setMessageCount(2);
        UUID id = sessions.saveAndFlush(session).getId();
        for (int i = 0; i < 2; i++) {
            MessageEntity msg = new MessageEntity();
            msg.setSessionId(id);
            msg.setRole("user");
            msg.setContent("message " + i);
            msg.setTurnIndex(i);
            msg.setCreatedAt(Instant.now());
            messages.save(msg);
        }
        messages.flush();
        assertThat(runtime.undoTurns(id, 1)).isEqualTo(1);
        assertThat(messages.countBySessionId(id)).isEqualTo(1);
        assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isEqualTo(1);
    }

    @Test
    void cleanupPreservesHistoryAndResetDeletesIt() {
        SessionEntity session = new SessionEntity();
        session.setUserId("cleanup-owner");
        session.setTitle("History fixture");
        session.setModelProvider("noop");
        session.setModelName("noop");
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session = sessions.saveAndFlush(session);
        UUID id = session.getId();
        MessageEntity msg = new MessageEntity();
        msg.setSessionId(id);
        msg.setRole("user");
        msg.setContent("Saved conversation");
        msg.setTurnIndex(0);
        msg.setCreatedAt(Instant.now());
        messages.saveAndFlush(msg);
        session.setMessageCount(1);
        sessions.saveAndFlush(session);

        runtime.cleanupSessionState(id);
        runtime.cleanupSessionState(id);
        assertThat(messages.findBySessionIdOrderByCreatedAtAsc(id)).hasSize(1);
        runtime.resetSession(id);
        messages.flush();
        assertThat(messages.findBySessionIdOrderByCreatedAtAsc(id)).isEmpty();
        assertThat(sessions.findById(id).orElseThrow().getMessageCount()).isZero();
    }
}
