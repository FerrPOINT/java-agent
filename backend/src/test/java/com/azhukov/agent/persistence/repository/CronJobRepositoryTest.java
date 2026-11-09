package com.azhukov.agent.persistence.repository;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.CronScheduleParser;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import com.azhukov.agent.persistence.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("slow")
@Transactional
class CronJobRepositoryTest extends PostgresTestContainer {

    private static final Instant T1 = Instant.parse("2026-01-01T12:00:00Z");
    private static final String NAME_1 = "daily-summary";
    private static final String NAME_2 = "weekly-report";
    private static final String SCHEDULE_1 = "0 0 9 * * ?";
    private static final String SCHEDULE_2 = "0 0 10 * * MON";
    private static final String PROMPT_1 = "Generate daily summary";
    private static final String PROMPT_2 = "Generate weekly report";
    private static final String DELIVER_TO = "telegram";

    @Autowired
    private CronJobRepository cronJobRepository;

    @Autowired private TransactionTemplate transactions;
    @Autowired private ApplicationContext context;
    @Autowired private CronExecutionLogRepository executionLogs;
    @Autowired private MessageRepository messages;
    @Autowired private CronScheduleParser scheduleParser;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void startupDisablesInvalidScheduleAndKeepsValidDeadline() {
        var invalid = newCronJob("invalid", "0 0 31 2 *", PROMPT_1, true);
        var valid = newCronJob("valid", "0 9 * * *", PROMPT_2, true);
        Instant due = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        invalid.setNextRunAt(due);
        valid.setNextRunAt(due);
        invalid = cronJobRepository.save(invalid);
        valid = cronJobRepository.save(valid);
        var properties = new AgentProperties();
        properties.getCron().setEnabled(true);
        var service = new CronJobService(cronJobRepository, context.getBeanProvider(AgentRuntimeService.class),
            properties, org.mockito.Mockito.mock(com.azhukov.agent.core.skill.SkillManager.class),
            executionLogs, messages, transactions, scheduleParser);
        try {
            service.init();
            var disabled = cronJobRepository.findById(invalid.getId()).orElseThrow();
            assertThat(disabled.isEnabled()).isFalse();
            assertThat(disabled.getNextRunAt()).isNull();
            assertThat(disabled.getLastError()).contains("no next execution");
            assertThat(cronJobRepository.findById(valid.getId()).orElseThrow().getNextRunAt()).isEqualTo(due);

            UUID id = disabled.getId();
            transactions.executeWithoutResult(status -> service.update(id, null, "0 9 * * *", null, null, null));
            transactions.executeWithoutResult(status -> service.resume(id));
            var resumed = cronJobRepository.findById(id).orElseThrow();
            assertThat(resumed.isEnabled()).isTrue();
            assertThat(resumed.getNextRunAt()).isAfter(Instant.now());
        } finally {
            service.shutdown();
            cronJobRepository.deleteAllById(List.of(invalid.getId(), valid.getId()));
        }
    }

    @Test
    void saveAndFindById() {
        CronJobEntity entity = newCronJob(NAME_1, SCHEDULE_1, PROMPT_1, true);

        CronJobEntity saved = cronJobRepository.save(entity);
        UUID generatedId = saved.getId();

        assertThat(generatedId).isNotNull();

        CronJobEntity found = cronJobRepository.findById(generatedId).orElse(null);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(generatedId);
        assertThat(found.getName()).isEqualTo(NAME_1);
        assertThat(found.getSchedule()).isEqualTo(SCHEDULE_1);
        assertThat(found.getPrompt()).isEqualTo(PROMPT_1);
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getDeliverTo()).isEqualTo(DELIVER_TO);
    }

    @Test
    void findByNameReturnsJob() {
        cronJobRepository.save(newCronJob(NAME_1, SCHEDULE_1, PROMPT_1, true));

        CronJobEntity found = cronJobRepository.findByName(NAME_1).orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo(NAME_1);
    }

    @Test
    void findByEnabledTrueReturnsOnlyEnabledJobs() {
        cronJobRepository.save(newCronJob(NAME_1, SCHEDULE_1, PROMPT_1, true));
        cronJobRepository.save(newCronJob(NAME_2, SCHEDULE_2, PROMPT_2, false));

        List<CronJobEntity> enabled = cronJobRepository.findByEnabledTrue();

        assertThat(enabled).hasSize(1);
        assertThat(enabled.get(0).getName()).isEqualTo(NAME_1);
    }

    @Test
    void findAllReturnsSavedEntities() {
        cronJobRepository.save(newCronJob(NAME_1, SCHEDULE_1, PROMPT_1, true));
        cronJobRepository.save(newCronJob(NAME_2, SCHEDULE_2, PROMPT_2, true));

        List<CronJobEntity> all = cronJobRepository.findAll();

        assertThat(all).hasSize(2);
        assertThat(all)
            .extracting(CronJobEntity::getName)
            .containsExactlyInAnyOrder(NAME_1, NAME_2);
    }

    @Test
    void findByUserIdDoesNotLeakOtherUsersJobs() {
        CronJobEntity alice = newCronJob(NAME_1, SCHEDULE_1, PROMPT_1, true);
        alice.setUserId("alice");
        CronJobEntity bob = newCronJob(NAME_2, SCHEDULE_2, PROMPT_2, true);
        bob.setUserId("bob");
        cronJobRepository.saveAll(List.of(alice, bob));

        assertThat(cronJobRepository.findByUserId("alice"))
            .extracting(CronJobEntity::getName)
            .containsExactly(NAME_1);
        assertThat(cronJobRepository.findByIdAndUserId(bob.getId(), "alice")).isEmpty();
        assertThat(cronJobRepository.findByIdAndUserId(bob.getId(), "bob")).isPresent();
    }

    @Test
    void deleteByIdRemovesEntity() {
        CronJobEntity saved = cronJobRepository.save(newCronJob(NAME_1, SCHEDULE_1, PROMPT_1, true));
        UUID id = saved.getId();

        assertThat(cronJobRepository.findById(id)).isPresent();

        cronJobRepository.deleteById(id);

        assertThat(cronJobRepository.findById(id)).isEmpty();
    }

    @Test
    void saveUpdatesExistingEntity() {
        CronJobEntity saved = cronJobRepository.save(newCronJob(NAME_1, SCHEDULE_1, PROMPT_1, true));
        UUID id = saved.getId();

        saved.setEnabled(false);
        cronJobRepository.save(saved);

        CronJobEntity found = cronJobRepository.findById(id).orElseThrow();
        assertThat(found.isEnabled()).isFalse();
    }

    private CronJobEntity newCronJob(String name, String schedule, String prompt, boolean enabled) {
        CronJobEntity entity = new CronJobEntity();
        entity.setName(name);
        entity.setSchedule(schedule);
        entity.setPrompt(prompt);
        entity.setEnabled(enabled);
        entity.setDeliverTo(DELIVER_TO);
        entity.setCreatedAt(T1);
        return entity;
    }
}