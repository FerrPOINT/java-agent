package com.azhukov.agent.api;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.CronSuggestionService;
import com.azhukov.agent.service.HeartbeatService;
import com.azhukov.agent.service.CronBlueprintService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Blueprint endpoints (Hermes /blueprint parity): catalog lists typed slots;
 * create fills time-slot blueprints into VALID cron (the fillBlueprintWithTime
 * path decomposes 08:00 → {hour}/{minute}); unknown keys throw.
 */
class CronBlueprintEndpointsTest {

    private static CronJobController controller(CronJobService jobService) {
        return new CronJobController(
            jobService,
            Mockito.mock(CronSuggestionService.class),
            Mockito.mock(HeartbeatService.class),
            Mockito.mock(com.azhukov.agent.persistence.repository.CronExecutionLogRepository.class),
            org.mapstruct.factory.Mappers.getMapper(com.azhukov.agent.api.mapper.CronJobDtoMapper.class),
            new CronBlueprintService());
    }

    @Test
    void catalogListsBlueprintsWithSlots() {
        var out = controller(Mockito.mock(CronJobService.class)).listBlueprints();
        assertThat(out).containsKey("blueprints");
        @SuppressWarnings("unchecked")
        var list = (java.util.List<Map<String, Object>>) out.get("blueprints");
        assertThat(list).isNotEmpty();
        assertThat(list.get(0)).containsEntry("key", "morning-brief");
        assertThat((java.util.List<?>) list.get(0).get("slots")).isNotEmpty();
    }

    @Test
    void createFillsTimeSlotIntoValidCron() {
        CronJobService jobs = Mockito.mock(CronJobService.class);
        when(jobs.create(anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            CronJobEntity e = new CronJobEntity();
            e.setId(UUID.randomUUID());
            e.setName(inv.getArgument(0));
            e.setSchedule(inv.getArgument(1));
            e.setPrompt(inv.getArgument(2));
            return e;
        });
        var dto = controller(jobs).createFromBlueprint("morning-brief",
            new CronJobController.BlueprintFillRequest(Map.of("time", "07:30", "deliver", "telegram")));
        // 07:30 decomposes into "30 7 * * *" — a VALID cron, no raw placeholders
        assertThat(dto.schedule()).isEqualTo("30 7 * * *");
        assertThat(dto.name()).startsWith("morning-brief-");
    }

    @Test
    void defaultsProduceValidCronToo() {
        CronJobService jobs = Mockito.mock(CronJobService.class);
        when(jobs.create(anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
            CronJobEntity e = new CronJobEntity();
            e.setId(UUID.randomUUID());
            e.setName(inv.getArgument(0));
            e.setSchedule(inv.getArgument(1));
            e.setPrompt(inv.getArgument(2));
            return e;
        });
        var dto = controller(jobs).createFromBlueprint("morning-brief", null);
        assertThat(dto.schedule()).matches("\\d+ \\d+ \\* \\* \\*");
    }

    @Test
    void unknownBlueprintKeyIsRejected() {
        assertThatThrownBy(() -> controller(Mockito.mock(CronJobService.class))
                .createFromBlueprint("no-such", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown blueprint");
    }
}
