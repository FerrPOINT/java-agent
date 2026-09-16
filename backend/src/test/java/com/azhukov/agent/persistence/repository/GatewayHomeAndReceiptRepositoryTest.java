package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.GatewayHomeChannelEntity;
import com.azhukov.agent.persistence.entity.GatewayRuntimeStateEntity;
import com.azhukov.agent.persistence.entity.OutboundMessageReceiptEntity;
import com.azhukov.agent.service.GatewayHomeChannelService;
import com.azhukov.agent.service.OutboundReceiptService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V55 (WP-2 / ADR-012) schema + service behavior on real PostgreSQL:
 * gateway home-channel directory, outbound message receipts, gateway
 * runtime state.
 */
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
class GatewayHomeAndReceiptRepositoryTest extends PostgresTestContainer {

    @Autowired
    private GatewayHomeChannelRepository homeRepository;

    @Autowired
    private OutboundMessageReceiptRepository receiptRepository;

    @Autowired
    private GatewayRuntimeStateRepository stateRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String unique() {
        return String.valueOf(100_000_000 + UUID.randomUUID().hashCode() % 900_000_000);
    }

    @Test
    void homeChannelRowRoundTrips() {
        String chat = unique();
        GatewayHomeChannelEntity entity = new GatewayHomeChannelEntity();
        entity.setPlatform("telegram");
        entity.setProfile("default");
        entity.setChatId(chat);
        entity.setThreadId("42");
        entity.setName("Home");
        entity.setUpdatedAt(Instant.now());
        homeRepository.save(entity);

        Optional<GatewayHomeChannelEntity> loaded =
            homeRepository.findByPlatformAndProfile("telegram", "default");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getChatId()).isEqualTo(chat);
        assertThat(loaded.get().getThreadId()).isEqualTo("42");

        transactionTemplate.executeWithoutResult(tx ->
            homeRepository.deleteByPlatformAndProfile("telegram", "default"));
        assertThat(homeRepository.findByPlatformAndProfile("telegram", "default")).isEmpty();
    }

    @Test
    void receiptIdempotencyKeyRejectsDuplicates() {
        String key = "wp2-test-" + UUID.randomUUID();
        OutboundMessageReceiptEntity first = receipt(key, "m1");
        receiptRepository.save(first);

        OutboundMessageReceiptEntity duplicate = receipt(key, "m1");
        assertThatThrownBy(() -> receiptRepository.save(duplicate))
            .isInstanceOf(Exception.class);

        receiptRepository.delete(first);
    }

    @Test
    void lastMessageForTargetOrdersByCreatedAt() {
        String chat = unique();
        String oldKey = "wp2-old-" + UUID.randomUUID();
        String newKey = "wp2-new-" + UUID.randomUUID();
        OutboundMessageReceiptEntity older = receipt(oldKey, "100");
        older.setChatId(chat);
        older.setCreatedAt(Instant.now().minusSeconds(60));
        OutboundMessageReceiptEntity newer = receipt(newKey, "101");
        newer.setChatId(chat);
        newer.setCreatedAt(Instant.now());
        receiptRepository.saveAll(List.of(older, newer));

        Optional<OutboundMessageReceiptEntity> latest =
            receiptRepository.findFirstByPlatformAndChatIdOrderByCreatedAtDesc("telegram", chat);
        assertThat(latest).isPresent();
        assertThat(latest.get().getMessageId()).isEqualTo("101");

        receiptRepository.deleteAll(List.of(older, newer));
    }

    @Test
    void runtimeStateRoundTrips() {
        GatewayRuntimeStateEntity state = new GatewayRuntimeStateEntity();
        state.setProfile("default");
        state.setState("running");
        state.setUpdatedAt(Instant.now());
        stateRepository.save(state);

        Optional<GatewayRuntimeStateEntity> loaded = stateRepository.findById("default");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getState()).isEqualTo("running");

        stateRepository.delete(state);
    }

    @Test
    void concurrentSetHomeKeepsSingleRowPerPlatformProfile() throws Exception {
        String chatA = unique();
        String chatB = unique();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                final String chat = i % 2 == 0 ? chatA : chatB;
                pool.submit(() -> {
                    try {
                        start.await();
                        transactionTemplate.executeWithoutResult(tx -> {
                            GatewayHomeChannelEntity entity =
                                homeRepository.findByPlatformAndProfile("telegram", "default").orElseGet(() -> {
                                    GatewayHomeChannelEntity created = new GatewayHomeChannelEntity();
                                    created.setPlatform("telegram");
                                    created.setProfile("default");
                                    return created;
                                });
                            entity.setChatId(chat);
                            entity.setUpdatedAt(Instant.now());
                            homeRepository.save(entity);
                            wins.incrementAndGet();
                        });
                    } catch (Exception ignored) {
                        // serialization failures are acceptable; row count is the assertion
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.close();
        }

        long rows = homeRepository.findAll().stream()
            .filter(e -> "telegram".equals(e.getPlatform()) && "default".equals(e.getProfile()))
            .count();
        assertThat(rows).isLessThanOrEqualTo(1);
        transactionTemplate.executeWithoutResult(tx ->
            homeRepository.deleteByPlatformAndProfile("telegram", "default"));
    }

    @Test
    void receiptServiceRecordAndLookup() {
        OutboundReceiptService service = new OutboundReceiptService(receiptRepository);
        String chat = unique();
        Optional<OutboundMessageReceiptEntity> recorded = service.record(
            new OutboundReceiptService.Receipt("telegram", chat, null, UUID.randomUUID(), null, "55",
                "wp2-svc-" + UUID.randomUUID()));
        assertThat(recorded).isPresent();

        Optional<OutboundMessageReceiptEntity> latest = service.lastMessageFor("telegram", chat, null);
        assertThat(latest).isPresent();
        assertThat(latest.get().getMessageId()).isEqualTo("55");

        // Idempotent re-record with the same key returns the existing row.
        Optional<OutboundMessageReceiptEntity> again = service.record(
            new OutboundReceiptService.Receipt("telegram", chat, null, null, null, "55",
                recorded.get().getIdempotencyKey()));
        assertThat(again).isPresent();
        assertThat(again.get().getId()).isEqualTo(recorded.get().getId());
        assertThat(again.get().getMessageId()).isEqualTo("55");

        receiptRepository.delete(recorded.get());
    }

    private OutboundMessageReceiptEntity receipt(String key, String messageId) {
        OutboundMessageReceiptEntity entity = new OutboundMessageReceiptEntity();
        entity.setId(UUID.randomUUID());
        entity.setPlatform("telegram");
        entity.setChatId(unique());
        entity.setDirection("outbound");
        entity.setMessageId(messageId);
        entity.setIdempotencyKey(key);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
