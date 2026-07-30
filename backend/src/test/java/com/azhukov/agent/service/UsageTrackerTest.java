package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.repository.UsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageTrackerTest {

    @Mock
    private UsageRepository usageRepository;

    private UsageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new UsageTracker(usageRepository);
    }

    @Test
    void recordTurn_persistsUsageEntity() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "gpt-4", 100, 50);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        UsageEntity saved = captor.getValue();
        assertThat(saved.getSessionId()).isEqualTo(sessionId);
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getModel()).isEqualTo("gpt-4");
        assertThat(saved.getPromptTokens()).isEqualTo(100);
        assertThat(saved.getCompletionTokens()).isEqualTo(50);
        assertThat(saved.getTotalTokens()).isEqualTo(150);
        assertThat(saved.getCost()).isNotNull().isPositive();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    // S10: Per-model pricing tests
    @Test
    void recordTurn_gpt4o_usesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "gpt-4o", 1_000_000, 500_000);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        UsageEntity saved = captor.getValue();
        // gpt-4o: $2.50/1M input, $10.00/1M output
        // 1M input * $2.50 + 500K output * $10.00 = $2.50 + $5.00 = $7.50
        assertThat(saved.getCost()).isCloseTo(7.5, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void recordTurn_claudeSonnet_usesCorrectPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "claude-sonnet-4-5", 1_000_000, 1_000_000);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        UsageEntity saved = captor.getValue();
        // claude-sonnet-4-5: $3.00/1M input, $15.00/1M output
        // 1M input * $3.00 + 1M output * $15.00 = $18.00
        assertThat(saved.getCost()).isCloseTo(18.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void recordTurn_unknownModel_usesDefaultPricing() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "unknown-model-xyz", 1_000_000, 1_000_000);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        UsageEntity saved = captor.getValue();
        // Default: $0.50/1M input, $1.50/1M output
        // 1M * $0.50 + 1M * $1.50 = $2.00
        assertThat(saved.getCost()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void recordTurn_withCacheTokens() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "gpt-4o", 1000, 500, 2000, 1000);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        UsageEntity saved = captor.getValue();
        assertThat(saved.getCacheReadTokens()).isEqualTo(2000);
        assertThat(saved.getCacheWriteTokens()).isEqualTo(1000);
        // Cost should include cache tokens
        // gpt-4o: $2.50/1M input, $10.00/1M output, $1.25/1M cache read, cache write = $2.50*1.25
        // 1000/1M * $2.50 + 500/1M * $10.00 + 2000/1M * $1.25 + 1000/1M * $3.125
        double expectedCost = 0.0025 + 0.005 + 0.0025 + 0.003125;
        assertThat(saved.getCost()).isCloseTo(expectedCost, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void recordTurn_localModel_hasZeroCost() {
        UUID sessionId = UUID.randomUUID();
        tracker.recordTurn(sessionId, "user-1", "llama-3.1-70b", 1000, 500);

        ArgumentCaptor<UsageEntity> captor = ArgumentCaptor.forClass(UsageEntity.class);
        verify(usageRepository).save(captor.capture());

        UsageEntity saved = captor.getValue();
        assertThat(saved.getCost()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void getSessionUsage_aggregatesRecords() {
        UUID sessionId = UUID.randomUUID();
        UsageEntity e1 = makeEntity(sessionId, "gpt-4", 100, 50);
        UsageEntity e2 = makeEntity(sessionId, "gpt-4", 200, 100);
        when(usageRepository.findBySessionId(sessionId)).thenReturn(List.of(e1, e2));

        UsageDto dto = tracker.getSessionUsage(sessionId);

        assertThat(dto.sessionId()).isEqualTo(sessionId);
        assertThat(dto.messageCount()).isEqualTo(2);
        assertThat(dto.tokenEstimate()).isEqualTo(450); // 150 + 300
    }

    @Test
    void getInsights_aggregatesByModel() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UsageEntity e1 = makeEntity(s1, "gpt-4", 100, 50);
        UsageEntity e2 = makeEntity(s1, "gpt-4", 200, 100);
        UsageEntity e3 = makeEntity(s2, "claude-3", 300, 200);
        when(usageRepository.findAll()).thenReturn(List.of(e1, e2, e3));

        InsightsDto dto = tracker.getInsights(null);

        assertThat(dto.totalTokens()).isEqualTo(950); // 150 + 300 + 500
        assertThat(dto.totalMessages()).isEqualTo(3);
        assertThat(dto.byModel()).containsEntry("gpt-4", 450);
        assertThat(dto.byModel()).containsEntry("claude-3", 500);
    }

    @Test
    void getInsights_emptyData_returnsZeros() {
        when(usageRepository.findAll()).thenReturn(List.of());

        InsightsDto dto = tracker.getInsights(null);

        assertThat(dto.totalTokens()).isZero();
        assertThat(dto.totalMessages()).isZero();
        assertThat(dto.byModel()).isEmpty();
    }

    @Test
    void recordTurn_exceptionDoesNotThrow() {
        UUID sessionId = UUID.randomUUID();
        when(usageRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        // Should not throw
        tracker.recordTurn(sessionId, "user-1", "gpt-4", 100, 50);
    }

    // S10: Cost breakdown tests
    @Test
    void getCostBreakdown_returnsCostPerModel() {
        UUID s1 = UUID.randomUUID();
        UsageEntity e1 = makeEntity(s1, "gpt-4o", 100, 50);
        e1.setCost(1.5);
        UsageEntity e2 = makeEntity(s1, "claude-sonnet-4-5", 200, 100);
        e2.setCost(3.0);
        when(usageRepository.findAll()).thenReturn(List.of(e1, e2));

        var breakdown = tracker.getCostBreakdown(null);
        assertThat(breakdown).containsEntry("gpt-4o", 1.5);
        assertThat(breakdown).containsEntry("claude-sonnet-4-5", 3.0);
        assertThat(tracker.getTotalCost(null)).isCloseTo(4.5, org.assertj.core.data.Offset.offset(0.001));
    }

    private UsageEntity makeEntity(UUID sessionId, String model, int prompt, int completion) {
        UsageEntity e = new UsageEntity();
        e.setId(UUID.randomUUID());
        e.setSessionId(sessionId);
        e.setUserId("user-1");
        e.setModel(model);
        e.setPromptTokens(prompt);
        e.setCompletionTokens(completion);
        e.setTotalTokens(prompt + completion);
        e.setCost(0.01);
        e.setCreatedAt(Instant.now());
        return e;
    }
}