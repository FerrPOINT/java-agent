package com.azhukov.agent.api;

import com.azhukov.agent.core.skill.CuratorService;
import com.azhukov.agent.core.skill.CuratorService.CuratorReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link CuratorController} — status, run, pause, resume endpoints.
 */
@ExtendWith(MockitoExtension.class)
class CuratorControllerTest {

    private MockMvc mockMvc;

    @Mock private CuratorService curatorService;

    @BeforeEach
    void setUp() {
        CuratorController controller = new CuratorController(curatorService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void curatorStatusReturnsAllFields() throws Exception {
        when(curatorService.isEnabled()).thenReturn(true);
        when(curatorService.isPaused()).thenReturn(false);
        when(curatorService.isDryRun()).thenReturn(true);
        when(curatorService.getIntervalHours()).thenReturn(168);
        when(curatorService.getMinIdleHours()).thenReturn(2.0);
        when(curatorService.getStaleAfterDays()).thenReturn(30);
        when(curatorService.getArchiveAfterDays()).thenReturn(90);

        mockMvc.perform(get("/api/v1/agent/curator/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.paused").value(false))
            .andExpect(jsonPath("$.dryRun").value(true))
            .andExpect(jsonPath("$.intervalHours").value(168))
            .andExpect(jsonPath("$.minIdleHours").value(2.0))
            .andExpect(jsonPath("$.staleAfterDays").value(30))
            .andExpect(jsonPath("$.archiveAfterDays").value(90));
    }

    @Test
    void curatorStatusWhenDisabled() throws Exception {
        when(curatorService.isEnabled()).thenReturn(false);
        when(curatorService.isPaused()).thenReturn(false);
        when(curatorService.isDryRun()).thenReturn(false);
        when(curatorService.getIntervalHours()).thenReturn(168);
        when(curatorService.getMinIdleHours()).thenReturn(0.0);
        when(curatorService.getStaleAfterDays()).thenReturn(30);
        when(curatorService.getArchiveAfterDays()).thenReturn(90);

        mockMvc.perform(get("/api/v1/agent/curator/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.paused").value(false))
            .andExpect(jsonPath("$.dryRun").value(false));
    }

    @Test
    void curatorRunReturnsReportString() throws Exception {
        CuratorReport report = new CuratorReport(
            List.of("skill-a", "skill-b"),
            List.of("skill-c"),
            List.of(),
            List.of(),
            List.of()
        );
        when(curatorService.runCycle()).thenReturn(report);

        mockMvc.perform(post("/api/v1/agent/curator/run"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("skill-a")));
    }

    @Test
    void curatorRunReturnsNoReportMessage() throws Exception {
        when(curatorService.runCycle()).thenReturn(null);

        mockMvc.perform(post("/api/v1/agent/curator/run"))
            .andExpect(status().isOk())
            .andExpect(content().string("Curator cycle completed (no report)"));
    }

    @Test
    void curatorPauseSetsPausedTrue() throws Exception {
        doNothing().when(curatorService).setPaused(true);

        mockMvc.perform(post("/api/v1/agent/curator/pause"))
            .andExpect(status().isOk());
    }

    @Test
    void curatorResumeSetsPausedFalse() throws Exception {
        doNothing().when(curatorService).setPaused(false);

        mockMvc.perform(post("/api/v1/agent/curator/resume"))
            .andExpect(status().isOk());
    }
}