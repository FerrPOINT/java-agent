package com.azhukov.agent.api.mapper;

import com.azhukov.agent.api.dto.CronExecutionLogDto;
import com.azhukov.agent.api.dto.CronJobDto;
import com.azhukov.agent.persistence.entity.CronExecutionLogEntity;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CronJobDtoMapperTest {

    private final CronJobDtoMapper mapper = Mappers.getMapper(CronJobDtoMapper.class);

    @Test
    void toDtoMapsAllFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        CronJobEntity entity = new CronJobEntity();
        entity.setId(id);
        entity.setName("job1");
        entity.setSchedule("0 * * * *");
        entity.setPrompt("prompt");
        entity.setEnabled(true);
        entity.setDeliverTo("telegram");
        entity.setSkills("skill-a,skill-b");
        entity.setContextFrom("upstream-id");
        entity.setRepeatCount(5);
        entity.setRepeatCompleted(2);
        entity.setScript("echo hi");
        entity.setNoAgent(false);
        entity.setEnabledToolsets("terminal");
        entity.setWorkdir("/tmp");
        entity.setModelProvider("openai-compatible");
        entity.setModelName("gpt-4");
        entity.setBaseUrl("http://localhost");
        entity.setCreatedAt(now);
        entity.setLastRunAt(now);
        entity.setNextRunAt(now);
        entity.setLastStatus("success");
        entity.setLastError(null);
        entity.setLastErrorAt(null);
        entity.setConsecutiveFailures(3);

        CronJobDto dto = mapper.toDto(entity);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.name()).isEqualTo("job1");
        assertThat(dto.schedule()).isEqualTo("0 * * * *");
        assertThat(dto.prompt()).isEqualTo("prompt");
        assertThat(dto.enabled()).isTrue();
        assertThat(dto.deliverTo()).isEqualTo("telegram");
        assertThat(dto.skills()).isEqualTo("skill-a,skill-b");
        assertThat(dto.contextFrom()).isEqualTo("upstream-id");
        assertThat(dto.repeatCount()).isEqualTo(5);
        assertThat(dto.repeatCompleted()).isEqualTo(2);
        assertThat(dto.script()).isEqualTo("echo hi");
        assertThat(dto.noAgent()).isFalse();
        assertThat(dto.enabledToolsets()).isEqualTo("terminal");
        assertThat(dto.workdir()).isEqualTo("/tmp");
        assertThat(dto.modelProvider()).isEqualTo("openai-compatible");
        assertThat(dto.modelName()).isEqualTo("gpt-4");
        assertThat(dto.baseUrl()).isEqualTo("http://localhost");
        assertThat(dto.createdAt()).isEqualTo(now);
        assertThat(dto.lastRunAt()).isEqualTo(now);
        assertThat(dto.nextRunAt()).isEqualTo(now);
        assertThat(dto.lastStatus()).isEqualTo("success");
        assertThat(dto.lastError()).isNull();
        assertThat(dto.lastErrorAt()).isNull();
        assertThat(dto.consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void toDtoHandlesNull() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void toDtoListMapsAll() {
        CronJobEntity e1 = new CronJobEntity();
        e1.setId(UUID.randomUUID());
        e1.setName("a");
        CronJobEntity e2 = new CronJobEntity();
        e2.setId(UUID.randomUUID());
        e2.setName("b");

        List<CronJobDto> dtos = mapper.toDtoList(List.of(e1, e2));

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).name()).isEqualTo("a");
        assertThat(dtos.get(1).name()).isEqualTo("b");
    }

    @Test
    void toDtoListHandlesNull() {
        assertThat(mapper.toDtoList(null)).isNull();
    }

    @Test
    void toExecutionLogDtoMapsAllFields() {
        UUID jobId = UUID.randomUUID();
        Instant started = Instant.now();
        Instant finished = started.plusSeconds(10);
        Instant created = started;
        CronExecutionLogEntity entity = CronExecutionLogEntity.create(jobId, started, finished, "success", null);
        entity.setId(1L);
        entity.setCreatedAt(created);

        CronExecutionLogDto dto = mapper.toExecutionLogDto(entity);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.jobId()).isEqualTo(jobId);
        assertThat(dto.startedAt()).isEqualTo(started);
        assertThat(dto.finishedAt()).isEqualTo(finished);
        assertThat(dto.status()).isEqualTo("success");
        assertThat(dto.errorMessage()).isNull();
        assertThat(dto.createdAt()).isEqualTo(created);
    }

    @Test
    void toExecutionLogDtoHandlesNull() {
        assertThat(mapper.toExecutionLogDto(null)).isNull();
    }

    @Test
    void toExecutionLogDtoListMapsAll() {
        UUID jobId = UUID.randomUUID();
        CronExecutionLogEntity e1 = CronExecutionLogEntity.create(jobId, Instant.now(), Instant.now(), "success", null);
        e1.setId(1L);
        CronExecutionLogEntity e2 = CronExecutionLogEntity.create(jobId, Instant.now(), Instant.now(), "failure", "boom");
        e2.setId(2L);

        List<CronExecutionLogDto> dtos = mapper.toExecutionLogDtoList(List.of(e1, e2));

        assertThat(dtos).hasSize(2);
        assertThat(dtos.get(0).status()).isEqualTo("success");
        assertThat(dtos.get(1).status()).isEqualTo("failure");
        assertThat(dtos.get(1).errorMessage()).isEqualTo("boom");
    }
}