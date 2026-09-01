package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.core.model.TokenUsage;
import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.repository.UsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Branch-coverage tests for {@link UsageTracker} targeting:
 * - normalizeModel for various model name prefixes
 * - computeCost with cache tokens
 * - recordTurn with TokenUsage (cache read/write)
 * - recordTurn with repository exception (caught, logged)
 * - getDailyUsage with empty/ non-empty results
 * - getCreditsSummary with null cost entries
 * - getCostBreakdown with null model entries
 * - loadAllRecords with null userId vs non-null userId
 */
@ExtendWith(MockitoExtension.class)
class UsageTrackerBranchTest {

    @Mock
    private UsageRepository usageRepository;

    private UsageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new UsageTracker(usageRepository);
    }

    private UsageEntity createUsageEntity(UUID sessionId, String userId, String model, int tokens, Double cost) {
        UsageEntity e = new UsageEntity();
        e.setSessionId(sessionId);
        e.setUserId(userId);
        e.setModel(model);
        e.setPromptTokens(100);
        e.setCompletionTokens(50);
        e.setTotalTokens(tokens);
        e.setCost(cost);
        e.setCreatedAt(Instant.now());
        return e;
    }

    // ── normalizeModel: gpt-4o-mini prefix ──

    @Test
    void recordTurnGpt4oMiniVariantUsesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "gpt-4o-mini-2024-07-18", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // gpt-4o-mini: input=$0.150/1M → 1M tokens = $0.150
        assertThat(captor.getValue().getCost()).isCloseTo(0.150, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: claude-3-5-sonnet variant ──

    @Test
    void recordTurnClaude35SonnetVariantUsesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "claude-3-5-sonnet-20241022", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // claude-3-5-sonnet: input=$3.00/1M → 1M tokens = $3.00
        assertThat(captor.getValue().getCost()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: gemini-2.0-flash-lite ──

    @Test
    void recordTurnGemini20FlashLiteUsesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "gemini-2.0-flash-lite-001", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // gemini-2.0-flash-lite: input=$0.075/1M → 1M tokens = $0.075
        assertThat(captor.getValue().getCost()).isCloseTo(0.075, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: deepseek-reasoner ──

    @Test
    void recordTurnDeepseekReasonerUsesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "deepseek-reasoner-v3", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // deepseek-reasoner: input=$0.55/1M → 1M tokens = $0.55
        assertThat(captor.getValue().getCost()).isCloseTo(0.55, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: unknown model uses default pricing ──

    @Test
    void recordTurnUnknownModelUsesDefaultPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "some-unknown-model", 1_000_000, 500_000);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // Default: input=$0.50/1M, output=$1.50/1M
        // 1M prompt + 500K completion = 0.50 + 0.75 = 1.25
        assertThat(captor.getValue().getCost()).isCloseTo(1.25, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── recordTurn with cache tokens ──

    @Test
    void recordTurnWithCacheTokensComputesCacheCost() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "gpt-4o",
            TokenUsage.of(1_000_000, 500_000, 200_000, 100_000, 0));

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        UsageEntity saved = captor.getValue();
        // gpt-4o: input=$2.50/1M, output=$10.00/1M, cache_read=$1.25/1M
        // cache_write = input * 1.25 = $3.125/1M
        // cost = 1M * 2.50/1M + 500K * 10.00/1M + 200K * 1.25/1M + 100K * 3.125/1M
        //      = 2.50 + 5.00 + 0.25 + 0.3125 = 8.0625
        assertThat(saved.getCost()).isCloseTo(8.0625, org.assertj.core.data.Offset.offset(0.001));
        assertThat(saved.getCacheReadTokens()).isEqualTo(200_000);
        assertThat(saved.getCacheWriteTokens()).isEqualTo(100_000);
    }

    // ── recordTurn with null model ──

    @Test
    void recordTurnWithNullModelDoesNotThrow() {
        UUID sessionId = UUID.randomUUID();
        // null model → Map.getOrDefault(null, ...) throws NPE in Map.ofEntries,
        // but recordTurn catches exceptions, so this should not throw
        tracker.recordTurn(sessionId, "user-1", null, 100, 50);
        // The exception is caught and logged, so no save happens
    }

    // ── recordTurn with repository exception does not throw ──

    @Test
    void recordTurnWithRepositoryExceptionDoesNotThrow() {
        UUID sessionId = UUID.randomUUID();
        doThrow(new RuntimeException("DB down"))
            .when(usageRepository).save(any());

        // Should not throw — exception is caught and logged
        tracker.recordTurn(sessionId, "user-1", "gpt-4", 100, 50);
    }

    // ── getSessionUsage ──

    @Test
    void getSessionUsageReturnsAggregatedUsage() {
        UUID sessionId = UUID.randomUUID();
        when(usageRepository.findBySessionId(sessionId))
            .thenReturn(List.of(
                createUsageEntity(sessionId, "user-1", "gpt-4", 100, 0.01),
                createUsageEntity(sessionId, "user-1", "gpt-4", 200, 0.02)
            ));

        UsageDto usage = tracker.getSessionUsage(sessionId);

        assertThat(usage.messageCount()).isEqualTo(2);
        assertThat(usage.tokenEstimate()).isEqualTo(300);
    }

    // ── getSessionUsage with empty records ──

    @Test
    void getSessionUsageWithEmptyRecordsReturnsZero() {
        UUID sessionId = UUID.randomUUID();
        when(usageRepository.findBySessionId(sessionId)).thenReturn(List.of());

        UsageDto usage = tracker.getSessionUsage(sessionId);

        assertThat(usage.messageCount()).isZero();
        assertThat(usage.tokenEstimate()).isZero();
    }

    // ── getDailyUsage with empty results ──

    @Test
    void getDailyUsageWithEmptyResultsReturnsZero() {
        LocalDate date = LocalDate.of(2024, 1, 15);
        when(usageRepository.findByUserIdAndCreatedAtBetween(any(), any(), any()))
            .thenReturn(List.of());
        when(usageRepository.findAll()).thenReturn(List.of());

        UsageDto usage = tracker.getDailyUsage(date);

        assertThat(usage.messageCount()).isZero();
        assertThat(usage.tokenEstimate()).isZero();
    }

    // ── getDailyUsage with results from findAll fallback ──

    @Test
    void getDailyUsageFallsBackToFindAll() {
        LocalDate date = LocalDate.of(2024, 1, 15);
        when(usageRepository.findByUserIdAndCreatedAtBetween(any(), any(), any()))
            .thenReturn(List.of());
        // Set up findAll with records that fall in the date range
        Instant start = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        UsageEntity e1 = createUsageEntity(UUID.randomUUID(), "user-1", "gpt-4", 500, 0.01);
        e1.setCreatedAt(start.plusSeconds(3600)); // within the day
        UsageEntity e2 = createUsageEntity(UUID.randomUUID(), "user-1", "gpt-4", 300, 0.01);
        e2.setCreatedAt(end.plusSeconds(3600)); // outside the day
        when(usageRepository.findAll()).thenReturn(List.of(e1, e2));

        UsageDto usage = tracker.getDailyUsage(date);

        // Only e1 falls within the date range
        assertThat(usage.messageCount()).isEqualTo(1);
        assertThat(usage.tokenEstimate()).isEqualTo(500);
    }

    // ── getDailyUsage with direct results from findByUserIdAndCreatedAtBetween ──

    @Test
    void getDailyUsageWithDirectResults() {
        LocalDate date = LocalDate.of(2024, 1, 15);
        when(usageRepository.findByUserIdAndCreatedAtBetween(any(), any(), any()))
            .thenReturn(List.of(
                createUsageEntity(UUID.randomUUID(), "user-1", "gpt-4", 500, 0.01)
            ));

        UsageDto usage = tracker.getDailyUsage(date);

        assertThat(usage.messageCount()).isEqualTo(1);
        assertThat(usage.tokenEstimate()).isEqualTo(500);
    }

    // ── getInsights with userId filter ──

    @Test
    void getInsightsWithUserIdFilter() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e1 = createUsageEntity(sessionId, "user-1", "gpt-4", 500, 0.01);
        UsageEntity e2 = createUsageEntity(sessionId, "user-2", "gpt-4", 300, 0.01);
        when(usageRepository.findByUserIdAndCreatedAtBetween(eq("user-1"), any(), any())).thenReturn(List.of(e1));

        InsightsDto insights = tracker.getInsights("user-1");

        assertThat(insights.totalTokens()).isEqualTo(500);
        assertThat(insights.totalMessages()).isEqualTo(1);
    }

    // ── getInsights with null userId ──

    @Test
    void getInsightsWithNullUserIdReturnsAll() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e1 = createUsageEntity(sessionId, "user-1", "gpt-4", 500, 0.01);
        UsageEntity e2 = createUsageEntity(sessionId, "user-2", "gpt-4", 300, 0.01);
        when(usageRepository.findAll()).thenReturn(List.of(e1, e2));

        InsightsDto insights = tracker.getInsights(null);

        assertThat(insights.totalTokens()).isEqualTo(800);
        assertThat(insights.totalMessages()).isEqualTo(2);
    }

    // ── getInsights with blank userId ──

    @Test
    void getInsightsWithBlankUserIdReturnsAll() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e1 = createUsageEntity(sessionId, "user-1", "gpt-4", 500, 0.01);
        when(usageRepository.findAll()).thenReturn(List.of(e1));

        InsightsDto insights = tracker.getInsights("  ");

        assertThat(insights.totalTokens()).isEqualTo(500);
        assertThat(insights.totalMessages()).isEqualTo(1);
    }

    // ── getInsights with null model entry ──

    @Test
    void getInsightsHandlesNullModel() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e = createUsageEntity(sessionId, "user-1", null, 500, 0.01);
        when(usageRepository.findAll()).thenReturn(List.of(e));

        InsightsDto insights = tracker.getInsights(null);

        assertThat(insights.byModel()).containsKey("unknown");
        assertThat(insights.byModel().get("unknown")).isEqualTo(500);
    }

    // ── getCreditsSummary with null cost entries ──

    @Test
    void getCreditsSummaryHandlesNullCost() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e1 = createUsageEntity(sessionId, "user-1", "gpt-4", 500, null);
        UsageEntity e2 = createUsageEntity(sessionId, "user-1", "gpt-4", 300, 0.05);
        when(usageRepository.findAll()).thenReturn(List.of(e1, e2));

        var credits = tracker.getCreditsSummary(null);

        assertThat(credits.totalTokens()).isEqualTo(800);
        assertThat(credits.totalMessages()).isEqualTo(2);
        assertThat(credits.totalCost()).isEqualTo(0.05);
    }

    // ── getCreditsSummary with userId filter ──

    @Test
    void getCreditsSummaryWithUserIdFilter() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e1 = createUsageEntity(sessionId, "user-1", "gpt-4", 500, 0.01);
        UsageEntity e2 = createUsageEntity(sessionId, "user-2", "gpt-4", 300, 0.02);
        when(usageRepository.findByUserIdAndCreatedAtBetween(eq("user-1"), any(), any())).thenReturn(List.of(e1));

        var credits = tracker.getCreditsSummary("user-1");

        assertThat(credits.totalTokens()).isEqualTo(500);
        assertThat(credits.totalMessages()).isEqualTo(1);
        assertThat(credits.totalCost()).isEqualTo(0.01);
    }

    // ── getCostBreakdown with null model ──

    @Test
    void getCostBreakdownHandlesNullModel() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e = createUsageEntity(sessionId, "user-1", null, 500, 0.01);
        when(usageRepository.findAll()).thenReturn(List.of(e));

        var breakdown = tracker.getCostBreakdown(null);

        assertThat(breakdown).containsKey("unknown");
        assertThat(breakdown.get("unknown")).isEqualTo(0.01);
    }

    // ── getCostBreakdown with null cost ──

    @Test
    void getCostBreakdownHandlesNullCost() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e = createUsageEntity(sessionId, "user-1", "gpt-4", 500, null);
        when(usageRepository.findAll()).thenReturn(List.of(e));

        var breakdown = tracker.getCostBreakdown(null);

        assertThat(breakdown.get("gpt-4")).isEqualTo(0.0);
    }

    // ── getTotalCost aggregates ──

    @Test
    void getTotalCostSumsAllModels() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e1 = createUsageEntity(sessionId, "user-1", "gpt-4", 500, 0.01);
        UsageEntity e2 = createUsageEntity(sessionId, "user-1", "gpt-4o", 300, 0.02);
        when(usageRepository.findAll()).thenReturn(List.of(e1, e2));

        double total = tracker.getTotalCost(null);

        assertThat(total).isCloseTo(0.03, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: o1-mini prefix ──

    @Test
    void recordTurnO1MiniVariantUsesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "o1-mini-2024-09-12", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // o1-mini: input=$3.00/1M → 1M tokens = $3.00
        assertThat(captor.getValue().getCost()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: moonshot-v1-8k ──

    @Test
    void recordTurnMoonshotV18kUsesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "moonshot-v1-8k-model", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // moonshot-v1-8k: input=$1.67/1M → 1M tokens = $1.67
        assertThat(captor.getValue().getCost()).isCloseTo(1.67, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: kimi-k2 prefix ──

    @Test
    void recordTurnKimiPrefixUsesKimiK2Pricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "kimi-k2.6-something", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // kimi-k2: input=$1.00/1M → 1M tokens = $1.00
        assertThat(captor.getValue().getCost()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: deepseek-chat prefix ──

    @Test
    void recordTurnDeepseekChatUsesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "deepseek-chat-v3", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // deepseek-chat: input=$0.27/1M → 1M tokens = $0.27
        assertThat(captor.getValue().getCost()).isCloseTo(0.27, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── normalizeModel: o3-mini ──

    @Test
    void recordTurnO3MiniUsesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "o3-mini-2025-01-31", 1_000_000, 0);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        // o3-mini: input=$3.00/1M → 1M tokens = $3.00
        assertThat(captor.getValue().getCost()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.001));
    }
}