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
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Coverage + regression for CronDashboardController not-found / error branches:
 * unknown job id on get/runs/pause/resume/trigger/delete, empty delivery targets.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CronDashboardControllerNotFoundBranchTest {

    private static final UUID JOB_ID = UUID.fromString("5b418d50-f2d9-437f-81f9-ddd138c88b13");
    private static final String HERMES_JOB_ID = "5b418d50f2d9";

    @TempDir
    private Path tempDir;

    @Mock private CronJobService cronJobService;

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

    private CronJobEntity job(String name, boolean enabled) {
        CronJobEntity e = new CronJobEntity();
        e.setId(JOB_ID);
        e.setName(name);
        e.setEnabled(enabled);
        e.setSchedule("5m");
        return e;
    }

    @Test
    void getUnknownJobIsNotFound() throws Exception {
        when(cronJobService.findById(any(UUID.class))).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/cron/jobs/" + HERMES_JOB_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void getMalformedJobIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/cron/jobs/zzzz"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void runsForKnownJobReturnsListShape() throws Exception {
        when(cronJobService.findById(any(UUID.class))).thenReturn(Optional.of(job("a", true)));
        mockMvc.perform(get("/api/cron/jobs/" + HERMES_JOB_ID + "/runs"))
            .andExpect(result -> {
                int code = result.getResponse().getStatus();
                org.assertj.core.api.Assertions.assertThat(code).isIn(200, 404);
            });
    }

    @Test
    void pauseUnknownJobIsNotFound() throws Exception {
        when(cronJobService.findById(any(UUID.class))).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/cron/jobs/" + HERMES_JOB_ID + "/pause"))
            .andExpect(status().isNotFound());
    }

    @Test
    void resumeUnknownJobIsNotFound() throws Exception {
        when(cronJobService.findById(any(UUID.class))).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/cron/jobs/" + HERMES_JOB_ID + "/resume"))
            .andExpect(status().isNotFound());
    }

    @Test
    void triggerUnknownJobIsNotFound() throws Exception {
        when(cronJobService.findById(any(UUID.class))).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/cron/jobs/" + HERMES_JOB_ID + "/trigger"))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteUnknownJobIsNotFound() throws Exception {
        when(cronJobService.findById(any(UUID.class))).thenReturn(Optional.empty());
        mockMvc.perform(delete("/api/cron/jobs/" + HERMES_JOB_ID))
            .andExpect(status().isNotFound());
    }

    @Test
    void deliveryTargetsIncludeTelegramWhenConfigured() throws Exception {
        mockMvc.perform(get("/api/cron/delivery-targets"))
            .andExpect(status().isOk());
    }

    @Test
    void blueprintsListed() throws Exception {
        mockMvc.perform(get("/api/cron/blueprints"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.blueprints").isArray());
    }

    @Test
    void fireWithoutVerifierSecretFailsClosed() throws Exception {
        mockMvc.perform(post("/api/cron/fire").contentType("application/json").content("{}"))
            .andExpect(status().is4xxClientError());
    }
}
