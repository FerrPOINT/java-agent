package com.azhukov.agent.api;

import com.azhukov.agent.tools.memory.SessionSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionSearchControllerTest {

    @Test
    void searchSessionsReturnsHermesResultsEnvelope() throws Exception {
        SessionSearchService service = mock(SessionSearchService.class);
        when(service.webSearch("needle", 25, "work", "cli", null, "cron"))
            .thenReturn(Map.of("results", List.of(Map.of(
                "session_id", "550e8400-e29b-41d4-a716-446655440000",
                "snippet", "needle found"
            ))));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SessionSearchController(service)).build();

        mockMvc.perform(get("/api/sessions/search")
                .param("q", "needle")
                .param("limit", "25")
                .param("profile", "work")
                .param("source", "cli")
                .param("exclude_sources", "cron"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].session_id").value("550e8400-e29b-41d4-a716-446655440000"))
            .andExpect(jsonPath("$.results[0].snippet").value("needle found"));

        verify(service).webSearch("needle", 25, "work", "cli", null, "cron");
    }

    @Test
    void searchSessionsRejectsNonIntegerLimit() throws Exception {
        SessionSearchService service = mock(SessionSearchService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SessionSearchController(service)).build();

        mockMvc.perform(get("/api/sessions/search")
                .param("q", "needle")
                .param("limit", "many"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("limit must be an integer"));

        verify(service, never()).webSearch("needle", null, null, null, null, null);
    }

    @Test
    void searchSessionsReturnsEmptyResultsForBlankQueryWithoutCallingService() throws Exception {
        SessionSearchService service = mock(SessionSearchService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SessionSearchController(service)).build();

        mockMvc.perform(get("/api/sessions/search")
                .param("q", "   "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results").isArray())
            .andExpect(jsonPath("$.results.length()").value(0));

        verify(service, never()).webSearch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void searchSessionsReturnsHermesFailureEnvelopeWhenServiceFails() throws Exception {
        SessionSearchService service = mock(SessionSearchService.class);
        when(service.webSearch("needle", null, null, null, null, null))
            .thenThrow(new IllegalStateException("index unavailable"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SessionSearchController(service)).build();

        mockMvc.perform(get("/api/sessions/search")
                .param("q", "needle"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("Search failed"));
    }
}
