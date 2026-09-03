package com.azhukov.agent.api;

import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.repository.UsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalyticsDashboardControllerTest {

    private static final UUID SESSION_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SESSION_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private UsageRepository usageRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalyticsDashboardController(usageRepository)).build();
    }

    @Test
    void usageAggregatesDailyModelAndTotalsInHermesShape() throws Exception {
        when(usageRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(
            usage(SESSION_1, "gpt-5", 100, 50, 10, 0.02, "2026-01-02T01:00:00Z"),
            usage(SESSION_1, "gpt-5", 20, 30, 0, null, "2026-01-02T02:00:00Z"),
            usage(SESSION_2, "claude-sonnet-4-5", 10, 90, 5, 0.03, "2026-01-03T01:00:00Z")
        ));

        mockMvc.perform(get("/api/analytics/usage?days=30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.period_days").value(30))
            .andExpect(jsonPath("$.daily[0].day").value("2026-01-02"))
            .andExpect(jsonPath("$.daily[0].input_tokens").value(120))
            .andExpect(jsonPath("$.daily[0].output_tokens").value(80))
            .andExpect(jsonPath("$.daily[0].cache_read_tokens").value(10))
            .andExpect(jsonPath("$.daily[0].sessions").value(1))
            .andExpect(jsonPath("$.daily[0].api_calls").value(2))
            .andExpect(jsonPath("$.daily[1].day").value("2026-01-03"))
            .andExpect(jsonPath("$.by_model[0].model").value("gpt-5"))
            .andExpect(jsonPath("$.by_model[0].input_tokens").value(120))
            .andExpect(jsonPath("$.by_model[0].output_tokens").value(80))
            .andExpect(jsonPath("$.by_model[1].model").value("claude-sonnet-4-5"))
            .andExpect(jsonPath("$.totals.total_input").value(130))
            .andExpect(jsonPath("$.totals.total_output").value(170))
            .andExpect(jsonPath("$.totals.total_cache_read").value(15))
            .andExpect(jsonPath("$.totals.total_reasoning").value(0))
            .andExpect(jsonPath("$.totals.total_sessions").value(2))
            .andExpect(jsonPath("$.totals.total_api_calls").value(3))
            .andExpect(jsonPath("$.skills.summary.total_skill_actions").value(0))
            .andExpect(jsonPath("$.skills.top_skills").isArray())
            .andExpect(jsonPath("$.tools").isArray())
            .andExpect(jsonPath("$.by_task").isArray());
    }

    @Test
    void modelsAggregatesPerModelAnalyticsInHermesShape() throws Exception {
        when(usageRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(
            usage(SESSION_1, "gpt-5", 100, 50, 10, 0.02, "2026-01-02T01:00:00Z"),
            usage(SESSION_1, "gpt-5", 20, 30, 0, null, "2026-01-02T02:00:00Z"),
            usage(SESSION_2, "claude-sonnet-4-5", 10, 90, 5, 0.03, "2026-01-03T01:00:00Z")
        ));

        mockMvc.perform(get("/api/analytics/models?days=365"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.period_days").value(365))
            .andExpect(jsonPath("$.models[0].model").value("gpt-5"))
            .andExpect(jsonPath("$.models[0].provider").value(""))
            .andExpect(jsonPath("$.models[0].input_tokens").value(120))
            .andExpect(jsonPath("$.models[0].output_tokens").value(80))
            .andExpect(jsonPath("$.models[0].cache_read_tokens").value(10))
            .andExpect(jsonPath("$.models[0].reasoning_tokens").value(0))
            .andExpect(jsonPath("$.models[0].actual_cost").value(0.0))
            .andExpect(jsonPath("$.models[0].sessions").value(1))
            .andExpect(jsonPath("$.models[0].api_calls").value(2))
            .andExpect(jsonPath("$.models[0].tool_calls").value(0))
            .andExpect(jsonPath("$.models[0].last_used_at").value(1767319200))
            .andExpect(jsonPath("$.models[0].avg_tokens_per_session").value(200.0))
            .andExpect(jsonPath("$.models[0].capabilities").isMap())
            .andExpect(jsonPath("$.models[1].model").value("claude-sonnet-4-5"))
            .andExpect(jsonPath("$.totals.distinct_models").value(2))
            .andExpect(jsonPath("$.totals.total_input").value(130))
            .andExpect(jsonPath("$.totals.total_output").value(170))
            .andExpect(jsonPath("$.totals.total_cache_read").value(15))
            .andExpect(jsonPath("$.totals.total_reasoning").value(0))
            .andExpect(jsonPath("$.totals.total_estimated_cost").value(0.05))
            .andExpect(jsonPath("$.totals.total_actual_cost").value(0.0))
            .andExpect(jsonPath("$.totals.total_sessions").value(2))
            .andExpect(jsonPath("$.totals.total_api_calls").value(3));
    }

    @Test
    void usageAcceptsMaximumDaysAndUsesBoundedCreatedAtQuery() throws Exception {
        when(usageRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/analytics/usage?days=365"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.period_days").value(365))
            .andExpect(jsonPath("$.daily").isArray())
            .andExpect(jsonPath("$.by_model").isArray())
            .andExpect(jsonPath("$.totals.total_sessions").value(0));

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        verify(usageRepository).findByCreatedAtBetweenOrderByCreatedAtAsc(start.capture(), end.capture());
        assertThat(start.getValue()).isBefore(end.getValue());
    }

    @Test
    void analyticsRejectsOutOfRangeDaysLikeHermes() throws Exception {
        mockMvc.perform(get("/api/analytics/usage?days=0"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("days must be between 1 and 365"));

        mockMvc.perform(get("/api/analytics/models?days=100000"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("days must be between 1 and 365"));

        mockMvc.perform(get("/api/analytics/usage?days=abc"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("days must be an integer"));

        verifyNoInteractions(usageRepository);
    }

    private static UsageEntity usage(UUID sessionId,
                                     String model,
                                     int prompt,
                                     int completion,
                                     int cacheRead,
                                     Double cost,
                                     String createdAt) {
        UsageEntity entity = new UsageEntity();
        entity.setSessionId(sessionId);
        entity.setModel(model);
        entity.setPromptTokens(prompt);
        entity.setCompletionTokens(completion);
        entity.setTotalTokens(prompt + completion);
        entity.setCacheReadTokens(cacheRead);
        entity.setCost(cost);
        entity.setCreatedAt(Instant.parse(createdAt));
        return entity;
    }
}
