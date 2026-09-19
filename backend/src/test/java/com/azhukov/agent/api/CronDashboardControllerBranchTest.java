package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.CronBlueprintService;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Branch coverage for CronDashboardController mutation endpoints
 * (create/update across all profile/extras combinations, lifecycle, blueprints).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CronDashboardControllerBranchTest {

    private static final UUID JOB_ID = UUID.fromString("5b418d50-f2d9-437f-81f9-ddd138c88b13");

    @TempDir
    private Path tempDir;

    @Mock
    private CronJobService cronJobService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        Files.createDirectories(tempDir.resolve("profiles").resolve("work"));
        ProfileService profileService = new ProfileService(properties, new RuntimeConfigService());
        HermesCronJobsController jobsController = new HermesCronJobsController(cronJobService, profileService);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CronDashboardController(jobsController, cronJobService, new CronBlueprintService(), profileService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static CronJobEntity job(String name) {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(JOB_ID);
        entity.setName(name);
        entity.setSchedule("every 1h");
        entity.setPrompt("Do work");
        entity.setEnabled(true);
        return entity;
    }

    @Test
    void createWithHermesExtrasAndProfileUsesCreateInProfile() throws Exception {
        CronJobEntity entity = job("monitor-job");
        entity.setProfile("work");
        when(cronJobService.createInProfile(eq("work"), any(), any(), any(), any(), any(), any(), any(),
            any(), anyBoolean(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(entity);

        mockMvc.perform(post("/p/work/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"monitor-job\",\"schedule\":\"every 1h\",\"prompt\":\"Check\",\"continuity\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile").value("work"));

        verify(cronJobService).createInProfile(
            eq("work"), any(), any(), any(), any(), any(), any(), any(),
            any(), anyBoolean(), any(), any(), any(), any(), any(), any(),
            anyBoolean(), any());
    }

    @Test
    void createWithHermesExtrasWithoutProfileUsesCreate() throws Exception {
        when(cronJobService.create(any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(job("monitor-job"));

        mockMvc.perform(post("/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"monitor-job\",\"schedule\":\"every 1h\",\"prompt\":\"Check\",\"continuity\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("monitor-job"));

        verify(cronJobService).create(any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void createWithoutExtrasAndProfileUsesPlainCreate() throws Exception {
        when(cronJobService.create(any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean(), any(), any(), any(), any(), any()))
            .thenReturn(job("plain"));

        mockMvc.perform(post("/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"plain\",\"schedule\":\"every 1h\",\"prompt\":\"Check\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("plain"));

        verify(cronJobService).create(any(), any(), any(), any(), any(), any(), any(), any(),
            anyBoolean(), any(), any(), any(), any(), any());
    }

    @Test
    void createWithoutExtrasWithProfileUsesCreateInProfile() throws Exception {
        CronJobEntity entity = job("plain");
        entity.setProfile("work");
        when(cronJobService.createInProfile(eq("work"), any(), any(), any(), any(), any(), any(), any(),
            any(), anyBoolean(), any(), any(), any(), any(), any()))
            .thenReturn(entity);

        mockMvc.perform(post("/p/work/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"plain\",\"schedule\":\"every 1h\",\"prompt\":\"Check\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile").value("work"));

        verify(cronJobService).createInProfile(eq("work"), any(), any(), any(), any(), any(), any(), any(),
            any(), anyBoolean(), any(), any(), any(), any(), any());
    }

    @Test
    void createRejectsMissingSchedule() throws Exception {
        mockMvc.perform(post("/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"no-schedule\",\"prompt\":\"Check\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsPromptSkillsScriptAllEmpty() throws Exception {
        mockMvc.perform(post("/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schedule\":\"every 1h\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateWithHermesExtrasUsesExtendedUpdate() throws Exception {
        CronJobEntity existing = job("existing");
        when(cronJobService.findById(JOB_ID)).thenReturn(Optional.of(existing));
        when(cronJobService.update(eq(JOB_ID), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(job("updated"));

        mockMvc.perform(put("/api/cron/jobs/{id}", JOB_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"updates\":{\"prompt\":\"New\",\"continuity\":true}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("updated"));

        verify(cronJobService).update(eq(JOB_ID), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateWithoutExtrasUsesPlainUpdate() throws Exception {
        CronJobEntity existing = job("existing");
        when(cronJobService.findById(JOB_ID)).thenReturn(Optional.of(existing));
        when(cronJobService.update(eq(JOB_ID), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(job("updated"));

        mockMvc.perform(put("/api/cron/jobs/{id}", JOB_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"updates\":{\"prompt\":\"New\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("updated"));

        verify(cronJobService).update(eq(JOB_ID), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateUnknownJobReturns404() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/cron/jobs/{id}", JOB_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void listJobRunsReturnsRunsAndLimit() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(Optional.of(job("existing")));

        mockMvc.perform(get("/api/cron/jobs/{id}/runs", JOB_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runs").isEmpty())
            .andExpect(jsonPath("$.limit").value(20));
    }

    @Test
    void listJobRunsUnknownJobReturns404() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/cron/jobs/{id}/runs", JOB_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void pauseResumeTriggerDeleteLifecycle() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(Optional.of(job("existing")));
        when(cronJobService.pause(JOB_ID)).thenReturn(job("paused"));
        when(cronJobService.resume(JOB_ID)).thenReturn(job("resumed"));
        when(cronJobService.runNowBackground(JOB_ID, null)).thenReturn(job("triggered"));
        org.mockito.Mockito.doNothing().when(cronJobService).remove(JOB_ID);

        mockMvc.perform(post("/api/cron/jobs/{id}/pause", JOB_ID))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/cron/jobs/{id}/resume", JOB_ID))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/cron/jobs/{id}/trigger", JOB_ID))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/cron/jobs/{id}", JOB_ID))
            .andExpect(status().isOk());
    }

    @Test
    void pauseUnknownJobReturns404() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/cron/jobs/{id}/pause", JOB_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void deliveryTargetsListed() throws Exception {
        mockMvc.perform(get("/api/cron/delivery-targets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targets").isArray());
    }

    @Test
    void blueprintsListAndInstantiate() throws Exception {
        mockMvc.perform(get("/api/cron/blueprints"))
            .andExpect(status().isOk());

        when(cronJobService.create(any(), any(), any(), any(), any()))
            .thenReturn(job("instantiated"));
        mockMvc.perform(post("/api/cron/blueprints/instantiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blueprint\":\"morning-brief\",\"values\":{\"time\":\"08:00\"}}"))
            .andExpect(status().isOk());
    }

    @Test
    void cronFireWebhookRejectsBadPayload() throws Exception {
        mockMvc.perform(post("/api/cron/fire")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().is4xxClientError());
    }
}
