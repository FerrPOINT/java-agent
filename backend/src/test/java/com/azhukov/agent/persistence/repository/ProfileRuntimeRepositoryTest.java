package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.persistence.PostgresTestContainer;
import com.azhukov.agent.persistence.entity.DashboardActionEntity;
import com.azhukov.agent.persistence.entity.ProfileConfigRevisionEntity;
import com.azhukov.agent.persistence.entity.ProfileRuntimeStateEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-4 (V56) PostgreSQL slowTest: runtime state uniqueness per profile,
 * append-only revision ordering, action ledger lifecycle.
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
})
class ProfileRuntimeRepositoryTest extends PostgresTestContainer {

    @Autowired
    private ProfileRuntimeStateRepository runtimeStateRepository;

    @Autowired
    private ProfileConfigRevisionRepository revisionRepository;

    @Autowired
    private DashboardActionRepository actionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private ProfileRuntimeStateEntity runtimeRow(String profile) {
        ProfileRuntimeStateEntity entity = new ProfileRuntimeStateEntity();
        entity.setProfile(profile);
        return entity;
    }

    @Test
    void runtimeStateIsUniquePerProfile() {
        transactionTemplate.executeWithoutResult(tx -> {
            runtimeStateRepository.deleteAll();
            runtimeStateRepository.save(runtimeRow("wp4-unique"));
        });
        // Second row for the same profile must be rejected by the unique
        // constraint. The constraint fires at flush; catching inside the
        // template would mark the tx rollback-only, so assert from outside.
        assertThatThrownBy(() -> transactionTemplate.execute(tx ->
                runtimeStateRepository.saveAndFlush(runtimeRow("wp4-unique"))))
            .isInstanceOf(Exception.class);
        transactionTemplate.executeWithoutResult(tx -> runtimeStateRepository.deleteAll());
    }

    @Test
    void workerStateUpdateQueryTouchesOnlyTargetProfile() {
        transactionTemplate.executeWithoutResult(tx -> {
            runtimeStateRepository.deleteAll();
            runtimeStateRepository.save(runtimeRow("wp4-a"));
            runtimeStateRepository.save(runtimeRow("wp4-b"));
        });

        int updated = transactionTemplate.execute(tx ->
            runtimeStateRepository.updateWorkerState("wp4-a", "running", Instant.now()));

        assertThat(updated).isEqualTo(1);
        assertThat(runtimeStateRepository.findByProfile("wp4-a").orElseThrow().getWorkerState())
            .isEqualTo("running");
        assertThat(runtimeStateRepository.findByProfile("wp4-b").orElseThrow().getWorkerState())
            .isEqualTo("stopped");
        transactionTemplate.executeWithoutResult(tx -> runtimeStateRepository.deleteAll());
    }

    @Test
    void revisionsAreAppendOnlyWithMonotonicOrdering() {
        transactionTemplate.executeWithoutResult(tx -> {
            revisionRepository.deleteAll();
            for (long revision = 1; revision <= 3; revision++) {
                ProfileConfigRevisionEntity entity = new ProfileConfigRevisionEntity();
                entity.setProfile("wp4-rev");
                entity.setRevision(revision);
                entity.setActor("test");
                entity.setSummaryHash("hash-" + revision);
                entity.setStatus("applied");
                revisionRepository.save(entity);
            }
        });

        List<ProfileConfigRevisionEntity> latest =
            revisionRepository.findByProfileOrderByRevisionDesc("wp4-rev", PageRequest.of(0, 1));
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).getRevision()).isEqualTo(3L);

        var newest = revisionRepository.findFirstByProfileOrderByRevisionDesc("wp4-rev");
        assertThat(newest).isPresent();
        assertThat(newest.get().getRevision()).isEqualTo(3L);
        transactionTemplate.executeWithoutResult(tx -> revisionRepository.deleteAll());
    }

    @Test
    void actionLedgerLifecyclePersistsStateTransitions() {
        DashboardActionEntity saved = transactionTemplate.execute(tx -> {
            DashboardActionEntity entity = new DashboardActionEntity();
            entity.setAction("doctor");
            entity.setProfile("default");
            entity.setState("running");
            entity.setStartedAt(Instant.now());
            return actionRepository.save(entity);
        });

        transactionTemplate.executeWithoutResult(tx -> {
            DashboardActionEntity loaded = actionRepository.findById(saved.getId()).orElseThrow();
            loaded.setState("completed");
            loaded.setFinishedAt(Instant.now());
            loaded.setOutputPath("/tmp/artifact.json");
            loaded.setOutputSha256("abc123");
            actionRepository.save(loaded);
        });

        DashboardActionEntity loaded = actionRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getState()).isEqualTo("completed");
        assertThat(loaded.getOutputPath()).isEqualTo("/tmp/artifact.json");
        assertThat(actionRepository.findFirstByActionOrderByRequestedAtDesc("doctor")).isPresent();
        transactionTemplate.executeWithoutResult(tx -> actionRepository.deleteById(saved.getId()));
    }
}
