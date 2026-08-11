package com.azhukov.agent.tools.cron;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.CronJobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Branch coverage tests for {@link CronJobTool}.
 * Covers error paths, null inputs, exception handling, and untested branches.
 */
@ExtendWith(MockitoExtension.class)
class CronJobToolBranchTest {

    @Mock private CronJobService cronJobService;

    private Session session() {
        return Session.create("user-1", "openai", "gpt-4");
    }

    private Message assistant() {
        return Message.assistant("test", 0);
    }

    // ── Unknown action ──

    @Test
    void unknownActionReturnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"unknown\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown cron action");
        assertThat(result.error()).contains("unknown");
    }

    @Test
    void nullActionReturnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":null}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Unknown cron action");
    }

    @Test
    void uppercaseActionIsLowercased() {
        CronJobTool tool = new CronJobTool(cronJobService);
        when(cronJobService.list()).thenReturn(List.of());
        ToolResult result = tool.execute("{\"action\":\"LIST\"}", assistant(), session());
        assertThat(result.success()).isTrue();
    }

    // ── create job: validation branches ──

    @Test
    void createJob_nullName_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"create\",\"schedule\":\"0 9 * * *\",\"prompt\":\"test\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("name is required");
    }

    @Test
    void createJob_blankName_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"create\",\"name\":\"\",\"schedule\":\"0 9 * * *\",\"prompt\":\"test\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("name is required");
    }

    @Test
    void createJob_nullSchedule_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"create\",\"name\":\"job1\",\"prompt\":\"test\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("schedule is required");
    }

    @Test
    void createJob_blankSchedule_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"create\",\"name\":\"job1\",\"schedule\":\"\",\"prompt\":\"test\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("schedule is required");
    }

    @Test
    void createJob_nullPrompt_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"create\",\"name\":\"job1\",\"schedule\":\"0 9 * * *\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("prompt is required");
    }

    @Test
    void createJob_blankPrompt_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"create\",\"name\":\"job1\",\"schedule\":\"0 9 * * *\",\"prompt\":\"\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("prompt is required");
    }

    @Test
    void createJob_serviceThrows_returnsFail() {
        when(cronJobService.create(any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("DB error"));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute(
            "{\"action\":\"create\",\"name\":\"job1\",\"schedule\":\"0 9 * * *\",\"prompt\":\"test\"}",
            assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Failed to create cron job");
        assertThat(result.error()).contains("DB error");
    }

    // ── list job: exception path ──

    @Test
    void listJobs_serviceThrows_returnsFail() {
        when(cronJobService.list()).thenThrow(new RuntimeException("DB error"));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"list\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Failed to list cron jobs");
    }

    @Test
    void listJobs_emptyList_returnsOk() {
        when(cronJobService.list()).thenReturn(List.of());
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"list\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Cron jobs (0)");
    }

    @Test
    void listJobs_multipleJobs_allFormatted() {
        CronJobEntity e1 = new CronJobEntity();
        e1.setId(UUID.randomUUID());
        e1.setName("job1");
        e1.setSchedule("0 9 * * *");
        e1.setEnabled(true);
        e1.setLastRunAt(Instant.now());
        e1.setNextRunAt(Instant.now().plusSeconds(3600));

        CronJobEntity e2 = new CronJobEntity();
        e2.setId(UUID.randomUUID());
        e2.setName("job2");
        e2.setSchedule("0 18 * * *");
        e2.setEnabled(false);

        when(cronJobService.list()).thenReturn(List.of(e1, e2));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"list\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("job1");
        assertThat(result.content()).contains("job2");
        assertThat(result.content()).contains("Cron jobs (2)");
    }

    // ── pause job: validation and exception branches ──

    @Test
    void pauseJob_nullName_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"pause\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("name is required");
    }

    @Test
    void pauseJob_notFound_returnsFail() {
        when(cronJobService.findByName("missing")).thenReturn(Optional.empty());
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"pause\",\"name\":\"missing\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Cron job not found: missing");
    }

    @Test
    void pauseJob_serviceThrows_returnsFail() {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("job1");
        when(cronJobService.findByName("job1")).thenReturn(Optional.of(entity));
        when(cronJobService.pause(entity.getId())).thenThrow(new RuntimeException("DB error"));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"pause\",\"name\":\"job1\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Failed to pause cron job");
    }

    // ── resume job: validation and exception branches ──

    @Test
    void resumeJob_nullName_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"resume\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("name is required");
    }

    @Test
    void resumeJob_notFound_returnsFail() {
        when(cronJobService.findByName("missing")).thenReturn(Optional.empty());
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"resume\",\"name\":\"missing\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Cron job not found: missing");
    }

    @Test
    void resumeJob_success() {
        UUID id = UUID.randomUUID();
        CronJobEntity entity = new CronJobEntity();
        entity.setId(id);
        entity.setName("resume-me");
        entity.setEnabled(false);
        when(cronJobService.findByName("resume-me")).thenReturn(Optional.of(entity));
        when(cronJobService.resume(id)).thenAnswer(inv -> {
            entity.setEnabled(true);
            return entity;
        });
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"resume\",\"name\":\"resume-me\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Resumed");
    }

    @Test
    void resumeJob_serviceThrows_returnsFail() {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("job1");
        when(cronJobService.findByName("job1")).thenReturn(Optional.of(entity));
        when(cronJobService.resume(entity.getId())).thenThrow(new RuntimeException("DB error"));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"resume\",\"name\":\"job1\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Failed to resume cron job");
    }

    // ── remove job: validation and exception branches ──

    @Test
    void removeJob_nullName_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"remove\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("name is required");
    }

    @Test
    void removeJob_notFound_returnsFail() {
        when(cronJobService.findByName("missing")).thenReturn(Optional.empty());
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"remove\",\"name\":\"missing\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Cron job not found: missing");
    }

    @Test
    void removeJob_success() {
        UUID id = UUID.randomUUID();
        CronJobEntity entity = new CronJobEntity();
        entity.setId(id);
        entity.setName("remove-me");
        when(cronJobService.findByName("remove-me")).thenReturn(Optional.of(entity));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"remove\",\"name\":\"remove-me\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Removed cron job: remove-me");
    }

    @Test
    void removeJob_serviceThrows_returnsFail() {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("job1");
        when(cronJobService.findByName("job1")).thenReturn(Optional.of(entity));
        org.mockito.Mockito.doThrow(new RuntimeException("DB error"))
            .when(cronJobService).remove(entity.getId());
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"remove\",\"name\":\"job1\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Failed to remove cron job");
    }

    // ── run job: validation and exception branches ──

    @Test
    void runJob_nullName_returnsFail() {
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"run\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("name is required");
    }

    @Test
    void runJob_notFound_returnsFail() {
        when(cronJobService.findByName("missing")).thenReturn(Optional.empty());
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"run\",\"name\":\"missing\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Cron job not found: missing");
    }

    @Test
    void runJob_serviceThrows_returnsFail() {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("job1");
        when(cronJobService.findByName("job1")).thenReturn(Optional.of(entity));
        when(cronJobService.runNow(entity.getId())).thenThrow(new RuntimeException("DB error"));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"run\",\"name\":\"job1\"}", assistant(), session());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Failed to run cron job");
    }

    // ── create with deliverTo and skills ──

    @Test
    void createJob_withDeliverToAndSkills() {
        when(cronJobService.create(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            CronJobEntity e = new CronJobEntity();
            e.setId(UUID.randomUUID());
            e.setName(inv.getArgument(0));
            e.setSchedule(inv.getArgument(1));
            e.setPrompt(inv.getArgument(2));
            e.setEnabled(true);
            return e;
        });
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute(
            "{\"action\":\"create\",\"name\":\"daily-report\",\"schedule\":\"0 9 * * *\",\"prompt\":\"Generate report\",\"deliver_to\":\"telegram\",\"skills\":\"coding\"}",
            assistant(), session());
        assertThat(result.success()).isTrue();
        org.mockito.Mockito.verify(cronJobService).create("daily-report", "0 9 * * *", "Generate report", "telegram", "coding");
    }

    // ── formatJob: entity with all fields set ──

    @Test
    void listJobs_formatJobWithNullFields() {
        CronJobEntity e = new CronJobEntity();
        e.setId(UUID.randomUUID());
        e.setName("job1");
        e.setSchedule("0 9 * * *");
        e.setEnabled(true);
        // lastRunAt and nextRunAt left null
        when(cronJobService.list()).thenReturn(List.of(e));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("{\"action\":\"list\"}", assistant(), session());
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("job1");
        assertThat(result.content()).contains("lastRun=null");
    }
}