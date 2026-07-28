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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CronJobToolTest {

    @Mock private CronJobService cronJobService;

    @Test
    void createJob() {
        when(cronJobService.create(any(), any(), any(), any())).thenAnswer(inv -> {
            CronJobEntity e = new CronJobEntity();
            e.setId(UUID.randomUUID());
            e.setName(inv.getArgument(0));
            e.setSchedule(inv.getArgument(1));
            e.setPrompt(inv.getArgument(2));
            e.setEnabled(true);
            return e;
        });
        CronJobTool tool = new CronJobTool(cronJobService);
        String args = """
            {"action":"create","name":"daily-report","schedule":"0 9 * * *","prompt":"Generate report"}
            """;
        ToolResult result = tool.execute(args, null, null);
        assertThat(result.success()).isTrue();
        verify(cronJobService).create("daily-report", "0 9 * * *", "Generate report", null);
    }

    @Test
    void listJobs() {
        CronJobEntity e = new CronJobEntity();
        e.setId(UUID.randomUUID());
        e.setName("job1");
        e.setSchedule("0 * * * *");
        when(cronJobService.list()).thenReturn(List.of(e));
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("""
            {"action":"list"}
            """, null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("job1");
    }

    @Test
    void pauseJob() {
        UUID id = UUID.randomUUID();
        CronJobEntity e = new CronJobEntity();
        e.setId(id);
        e.setName("pause-me");
        when(cronJobService.findByName("pause-me")).thenReturn(Optional.of(e));
        when(cronJobService.pause(id)).thenAnswer(inv -> {
            e.setEnabled(false);
            return e;
        });
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("""
            {"action":"pause","name":"pause-me"}
            """, null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Paused");
    }

    @Test
    void runJob() {
        UUID id = UUID.randomUUID();
        CronJobEntity e = new CronJobEntity();
        e.setId(id);
        e.setName("run-me");
        when(cronJobService.findByName("run-me")).thenReturn(Optional.of(e));
        when(cronJobService.runNow(id)).thenReturn(e);
        CronJobTool tool = new CronJobTool(cronJobService);
        ToolResult result = tool.execute("""
            {"action":"run","name":"run-me"}
            """, null, null);
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Triggered");
    }
}