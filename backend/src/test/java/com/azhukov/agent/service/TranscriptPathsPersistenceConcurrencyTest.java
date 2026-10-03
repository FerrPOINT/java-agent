package com.azhukov.agent.service;

import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.MessageMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import java.util.Map;
import java.util.ArrayList;
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

/** Exercises all remaining live transcript entry points against real JPA deletion and commit boundaries. */
@Tag("slow")
class TranscriptPathsPersistenceConcurrencyTest {
    @ParameterizedTest
    @EnumSource(WriterKind.class)
    void deletionBeforeMessageCommitSkipsTheDeletedParent(WriterKind kind) throws Exception {
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
                        return recordMessage(fixture, kind);
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

    @ParameterizedTest
    @EnumSource(WriterKind.class)
    void messageBeforeDeletionCommitsThenCascades(WriterKind kind) throws Exception {
        try (var context = new AnnotationConfigApplicationContext(UsagePersistenceConcurrencyTest.DatabaseConfig.class)) {
            var fixture = fixture(context);
            var persisted = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var deletion = deletionService(fixture, mock(ApplicationEventPublisher.class));
            try (var threads = Executors.newFixedThreadPool(2)) {
                var recorded = threads.submit(() -> fixture.transactions.execute(status -> {
                    assertTrue(recordMessage(fixture, kind));
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

    @org.junit.jupiter.api.Test
    void compressionAndDeletionCompleteWithoutDeadlock() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(UsagePersistenceConcurrencyTest.DatabaseConfig.class)) {
            var fixture = fixture(context);
            try {
                var original = new com.azhukov.agent.persistence.entity.MessageEntity();
                original.setSessionId(fixture.id); original.setRole("user"); original.setContent("old");
                original.setCreatedAt(java.time.Instant.now().minusSeconds(60)); original.setActive(true);
                original = fixture.messages.saveAndFlush(original);
                var ids = java.util.Set.of(original.getId());
                var target = new SessionCompressionHelper(fixture.messages, MessageMapper.INSTANCE,
                    mock(ConversationCompressor.class), fixture.sessions);
                var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
                proxy.addAdvice(new TransactionInterceptor(fixture.transactions.getTransactionManager(), new AnnotationTransactionAttributeSource(false)));
                var compression = (SessionCompressionHelper)proxy.getProxy();
                var parentHeld = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var deletedRows = new CountDownLatch(1);
                var deletionMessages = mock(MessageRepository.class, org.mockito.AdditionalAnswers.delegatesTo(fixture.messages));
                doAnswer(call -> {
                    fixture.messages.deleteBySessionId(fixture.id); deletedRows.countDown(); return null;
                }).when(deletionMessages).deleteBySessionId(fixture.id);
                var deletionTarget = new SessionQueryService(fixture.sessions, deletionMessages,
                    mock(AgentSessionResolver.class), mock(DomainDtoMapper.class), new AgentProperties(), mock(ApplicationEventPublisher.class));
                var deletionProxy = new ProxyFactory(deletionTarget); deletionProxy.setProxyTargetClass(true);
                deletionProxy.addAdvice(new TransactionInterceptor(fixture.transactions.getTransactionManager(), new AnnotationTransactionAttributeSource(false)));
                var deletion = (SessionQueryService)deletionProxy.getProxy();
                try (var threads = Executors.newFixedThreadPool(2)) {
                    var compressed = threads.submit(() -> fixture.transactions.execute(status -> {
                        compression.persistCompressed(fixture.id, List.of(Message.user("summary")), java.time.Instant.now(), ids);
                        parentHeld.countDown(); await(release); return null;
                    }));
                    try {
                        assertTrue(parentHeld.await(10, TimeUnit.SECONDS));
                        var started = new CountDownLatch(1);
                        var deleted = threads.submit(() -> { started.countDown(); return deleteAsOwner(deletion, fixture.id); });
                        assertTrue(started.await(10, TimeUnit.SECONDS));
                        // Compression flushes its message updates before releasing the mutex.
                        // Deletion must complete after commit without a cycle between parent and child locks.
                        deletedRows.await(500, TimeUnit.MILLISECONDS);
                        release.countDown();
                        compressed.get(10, TimeUnit.SECONDS);
                        assertTrue(deleted.get(10, TimeUnit.SECONDS));
                        assertEquals(0, fixture.messages.count());
                    } finally { release.countDown(); }
                }
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
        return new Fixture(sessions.saveAndFlush(session).getId(), sessions, messages, transactions);
    }

    enum WriterKind { OPENAI_HISTORY, OPENAI_TURN, RUNTIME_INCOMING, RUNTIME_BATCH, STREAMING, COMPRESSION, CRON_MIRROR }

    private static boolean recordMessage(Fixture fixture, WriterKind kind) {
        var session = new Session(fixture.id, "user-1", "qa", "noop", "qa", null, Map.of(), null);
        switch (kind) {
            case OPENAI_HISTORY, OPENAI_TURN -> {
                var service = new OpenAiSessionService(mock(AgentSessionResolver.class), fixture.sessions,
                    fixture.messages, MessageMapper.INSTANCE, fixture.transactions, new AgentProperties());
                if (kind == WriterKind.OPENAI_HISTORY) service.persistHistory(fixture.id, List.of(Message.user("qa")));
                else service.persistTurn(new OpenAiSessionService.OpenAiSessionContext(session, true, null),
                    List.of(Message.user("qa")), null);
            }
            case RUNTIME_INCOMING, RUNTIME_BATCH -> {
                var service = mock(AgentRuntimeService.class, CALLS_REAL_METHODS);
                configureWriter(service, fixture);
                if (kind == WriterKind.RUNTIME_INCOMING)
                    ReflectionTestUtils.invokeMethod(service, "persistIncomingMessage", fixture.id, "qa", 0, 0);
                else ReflectionTestUtils.invokeMethod(service, "persistMessages", fixture.id, List.of(Message.user("qa")));
            }
            case COMPRESSION -> {
                var target = new SessionCompressionHelper(fixture.messages, MessageMapper.INSTANCE,
                    mock(ConversationCompressor.class), fixture.sessions);
                var proxy = new ProxyFactory(target);
                proxy.setProxyTargetClass(true);
                proxy.addAdvice(new TransactionInterceptor(fixture.transactions.getTransactionManager(), new AnnotationTransactionAttributeSource(false)));
                ((SessionCompressionHelper)proxy.getProxy()).persistCompressed(fixture.id,
                    List.of(Message.user("qa")), java.time.Instant.now(), java.util.Set.of());
            }
            case CRON_MIRROR -> {
                var service = mock(CronJobService.class, CALLS_REAL_METHODS);
                ReflectionTestUtils.setField(service, "messageRepository", fixture.messages);
                ReflectionTestUtils.setField(service, "transactionTemplate", fixture.transactions);
                @SuppressWarnings("unchecked")
                var provider = (org.springframework.beans.factory.ObjectProvider<SessionRepository>)mock(org.springframework.beans.factory.ObjectProvider.class);
                when(provider.getIfAvailable()).thenReturn(fixture.sessions);
                ReflectionTestUtils.setField(service, "sessionRepositoryProvider", provider);
                var job = new com.azhukov.agent.persistence.entity.CronJobEntity();
                job.setName("qa"); job.setAttachedSessionId(fixture.id);
                var logger = (ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CronJobService.class);
                var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
                appender.start(); logger.addAppender(appender);
                try {
                    ReflectionTestUtils.invokeMethod(service, "mirrorToAttachedSession", job, "qa");
                    assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("attached-session mirror failed")),
                        "deleted-session skip must not attempt an invalid FK insert or report mirror failure");
                } finally { logger.detachAppender(appender); appender.stop(); }
            }
            case STREAMING -> {
                var service = mock(AgentStreamingService.class, CALLS_REAL_METHODS);
                configureWriter(service, fixture);
                var logger = (ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(AgentStreamingService.class);
                var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
                appender.start(); logger.addAppender(appender);
                try {
                    ReflectionTestUtils.invokeMethod(service, "persistTurn", session,
                        new ArrayList<>(List.of(Message.user("qa"))), false, 0);
                    assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("turn_persist_failed")),
                        "deleted-session skip must not attempt an invalid FK insert or report persistence failure");
                } finally { logger.detachAppender(appender); appender.stop(); }
            }
        }
        return true;
    }

    private static void configureWriter(Object writer, Fixture fixture) {
        ReflectionTestUtils.setField(writer, "sessionRepository", fixture.sessions);
        ReflectionTestUtils.setField(writer, "messageRepository", fixture.messages);
        ReflectionTestUtils.setField(writer, "messageMapper", MessageMapper.INSTANCE);
        ReflectionTestUtils.setField(writer, "transactionTemplate", fixture.transactions);
    }

    private static SessionQueryService deletionService(Fixture fixture, ApplicationEventPublisher publisher) {
        var target = new SessionQueryService(fixture.sessions, fixture.messages, mock(AgentSessionResolver.class), mock(DomainDtoMapper.class), new AgentProperties(), publisher);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(fixture.transactions.getTransactionManager(), new AnnotationTransactionAttributeSource(false)));
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
    private record Fixture(UUID id, SessionRepository sessions, MessageRepository messages, TransactionTemplate transactions) {}
}
