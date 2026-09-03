package com.azhukov.agent.api;

import com.azhukov.agent.core.skill.CuratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CuratorDashboardControllerTest {

    private MockMvc mockMvc;
    private CuratorDashboardController controller;

    @Mock
    private CuratorService curatorService;

    @BeforeEach
    void setUp() {
        controller = new CuratorDashboardController(curatorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void statusReturnsHermesSnakeCaseShapeWithLastRunState() throws Exception {
        when(curatorService.isEnabled()).thenReturn(true);
        when(curatorService.isPaused()).thenReturn(false);
        when(curatorService.getIntervalHours()).thenReturn(168);
        when(curatorService.getMinIdleHours()).thenReturn(2.0);
        when(curatorService.getStaleAfterDays()).thenReturn(30);
        when(curatorService.getArchiveAfterDays()).thenReturn(90);
        when(curatorService.loadState()).thenReturn(Map.of("last_run_at", "2026-08-30T12:00:00Z"));

        mockMvc.perform(get("/api/curator").param("profile", "default"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.paused").value(false))
            .andExpect(jsonPath("$.interval_hours").value(168))
            .andExpect(jsonPath("$.last_run_at").value("2026-08-30T12:00:00Z"))
            .andExpect(jsonPath("$.min_idle_hours").value(2.0))
            .andExpect(jsonPath("$.stale_after_days").value(30))
            .andExpect(jsonPath("$.archive_after_days").value(90));
    }

    @Test
    void setPausedUpdatesCuratorAndReturnsEnvelope() throws Exception {
        mockMvc.perform(put("/api/curator/paused")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paused\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.paused").value(true));

        verify(curatorService).setPaused(true);
    }

    @Test
    void setPausedRejectsMissingBody() throws Exception {
        mockMvc.perform(put("/api/curator/paused"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("paused is required"));
    }

    @Test
    void runNowReturnsActionEnvelopeAndTriggersCycleInBackground() throws Exception {
        mockMvc.perform(post("/api/curator/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.pid").isNumber())
            .andExpect(jsonPath("$.name").value("curator-run"));

        verify(curatorService, timeout(500)).runCycle();
        controller.shutdown();
    }
}
