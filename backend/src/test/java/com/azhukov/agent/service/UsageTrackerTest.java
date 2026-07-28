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