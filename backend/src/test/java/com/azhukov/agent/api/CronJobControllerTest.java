package com.azhukov.agent.api;

import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.CronSuggestionService;
import com.azhukov.agent.service.HeartbeatService;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.repository.CronExecutionLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CronJobControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock private CronJobService cronJobService;
    @Mock private CronSuggestionService cronSuggestionService;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;
    @Mock private com.azhukov.agent.persistence.repository.SessionRepository sessionRepository;
    @Mock private HeartbeatService heartbeatService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        CronJobController controller = new CronJobController(
            cronJobService,
            cronSuggestionService,
            heartbeatService,
            cronExecutionLogRepository,
            sessionRepository,
            org.mapstruct.factory.Mappers.getMapper(com.azhukov.agent.api.mapper.CronJobDtoMapper.class),
            new com.azhukov.agent.service.CronBlueprintService());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void suggestionMutationsRejectDisplayOrdinals() throws Exception {
        for (String action : java.util.List.of("accept", "dismiss")) {
            mockMvc.perform(post("/api/v1/agent/cron/suggestions/1/" + action))
                .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(cronSuggestionService);
    }

    @Test
    void createEndpoint() throws Exception {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("test-job");
        entity.setSchedule("0 * * * *");
        entity.setPrompt("test prompt");
        entity.setEnabled(true);
        when(cronJobService.create(
            any(), any(), any(), any(),
            any(), any(), any(), any(),
            any(), any(), anyBoolean(), any(),
            any(), any(), any(), any(),
            any(), anyBoolean(), any())).thenReturn(entity);

        mockMvc.perform(post("/api/v1/agent/cron")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"test-job","schedule":"0 * * * *","prompt":"test prompt"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("test-job"));
    }

    @Test
    void createEndpoint_withContextFrom_passesChainingFields() throws Exception {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("chained");
        entity.setSchedule("0 * * * *");
        entity.setPrompt("p");
        entity.setEnabled(true);
        entity.setContextFrom("11111111-1111-1111-1111-111111111111");
        when(cronJobService.create(
            any(), any(), any(), any(),
            any(), any(), any(), any(),
            any(), any(), anyBoolean(), any(),
            any(), any(), any(), any(),
            any(), anyBoolean(), any())).thenReturn(entity);

        mockMvc.perform(post("/api/v1/agent/cron")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"chained","schedule":"0 * * * *","prompt":"p",
                     "contextFrom":"11111111-1111-1111-1111-111111111111",
                     "enabledToolsets":"web","workdir":"/tmp"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contextFrom").value("11111111-1111-1111-1111-111111111111"));

        verify(cronJobService).create(
            isNull(), isNull(), eq("chained"), eq("0 * * * *"),
            eq("p"), isNull(), isNull(), eq("11111111-1111-1111-1111-111111111111"),
            isNull(), isNull(), eq(false), eq("web"),
            eq("/tmp"), isNull(), isNull(), isNull(),
            isNull(), eq(false), isNull());
    }

    @Test
    void acceptSuggestionReturnsNotFoundWithoutCreatingJobForForeignSuggestion() throws Exception {
        UserContext.set("user-b", UserContext.ROLE_USER);
        try {
            when(cronSuggestionService.acceptSuggestion(org.mockito.ArgumentMatchers.any(UUID.class), eq("user-b"))).thenReturn(null);

            mockMvc.perform(post("/api/v1/agent/cron/suggestions/{id}/accept", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

            verify(cronSuggestionService).acceptSuggestion(org.mockito.ArgumentMatchers.any(UUID.class), eq("user-b"));
            verify(cronJobService, never()).create(
            any(), org.mockito.ArgumentMatchers.eq("default"), any(), any(),
            any(), any(), any(), any(),
            any(), any(), anyBoolean(), any(),
            any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.isNull());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void dismissSuggestionReturnsFalseForForeignSuggestion() throws Exception {
        UserContext.set("user-b", UserContext.ROLE_USER);
        try {
            when(cronSuggestionService.dismissSuggestion(org.mockito.ArgumentMatchers.any(UUID.class), eq("user-b"))).thenReturn(false);

            mockMvc.perform(post("/api/v1/agent/cron/suggestions/{id}/dismiss", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dismissed").value(false));

            verify(cronSuggestionService).dismissSuggestion(org.mockito.ArgumentMatchers.any(UUID.class), eq("user-b"));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void heartbeatSetRejectsForeignSessionBeforeMutatingState() throws Exception {
        UUID sessionId = UUID.randomUUID();
        com.azhukov.agent.persistence.entity.SessionEntity foreign =
            new com.azhukov.agent.persistence.entity.SessionEntity();
        foreign.setId(sessionId);
        foreign.setUserId("user-b");
        UserContext.set("user-a", UserContext.ROLE_USER);
        try {
            when(sessionRepository.findById(sessionId)).thenReturn(java.util.Optional.of(foreign));

            mockMvc.perform(post("/api/v1/agent/cron/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sessionId":"%s","prompt":"check status","intervalSeconds":60}
                        """.formatted(sessionId)))
                .andExpect(status().isForbidden());

            verify(heartbeatService, org.mockito.Mockito.never()).set(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void exactHeartbeatSessionRequiresItsProfileEvenForTheSameOwner() throws Exception {
        UUID sessionId = UUID.randomUUID();
        var session = new com.azhukov.agent.persistence.entity.SessionEntity();
        session.setId(sessionId);
        session.setUserId("same-owner");
        session.setProfile("alpha");
        when(sessionRepository.findById(sessionId)).thenReturn(java.util.Optional.of(session));
        when(heartbeatService.get(sessionId)).thenReturn(new HeartbeatService.HeartbeatState(
            "private alpha reminder", 60, "active", java.time.Instant.now(), null, 0, 0));

        UserContext.set("same-owner", UserContext.ROLE_USER);
        try {
            mockMvc.perform(get("/api/v1/agent/cron/heartbeat/{id}", sessionId))
                .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/agent/cron/heartbeat/{id}", sessionId).param("profile", "beta"))
                .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/v1/agent/cron/heartbeat/{id}/clear", sessionId).param("profile", "beta"))
                .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/agent/cron/heartbeat/{id}", sessionId).param("profile", "alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prompt").value("private alpha reminder"));
            verify(heartbeatService).get(sessionId);
            verify(heartbeatService, never()).clear(any());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void heartbeatSetRejectsNegativeMaxTicksWithoutStartingUnlimitedLoop() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UserContext.set("user-a", UserContext.ROLE_USER);
        try {
            mockMvc.perform(post("/api/v1/agent/cron/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sessionId":"%s","prompt":"check status","intervalSeconds":60,"maxTicks":-1}
                        """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
            verify(heartbeatService, never()).set(any(), anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void heartbeatSetRejectsMissingSessionWithoutCreatingState() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UserContext.set("user-a", UserContext.ROLE_USER);
        try {
            when(sessionRepository.findById(sessionId)).thenReturn(java.util.Optional.empty());
            mockMvc.perform(post("/api/v1/agent/cron/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"sessionId":"%s","prompt":"check status","intervalSeconds":60}
                        """.formatted(sessionId)))
                .andExpect(status().isNotFound());
            verify(heartbeatService, never()).set(any(), anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void listEndpoint() throws Exception {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("job1");
        when(cronJobService.listForProfile("default", true)).thenReturn(List.of(entity));

        mockMvc.perform(get("/api/v1/agent/cron"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("job1"));
    }

    @Test
    void currentCreationPreservesProfileScriptMonitorAndSessionScope() throws Exception {
        UUID session = UUID.randomUUID();
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID()); entity.setName("script-job"); entity.setProfile("team");
        UserContext.set("owner-a", UserContext.ROLE_USER);
        try {
            when(cronJobService.create(
            any(), any(), any(), any(),
            any(), any(), any(), any(),
            any(), any(), anyBoolean(), any(),
            any(), any(), any(), any(),
            any(), anyBoolean(), any())).thenReturn(entity);
            mockMvc.perform(post("/api/v1/agent/cron").contentType(MediaType.APPLICATION_JSON).content("""
                    {"name":"script-job","schedule":"0 * * * *","profile":"team","script":"daily.sh",
                     "noAgent":true,"repeatCount":2,"enabledToolsets":"terminal","workdir":"/jobs",
                     "modelProvider":"noop","modelName":"fixture","baseUrl":"http://localhost:8090",
                     "continuityEnabled":false,"attachedSessionId":"%s"}
                    """.formatted(session)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile").value("team"));
            verify(cronJobService).create(
            eq("owner-a"), eq("team"), eq("script-job"), eq("0 * * * *"),
            isNull(), isNull(), isNull(), isNull(),
            eq(2), eq("daily.sh"), eq(true), eq("terminal"),
            eq("/jobs"), eq("noop"), eq("fixture"), eq("http://localhost:8090"),
            isNull(), eq(false), eq(session));
        } finally { UserContext.clear(); }
    }

    @Test
    void currentUpdatePreservesConfigurationAndExplicitSessionAttachment() throws Exception {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        CronJobEntity entity = new CronJobEntity(); entity.setId(id); entity.setName("updated");
        when(cronJobService.findById(id, "default")).thenReturn(java.util.Optional.of(entity));
        when(cronJobService.update(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(entity);
        mockMvc.perform(put("/api/v1/agent/cron/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"updated","schedule":"0 * * * *","skills":"audit","repeatCount":3,
                 "script":"daily.sh","noAgent":false,"monitor":"https://example.test/status",
                 "continuityEnabled":true,"attachToSession":true,"attachedSessionId":"%s"}
                """.formatted(session))).andExpect(status().isOk());
        verify(cronJobService).update(eq(id), eq("updated"), eq("0 * * * *"), isNull(), isNull(), isNull(),
                eq("audit"), isNull(), eq(3), eq("daily.sh"), eq(false), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("https://example.test/status"), eq(true), eq(true), eq(session));
    }

    @Test
    void profileListUsesTheExistingScopedServiceBoundary() throws Exception {
        when(cronJobService.listForProfile("team", false)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/agent/cron?profile=team&includeDisabled=false"))
            .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(cronJobService).listForProfile("team", false);
    }

    @Test
    void obsoleteFieldsAndActorOverridesAreRejectedBeforeMutation() throws Exception {
        for (String field : List.of("no_agent", "monitor_script", "monitor_url", "attached_session_id", "userId")) {
            mockMvc.perform(post("/api/v1/agent/cron").contentType(MediaType.APPLICATION_JSON).content(
                    "{\"name\":\"job\",\"schedule\":\"0 * * * *\",\"prompt\":\"work\",\"" + field + "\":\"obsolete\"}"))
                .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(cronJobService);
    }

    @Test
    void retiredCrudRoutesAreNotRegistered() throws Exception {
        for (String route : List.of("/api/jobs", "/api/cron/jobs", "/p/team/api/jobs", "/p/team/api/cron/jobs")) {
            mockMvc.perform(get(route)).andExpect(status().isNotFound());
            mockMvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void pauseEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        CronJobEntity entity = new CronJobEntity();
        entity.setId(id);
        entity.setEnabled(false);
        when(cronJobService.findById(id, "default")).thenReturn(java.util.Optional.of(entity));
        when(cronJobService.pause(id)).thenReturn(entity);

        mockMvc.perform(post("/api/v1/agent/cron/{id}/pause", id))
            .andExpect(status().isOk());
    }

    @Test
    void executionsRejectForeignCronJobBeforeReadingLedger() throws Exception {
        UUID id = UUID.randomUUID();
        UserContext.set("user-a", UserContext.ROLE_USER);
        try {
            when(cronJobService.findById(id, "default")).thenReturn(java.util.Optional.empty());

            mockMvc.perform(get("/api/v1/agent/cron/{id}/executions", id))
                .andExpect(status().isNotFound());

            verify(cronExecutionLogRepository, org.mockito.Mockito.never()).findByJobIdOrderByStartedAtDesc(id);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void sameHumanCannotReadOrRunKnownJobIdFromAnotherProfile() throws Exception {
        UUID id = UUID.randomUUID();
        var alpha = new CronJobEntity();
        alpha.setId(id);
        alpha.setProfile("alpha");
        alpha.setName("plan alpha");
        when(cronJobService.findById(id, "alpha")).thenReturn(java.util.Optional.of(alpha));

        mockMvc.perform(get("/api/v1/agent/cron/{id}", id).param("profile", "alpha"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.profile").value("alpha"));
        mockMvc.perform(get("/api/v1/agent/cron/{id}", id).param("profile", "beta"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/agent/cron/{id}/run", id).param("profile", "beta"))
            .andExpect(status().isNotFound());
        verify(cronJobService, org.mockito.Mockito.never()).runNow(id);
    }

    @Test
    void deleteEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        // unknown id → 404 (REST hygiene; double-delete must not be 200)
        mockMvc.perform(delete("/api/v1/agent/cron/{id}", id))
            .andExpect(status().isNotFound());
    }
}