package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.persistence.repository.UsageRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Real JPA transactions; an explicit disposable PostgreSQL URL may replace H2. */
@Tag("slow")
class UsagePersistenceConcurrencyTest {
    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = SessionRepository.class)
    static class DatabaseConfig {
        @Bean String usageSchema() { return "qa_usage_" + UUID.randomUUID().toString().replace("-", ""); }
        @Bean DataSource dataSource() {
            String url = System.getenv("USAGE_PERSISTENCE_TEST_JDBC_URL");
            if (url == null || url.isBlank()) {
                url = "jdbc:h2:mem:usage_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
                return new DriverManagerDataSource(url, "sa", "");
            }
            return new DriverManagerDataSource(url,
                System.getenv("USAGE_PERSISTENCE_TEST_DB_USER"), System.getenv("USAGE_PERSISTENCE_TEST_DB_PASSWORD"));
        }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource, String usageSchema) {
            new JdbcTemplate(dataSource).execute("CREATE SCHEMA " + usageSchema);
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(SessionEntity.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create", "hibernate.default_schema", usageSchema,
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean UsagePersistenceService persistence(SessionRepository sessions, UsageRepository usage) { return new UsagePersistenceService(sessions, usage); }
    }

    @Test
    void deletionBeforeUsageCommitSkipsUsageInsteadOfFailingForeignKey() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(DatabaseConfig.class)) {
            var fixture = fixture(context);
            var deleteStarted = new CountDownLatch(1);
            var commitDelete = new CountDownLatch(1);
            try (var threads = Executors.newFixedThreadPool(2)) {
                var delete = threads.submit(() -> fixture.transactions.execute(status -> {
                    fixture.sessions.deleteById(fixture.id);
                    fixture.sessions.flush();
                    deleteStarted.countDown();
                    await(commitDelete);
                    return null;
                }));
                try {
                    assertTrue(deleteStarted.await(10, TimeUnit.SECONDS));
                    var recordStarted = new CountDownLatch(1);
                    var record = threads.submit(() -> {
                        recordStarted.countDown();
                        return fixture.persistence.saveForExistingSession(usage(fixture.id));
                    });
                    assertTrue(recordStarted.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> record.get(150, TimeUnit.MILLISECONDS));
                    commitDelete.countDown();
                    delete.get(10, TimeUnit.SECONDS);
                    assertFalse(record.get(10, TimeUnit.SECONDS));
                    assertEquals(0, fixture.usage.count());
                } finally { commitDelete.countDown(); }
            } finally { cleanupSchema(context); }
        }
    }

    @Test
    void usageBeforeDeleteKeepsParentLockedUntilCommitThenCascades() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(DatabaseConfig.class)) {
            var fixture = fixture(context);
            var usageSaved = new CountDownLatch(1);
            var commitUsage = new CountDownLatch(1);
            try (var threads = Executors.newFixedThreadPool(2)) {
                var record = threads.submit(() -> fixture.transactions.execute(status -> {
                    assertTrue(fixture.persistence.saveForExistingSession(usage(fixture.id)));
                    fixture.usage.flush();
                    usageSaved.countDown();
                    await(commitUsage);
                    return null;
                }));
                try {
                    assertTrue(usageSaved.await(10, TimeUnit.SECONDS));
                    var deleteStarted = new CountDownLatch(1);
                    var delete = threads.submit(() -> {
                        deleteStarted.countDown();
                        fixture.sessions.deleteById(fixture.id);
                    });
                    assertTrue(deleteStarted.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> delete.get(150, TimeUnit.MILLISECONDS));
                    commitUsage.countDown();
                    record.get(10, TimeUnit.SECONDS);
                    delete.get(10, TimeUnit.SECONDS);
                    assertEquals(0, fixture.usage.count());
                    assertFalse(fixture.sessions.existsById(fixture.id));
                } finally { commitUsage.countDown(); }
            } finally { cleanupSchema(context); }
        }
    }

    private static Fixture fixture(AnnotationConfigApplicationContext context) {
        String schema = context.getBean("usageSchema", String.class);
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.execute("ALTER TABLE " + schema + ".usage_log ADD CONSTRAINT fk_usage_log_session FOREIGN KEY (session_id) REFERENCES " + schema + ".sessions(id) ON DELETE CASCADE");
        var sessions = context.getBean(SessionRepository.class);
        var session = new SessionEntity();
        session.setUserId("user-1"); session.setModelProvider("noop"); session.setModelName("qa");
        UUID id = sessions.saveAndFlush(session).getId();
        return new Fixture(id, sessions, context.getBean(UsageRepository.class), context.getBean(UsagePersistenceService.class), new TransactionTemplate(context.getBean(PlatformTransactionManager.class)));
    }
    private static UsageEntity usage(UUID id) {
        var usage = new UsageEntity(); usage.setSessionId(id); usage.setUserId("user-1"); usage.setModel("qa");
        return usage;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
    }
    private static void cleanupSchema(AnnotationConfigApplicationContext context) {
        // Only this test's generated schema; never the caller's existing tables.
        new JdbcTemplate(context.getBean(DataSource.class)).execute("DROP SCHEMA " + context.getBean("usageSchema", String.class) + " CASCADE");
    }
    private record Fixture(UUID id, SessionRepository sessions, UsageRepository usage, UsagePersistenceService persistence, TransactionTemplate transactions) {}
}
