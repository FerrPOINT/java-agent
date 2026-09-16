package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.DeliveryWorkItemEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class DeliveryWorkItemRepositoryTest extends PostgresTestContainer {

    @Autowired
    private DeliveryWorkItemRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void duplicateSourceAndTargetIsRejectedByUniqueConstraint() {
        DeliveryWorkItemEntity first = pendingItem("default", "cron_execution", "job-1", "telegram:100");
        repository.saveAndFlush(first);

        DeliveryWorkItemEntity duplicate = pendingItem("default", "cron_execution", "job-1", "telegram:100");
        duplicate.setTargetHash(first.getTargetHash());

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
            .hasStackTraceContaining("uq_delivery_work_items_source_target");
    }

    @Test
    void claimPendingIsAtomicOnlyFirstCallerWins() {
        DeliveryWorkItemEntity item = repository.saveAndFlush(
            pendingItem("default", "cron_execution", "job-2", "telegram:100"));
        Instant now = Instant.now();

        int firstClaim = repository.claimPending(item.getId(), "consumer-A:" + UUID.randomUUID(), now);
        int secondClaim = repository.claimPending(item.getId(), "consumer-B:" + UUID.randomUUID(), now);

        assertThat(firstClaim).isEqualTo(1);
        assertThat(secondClaim).isZero();

        DeliveryWorkItemEntity claimed = repository.findById(item.getId()).orElseThrow();
        assertThat(claimed.getState()).isEqualTo("claimed");
        assertThat(claimed.getAttempts()).isEqualTo(1);
        assertThat(claimed.getClaimToken()).startsWith("consumer-A:");
    }

    @Test
    void concurrentClaimRacesProduceExactlyOneWinner() throws Exception {
        DeliveryWorkItemEntity item = repository.saveAndFlush(
            pendingItem("default", "cron_execution", "job-race", "telegram:100"));
        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                final String consumer = "consumer-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    Integer updated = transactionTemplate.execute(status ->
                        repository.claimPending(item.getId(), consumer + ":" + UUID.randomUUID(), Instant.now()));
                    return updated == null ? 0 : updated;
                }));
                if (i == contenders - 1) {
                    start.countDown();
                }
            }
            for (Future<Integer> future : futures) {
                wins.addAndGet(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(wins.get()).isEqualTo(1);
            assertThat(repository.findById(item.getId()).orElseThrow().getAttempts()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void terminalTransitionsRequireMatchingClaimToken() {
        DeliveryWorkItemEntity item = repository.saveAndFlush(
            pendingItem("default", "delegated_task_run", "run-1", "telegram:100"));
        Instant now = Instant.now();
        repository.claimPending(item.getId(), "owner-token", now);

        // Foreign token cannot deliver, fail, or terminalize the claim.
        assertThat(repository.markDelivered(item.getId(), "attacker-token", now, "m-1", "idem-1")).isZero();
        assertThat(repository.releaseKnownFailure(item.getId(), "attacker-token", now.plusSeconds(5), "err", "d")).isZero();
        assertThat(repository.markTerminal(item.getId(), "attacker-token", "unknown", now, "err", "d")).isZero();

        // Owner token terminalizes exactly once.
        assertThat(repository.markTerminal(item.getId(), "owner-token", "unknown", now, "ambiguous", null)).isEqualTo(1);
        assertThat(repository.markTerminal(item.getId(), "owner-token", "unknown", now, "ambiguous", null)).isZero();

        DeliveryWorkItemEntity terminal = repository.findById(item.getId()).orElseThrow();
        assertThat(terminal.getState()).isEqualTo("unknown");
        assertThat(terminal.getUnknownAt()).isNotNull();
        assertThat(terminal.getClaimToken()).isNull();
    }

    @Test
    void deliveredClearsClaimAndRecordsReceiptFields() {
        DeliveryWorkItemEntity item = repository.saveAndFlush(
            pendingItem("default", "delegated_task_run", "run-2", "telegram:100"));
        repository.claimPending(item.getId(), "owner-token", Instant.now());

        assertThat(repository.markDelivered(item.getId(), "owner-token", Instant.now(), "msg-77", "idem-77")).isEqualTo(1);

        DeliveryWorkItemEntity delivered = repository.findById(item.getId()).orElseThrow();
        assertThat(delivered.getState()).isEqualTo("delivered");
        assertThat(delivered.getDeliveredAt()).isNotNull();
        assertThat(delivered.getOutboundMessageId()).isEqualTo("msg-77");
        assertThat(delivered.getIdempotencyKey()).isEqualTo("idem-77");
        assertThat(delivered.getClaimToken()).isNull();
    }

    @Test
    void findClaimableFiltersByProfileAndAvailability() {
        repository.saveAndFlush(pendingItem("alpha", "cron_execution", "job-a", "telegram:1"));
        DeliveryWorkItemEntity beta = repository.saveAndFlush(
            pendingItem("beta", "cron_execution", "job-b", "telegram:2"));
        DeliveryWorkItemEntity delayed = pendingItem("beta", "cron_execution", "job-c", "telegram:3");
        delayed.setAvailableAt(Instant.now().plusSeconds(600));
        repository.saveAndFlush(delayed);

        var page = org.springframework.data.domain.PageRequest.of(0, 10);
        List<DeliveryWorkItemEntity> claimable =
            repository.findClaimable(List.of("beta"), Instant.now(), page);

        assertThat(claimable)
            .extracting(DeliveryWorkItemEntity::getSourceId)
            .containsExactly("job-b");
        assertThat(beta.getProfile()).isEqualTo("beta");
    }

    @Test
    void localTargetConstraintKeepsPlatformFieldsNull() {
        DeliveryWorkItemEntity local = pendingItem("default", "delegated_task_run", "run-local", "local");
        local.setTargetKind("local");
        local.setPlatform(null);
        local.setChatId(null);
        local.setThreadId(null);
        local.setTargetHash("local");
        repository.saveAndFlush(local);

        DeliveryWorkItemEntity invalid = pendingItem("default", "delegated_task_run", "run-invalid", "local");
        invalid.setTargetKind("local");
        invalid.setPlatform("telegram");

        assertThatThrownBy(() -> repository.saveAndFlush(invalid))
            .hasStackTraceContaining("ck_delivery_work_items_target");
    }

    private DeliveryWorkItemEntity pendingItem(String profile, String sourceType, String sourceId, String target) {
        DeliveryWorkItemEntity entity = new DeliveryWorkItemEntity();
        entity.setId(UUID.randomUUID());
        entity.setSourceType(sourceType);
        entity.setSourceId(sourceId);
        entity.setProfile(profile);
        entity.setTargetKind("platform");
        if (target.startsWith("telegram")) {
            entity.setPlatform("telegram");
            entity.setChatId(target.substring(target.indexOf(':') + 1));
        }
        entity.setTargetHash("hash:" + sourceId + ":" + target);
        entity.setPayloadText("payload for " + sourceId);
        entity.setPayloadHash("phash-" + sourceId);
        entity.setState("pending");
        entity.setAttempts(0);
        entity.setAvailableAt(Instant.now().minusSeconds(30));
        entity.setCreatedAt(Instant.now().minusSeconds(60));
        return entity;
    }
}
