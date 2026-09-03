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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CronDashboardControllerTest {

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

    @Test
    void dashboardListReturnsBareArrayAndIncludesPausedJobs() throws Exception {
        when(cronJobService.list(true)).thenReturn(List.of(job("paused", false)));

        mockMvc.perform(get("/api/cron/jobs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(HERMES_JOB_ID))
            .andExpect(jsonPath("$[0].enabled").value(false));

        verify(cronJobService).list(true);
    }

    @Test
    void profilePrefixedDashboardListUsesHermesProfileScope() throws Exception {
        CronJobEntity entity = job("work-job", true);
        entity.setProfile("work");
        when(cronJobService.listForProfile("work", true)).thenReturn(List.of(entity));

        mockMvc.perform(get("/p/work/api/cron/jobs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("work-job"))
            .andExpect(jsonPath("$[0].profile").value("work"));

        verify(cronJobService).listForProfile("work", true);
    }

    @Test
    void dashboardCreateAndGetReturnBareJobObjects() throws Exception {
        when(cronJobService.create(any(), any(), any(), any(), any(), any(), any(), any(),
            eq(false), any(), any(), any(), any(), any())).thenReturn(job("created", true));
        when(cronJobService.findById(JOB_ID)).thenReturn(java.util.Optional.of(job("created", true)));

        mockMvc.perform(post("/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"created","schedule":"every 1h","prompt":"Do work","skills":["research"]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("created"))
            .andExpect(jsonPath("$.skills[0]").value("research"));

        mockMvc.perform(get("/api/cron/jobs/{id}", JOB_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("created"));
    }

    @Test
    void profilePrefixedDashboardCreatePersistsScopeAndQueryProfileFiltersLookup() throws Exception {
        CronJobEntity entity = job("created", true);
        entity.setProfile("work");
        when(cronJobService.createInProfile(eq("work"), any(), any(), any(), any(), any(), any(), any(),
            any(), eq(false), any(), any(), any(), any(), any())).thenReturn(entity);
        when(cronJobService.findById(JOB_ID, "work")).thenReturn(java.util.Optional.of(entity));

        mockMvc.perform(post("/p/work/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"created","schedule":"every 1h","prompt":"Do work","skills":["research"]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile").value("work"))
            .andExpect(jsonPath("$.name").value("created"));

        mockMvc.perform(get("/api/cron/jobs/{id}?profile=work", JOB_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profile").value("work"))
            .andExpect(jsonPath("$.name").value("created"));

        verify(cronJobService).createInProfile("work", "created", "every 1h", "Do work", "local",
            "research", null, null, null, false, null, null, null, null, null);
        verify(cronJobService).findById(JOB_ID, "work");
    }

    @Test
    void dashboardCreatePreservesHermesExtendedFields() throws Exception {
        when(cronJobService.create(any(), any(), any(), any(), any(), any(), any(), any(),
            eq(false), any(), any(), any(), any(), any())).thenReturn(extendedJob("created", true));

        mockMvc.perform(post("/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"created",
                      "schedule":"every 1h",
                      "prompt":"Do work",
                      "deliver":"telegram",
                      "skills":["research","coding"],
                      "context_from":["self"],
                      "enabled_toolsets":["web","terminal"],
                      "workdir":"C:/work",
                      "provider":"openai",
                      "model":"gpt-4o",
                      "base_url":"https://api.openai.com/"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("created"))
            .andExpect(jsonPath("$.context_from[0]").value("self"))
            .andExpect(jsonPath("$.enabled_toolsets[1]").value("terminal"))
            .andExpect(jsonPath("$.provider_snapshot").value("initial-provider"))
            .andExpect(jsonPath("$.model_snapshot").value("old/model"))
            .andExpect(jsonPath("$.base_url").value("https://api.openai.com"));

        verify(cronJobService).create("created", "every 1h", "Do work", "telegram",
            "research,coding", "self", null, null, false, "web,terminal", "C:/work",
            "openai", "gpt-4o", "https://api.openai.com");
    }

    @Test
    void dashboardPutUnwrapsUpdatesAndTriggerAliasesRun() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(java.util.Optional.of(job("before", true)));
        when(cronJobService.update(eq(JOB_ID), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any())).thenReturn(job("after", true));
        when(cronJobService.runNowBackground(JOB_ID, null)).thenReturn(job("ran", true));

        mockMvc.perform(put("/api/cron/jobs/{id}", JOB_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"updates\":{\"name\":\"after\",\"skills\":[\"research\",\"coding\"]}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("after"));

        verify(cronJobService).update(JOB_ID, "after", null, null, null, null,
            "research,coding", null, null, null, null, null, null, null, null, null);

        mockMvc.perform(post("/api/cron/jobs/{id}/trigger", JOB_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("ran"))
            .andExpect(jsonPath("$.execution_mode").value("background"));
    }

    @Test
    void dashboardPutPreservesHermesExtendedFields() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(java.util.Optional.of(job("before", true)));
        when(cronJobService.update(eq(JOB_ID), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any())).thenReturn(extendedJob("after", true));

        mockMvc.perform(put("/api/cron/jobs/{id}", JOB_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "updates": {
                        "prompt":"Updated work",
                        "context_from":["self"],
                        "enabled_toolsets":["web"],
                        "workdir":"C:/work",
                        "provider":"openai",
                        "model":"gpt-4o",
                        "base_url":"https://api.openai.com/",
                        "no_agent":false
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.model").value("gpt-4o"))
            .andExpect(jsonPath("$.enabled_toolsets[0]").value("web"));

        verify(cronJobService).update(JOB_ID, null, null, "Updated work", null, null,
            null, "self", null, null, false, "web", "C:/work",
            "openai", "gpt-4o", "https://api.openai.com");
    }

    @Test
    void dashboardPutNullInferenceFieldsKeepExistingSnapshots() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(java.util.Optional.of(extendedJob("before", true)));
        when(cronJobService.update(eq(JOB_ID), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any())).thenReturn(extendedJob("after", true));

        mockMvc.perform(put("/api/cron/jobs/{id}", JOB_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "updates": {
                        "name": "after",
                        "provider": null,
                        "model": null,
                        "base_url": null,
                        "no_agent": false
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("after"))
            .andExpect(jsonPath("$.provider_snapshot").value("initial-provider"))
            .andExpect(jsonPath("$.model_snapshot").value("old/model"));

        verify(cronJobService).update(JOB_ID, "after", null, null, null, null,
            null, null, null, null, false, null, null, null, null, null);
    }

    @Test
    void dashboardCreateReportsInvalidContextFromAsMissingJob() throws Exception {
        mockMvc.perform(post("/api/cron/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"created",
                      "schedule":"every 1h",
                      "prompt":"Do work",
                      "context_from":["missing"]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("context_from job 'missing' not found in profile 'default'"));

        verifyNoInteractions(cronJobService);
    }

    @Test
    void dashboardRunsDeliveryTargetsAndDeleteExposeDesktopShapes() throws Exception {
        when(cronJobService.findById(JOB_ID)).thenReturn(java.util.Optional.of(job("delete", true)));

        mockMvc.perform(get("/api/cron/jobs/{id}/runs?limit=250", JOB_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runs").isArray())
            .andExpect(jsonPath("$.limit").value(100));

        mockMvc.perform(get("/api/cron/delivery-targets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.targets[0].id").value("local"))
            .andExpect(jsonPath("$.targets[0].home_env_var").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(delete("/api/cron/jobs/{id}", JOB_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void cronFireWebhookFailsClosedWithoutChronosVerifier() throws Exception {
        mockMvc.perform(post("/api/cron/fire")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job_id\":\"" + HERMES_JOB_ID + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid fire token"));

        mockMvc.perform(post("/api/cron/fire")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job_id\":\"" + HERMES_JOB_ID + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid fire token"));

        verifyNoInteractions(cronJobService);
    }

    @Test
    void profilePrefixedCronFireWebhookMirrorsFailClosedResponse() throws Exception {
        mockMvc.perform(post("/p/work/api/cron/fire")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"job_id\":\"" + HERMES_JOB_ID + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("invalid fire token"));

        verifyNoInteractions(cronJobService);
    }

    @Test
    void dashboardBlueprintRoutesUseDesktopSchemaAndCreateBareJob() throws Exception {
        when(cronJobService.create(any(), any(), any(), any(), any())).thenReturn(job("morning-brief-1234", true));

        mockMvc.perform(get("/api/cron/blueprints"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.blueprints[0].key").value("morning-brief"))
            .andExpect(jsonPath("$.blueprints[0].fields").isArray())
            .andExpect(jsonPath("$.blueprints[0].command").exists())
            .andExpect(jsonPath("$.blueprints[0].appUrl").exists());

        mockMvc.perform(post("/api/cron/blueprints/instantiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blueprint\":\"morning-brief\",\"values\":{\"time\":\"07:30\",\"deliver\":\"local\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("morning-brief-1234"));
    }

    @Test
    void dashboardBlueprintValidationUsesHermesStatusCodes() throws Exception {
        mockMvc.perform(post("/api/cron/blueprints/instantiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blueprint\":\"missing\",\"values\":{}}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown blueprint: missing"));

        mockMvc.perform(post("/api/cron/blueprints/instantiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blueprint\":\"morning-brief\",\"values\":{\"time\":\"bad\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Invalid time format")));
    }

    private static CronJobEntity job(String name, boolean enabled) {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(JOB_ID);
        entity.setName(name);
        entity.setSchedule("0 9 * * *");
        entity.setPrompt("Prompt");
        entity.setDeliverTo("local");
        entity.setEnabled(enabled);
        entity.setSkills("research");
        entity.setRepeatCount(3);
        entity.setRepeatCompleted(1);
        entity.setCreatedAt(Instant.parse("2026-08-28T09:00:00Z"));
        return entity;
    }

    private static CronJobEntity extendedJob(String name, boolean enabled) {
        CronJobEntity entity = job(name, enabled);
        entity.setSkills("research,coding");
        entity.setContextFrom("self");
        entity.setEnabledToolsets("web,terminal");
        entity.setWorkdir("C:/work");
        entity.setModelProvider("openai");
        entity.setModelName("gpt-4o");
        entity.setBaseUrl("https://api.openai.com");
        entity.setProviderSnapshot("initial-provider");
        entity.setModelSnapshot("old/model");
        return entity;
    }
}
