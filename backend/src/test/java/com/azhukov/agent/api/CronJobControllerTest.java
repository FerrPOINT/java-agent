package com.azhukov.agent.api;

import com.azhukov.agent.service.CronJobService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CronJobControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock private CronJobService cronJobService;
    @Mock private CronExecutionLogRepository cronExecutionLogRepository;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        CronJobController controller = new CronJobController(cronJobService, new com.azhukov.agent.service.CronSuggestionService(null), new com.azhukov.agent.service.HeartbeatService(), cronExecutionLogRepository, org.mapstruct.factory.Mappers.getMapper(com.azhukov.agent.api.mapper.CronJobDtoMapper.class), new com.azhukov.agent.service.CronBlueprintService());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createEndpoint() throws Exception {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("test-job");
        entity.setSchedule("0 * * * *");
        entity.setPrompt("test prompt");
        entity.setEnabled(true);
        when(cronJobService.create(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any())).thenReturn(entity);

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
        when(cronJobService.create(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any())).thenReturn(entity);

        mockMvc.perform(post("/api/v1/agent/cron")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"chained","schedule":"0 * * * *","prompt":"p",
                     "contextFrom":"11111111-1111-1111-1111-111111111111",
                     "enabledToolsets":"web","workdir":"/tmp"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contextFrom").value("11111111-1111-1111-1111-111111111111"));

        verify(cronJobService).create(eq("chained"), eq("0 * * * *"), eq("p"), isNull(), isNull(),
            eq("11111111-1111-1111-1111-111111111111"),
            isNull(), isNull(), eq(false), eq("web"), eq("/tmp"), isNull(), isNull(), isNull());
    }

    @Test
    void listEndpoint() throws Exception {
        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("job1");
        when(cronJobService.list()).thenReturn(List.of(entity));

        mockMvc.perform(get("/api/v1/agent/cron"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("job1"));
    }

    @Test
    void pauseEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        CronJobEntity entity = new CronJobEntity();
        entity.setId(id);
        entity.setEnabled(false);
        when(cronJobService.pause(id)).thenReturn(entity);

        mockMvc.perform(post("/api/v1/agent/cron/{id}/pause", id))
            .andExpect(status().isOk());
    }

    @Test
    void deleteEndpoint() throws Exception {
        UUID id = UUID.randomUUID();
        // unknown id → 404 (REST hygiene; double-delete must not be 200)
        mockMvc.perform(delete("/api/v1/agent/cron/{id}", id))
            .andExpect(status().isNotFound());
    }
}