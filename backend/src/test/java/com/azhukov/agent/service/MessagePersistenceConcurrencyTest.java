package com.azhukov.agent.service;

import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.persistence.service.MessagePersistenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises deletion after the mutex unlock using real JPA, service transactions and the messages FK. */
@Tag("slow")
class MessagePersistenceConcurrencyTest {
    @Test
    void deletionBeforeMessageCommitSkipsTheDeletedParent() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(UsagePersistenceConcurrencyTest.DatabaseConfig.class)) {
            var fixture = fixture(context);
            var atCommit = new CountDownLatch(1);
            var allowCommit = new CountDownLatch(1);
            var publisher = mock(ApplicationEventPublisher.class);
            doAnswer(call -> {
                fixture.sessions.flush();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        atCommit.countDown();
                        await(allowCommit);
                    }
                });
                return null;
            }).when(publisher).publishEvent(any(Object.class));
            var deletion = deletionService(fixture, publisher);
            try (var threads = Executors.newFixedThreadPool(2)) {
                var deleted = threads.submit(() -> deleteAsOwner(deletion, fixture.id));
                try {
                    assertTrue(atCommit.await(10, TimeUnit.SECONDS));
                    var started = new CountDownLatch(1);
                    var recorded = threads.submit(() -> {
                        started.countDown();
                        return recordMessage(fixture);
                    });
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> recorded.get(150, TimeUnit.MILLISECONDS));
                    allowCommit.countDown();
                    assertTrue(deleted.get(10, TimeUnit.SECONDS));
                    assertTrue(recorded.get(10, TimeUnit.SECONDS));
                    assertEquals(0, fixture.messages.count());
                } finally { allowCommit.countDown(); }
            } finally { cleanup(context); }
        }
    }

    @Test
    void messageBeforeDeletionCommitsThenCascades() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(UsagePersistenceConcurrencyTest.DatabaseConfig.class)) {
            var fixture = fixture(context);
            var persisted = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var deletion = deletionService(fixture, mock(ApplicationEventPublisher.class));
            try (var threads = Executors.newFixedThreadPool(2)) {
                var recorded = threads.submit(() -> fixture.transactions.execute(status -> {
                    assertTrue(recordMessage(fixture));
                    fixture.messages.flush();
                    persisted.countDown();
                    await(commit);
                    return null;
                }));
                try {
                    assertTrue(persisted.await(10, TimeUnit.SECONDS));
                    var started = new CountDownLatch(1);
                    var deleted = threads.submit(() -> { started.countDown(); return deleteAsOwner(deletion, fixture.id); });
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> deleted.get(150, TimeUnit.MILLISECONDS));
                    commit.countDown();
                    recorded.get(10, TimeUnit.SECONDS);
                    assertTrue(deleted.get(10, TimeUnit.SECONDS));
                    assertFalse(fixture.sessions.existsById(fixture.id));
                    assertEquals(0, fixture.messages.count());
                } finally { commit.countDown(); }
            } finally { cleanup(context); }
        }
    }

    private static Fixture fixture(AnnotationConfigApplicationContext context) {
        String schema = context.getBean("usageSchema", String.class);
        new JdbcTemplate(context.getBean(DataSource.class)).execute("ALTER TABLE " + schema + ".messages ADD CONSTRAINT fk_messages_session FOREIGN KEY (session_id) REFERENCES " + schema + ".sessions(id) ON DELETE CASCADE");
        var sessions = context.getBean(SessionRepository.class);
        var messages = context.getBean(MessageRepository.class);
        var session = new SessionEntity();
        session.setUserId("user-1"); session.setModelProvider("noop"); session.setModelName("qa");
        var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        var target = new MessagePersistenceService(messages, sessions, MessageMapper.INSTANCE);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions.getTransactionManager(), new AnnotationTransactionAttributeSource()));
        var service = (MessagePersistenceService)proxy.getProxy();
        return new Fixture(sessions.saveAndFlush(session).getId(), sessions, messages, service, transactions);
    }

    private static boolean recordMessage(Fixture fixture) {
        fixture.persistence.persistUserMessage(new com.azhukov.agent.core.model.Session(fixture.id, "user-1", "qa", "noop", "qa", null, java.util.Map.of(), null), "qa");
        return true;
    }

    private static SessionQueryService deletionService(Fixture fixture, ApplicationEventPublisher publisher) {
        var target = new SessionQueryService(fixture.sessions, fixture.messages, mock(AgentSessionResolver.class), mock(DomainDtoMapper.class), new AgentProperties(), publisher);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(fixture.transactions.getTransactionManager(), new AnnotationTransactionAttributeSource()));
        return (SessionQueryService)proxy.getProxy();
    }

    private static boolean deleteAsOwner(SessionQueryService deletion, UUID id) {
        UserContext.set("user-1", UserContext.ROLE_USER);
        try { return deletion.deleteSession(id); }
        finally { UserContext.clear(); }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
    }
    private static void cleanup(AnnotationConfigApplicationContext context) {
        new JdbcTemplate(context.getBean(DataSource.class)).execute("DROP SCHEMA " + context.getBean("usageSchema", String.class) + " CASCADE");
    }
    private record Fixture(UUID id, SessionRepository sessions, MessageRepository messages, MessagePersistenceService persistence, TransactionTemplate transactions) {}
}
