package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.DeliveryWorkItemEntity;
import com.azhukov.agent.persistence.repository.DeliveryWorkItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-1 tail: completion-batch claim parity (Hermes _flush_process_completion_batch).
 * One claim cycle returns several pending items for the SAME target only.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryWorkItemBatchClaimTest {

    @Mock
    private DeliveryWorkItemRepository repository;

    private DeliveryWorkItemService service() {
        return new DeliveryWorkItemService(repository, null);
    }

    private DeliveryWorkItemEntity item(String platform, String chatId) {
        DeliveryWorkItemEntity entity = new DeliveryWorkItemEntity();
        entity.setId(UUID.randomUUID());
        entity.setSourceType(DeliveryWorkItemService.SOURCE_CRON_EXECUTION);
        entity.setSourceId(UUID.randomUUID().toString());
        entity.setProfile("default");
        entity.setTargetKind(DeliveryWorkItemService.TARGET_PLATFORM);
        entity.setPlatform(platform);
        entity.setChatId(chatId);
        entity.setTargetHash("hash-" + UUID.randomUUID());
        entity.setPayloadText("payload");
        entity.setPayloadHash("phash");
        entity.setState(DeliveryWorkItemService.STATE_PENDING);
        entity.setAttempts(0);
        entity.setAvailableAt(Instant.now().minusSeconds(60));
        entity.setCreatedAt(Instant.now().minusSeconds(120));
        return entity;
    }

    @Test
    void batchClaimReturnsSameTargetItemsUpToMax() {
        DeliveryWorkItemEntity a = item("telegram", "100");
        DeliveryWorkItemEntity b = item("telegram", "100");
        DeliveryWorkItemEntity c = item("telegram", "100");
        when(repository.findClaimable(eq(List.of("default")), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(a, b, c));
        when(repository.claimPending(any(UUID.class), any(String.class), any(Instant.class))).thenReturn(1);
        when(repository.findById(any(UUID.class)))
            .thenReturn(Optional.of(a), Optional.of(b), Optional.of(c));

        List<DeliveryWorkItemService.ClaimedWorkItem> batch =
            service().claimNextBatch("bot-1", List.of("default"), 3);

        assertThat(batch).hasSize(3);
        assertThat(batch).allSatisfy(claimed ->
            assertThat(claimed.item().getChatId()).isEqualTo("100"));
    }

    @Test
    void batchClaimStopsAtDifferentTarget() {
        DeliveryWorkItemEntity a = item("telegram", "100");
        DeliveryWorkItemEntity otherTarget = item("telegram", "200");
        when(repository.findClaimable(eq(List.of("default")), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(a, otherTarget));
        when(repository.claimPending(eq(a.getId()), any(String.class), any(Instant.class))).thenReturn(1);
        when(repository.findById(a.getId())).thenReturn(Optional.of(a));

        List<DeliveryWorkItemService.ClaimedWorkItem> batch =
            service().claimNextBatch("bot-1", List.of("default"), 5);

        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).item().getChatId()).isEqualTo("100");
        // The other target's item must NOT be claimed — left for its own cycle.
        verify(repository, never()).claimPending(eq(otherTarget.getId()), any(String.class), any(Instant.class));
    }

    @Test
    void batchClaimRespectsMax() {
        DeliveryWorkItemEntity a = item("telegram", "100");
        DeliveryWorkItemEntity b = item("telegram", "100");
        when(repository.findClaimable(eq(List.of("default")), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(a, b));
        when(repository.claimPending(any(UUID.class), any(String.class), any(Instant.class))).thenReturn(1);
        when(repository.findById(any(UUID.class))).thenReturn(Optional.of(a));

        List<DeliveryWorkItemService.ClaimedWorkItem> batch =
            service().claimNextBatch("bot-1", List.of("default"), 1);

        assertThat(batch).hasSize(1);
    }

    @Test
    void claimNextDelegatesToBatchOfOne() {
        DeliveryWorkItemEntity a = item("telegram", "100");
        when(repository.findClaimable(eq(List.of("default")), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(a));
        when(repository.claimPending(eq(a.getId()), any(String.class), any(Instant.class))).thenReturn(1);
        when(repository.findById(a.getId())).thenReturn(Optional.of(a));

        Optional<DeliveryWorkItemService.ClaimedWorkItem> claimed =
            service().claimNext("bot-1", List.of("default"));

        assertThat(claimed).isPresent();
    }

    @Test
    void batchClaimSkipsCandidateLosingRace() {
        DeliveryWorkItemEntity lost = item("telegram", "100");
        DeliveryWorkItemEntity won = item("telegram", "100");
        when(repository.findClaimable(eq(List.of("default")), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(lost, won));
        when(repository.claimPending(eq(lost.getId()), any(String.class), any(Instant.class))).thenReturn(0);
        when(repository.claimPending(eq(won.getId()), any(String.class), any(Instant.class))).thenReturn(1);
        when(repository.findById(won.getId())).thenReturn(Optional.of(won));

        List<DeliveryWorkItemService.ClaimedWorkItem> batch =
            service().claimNextBatch("bot-1", List.of("default"), 5);

        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).item().getId()).isEqualTo(won.getId());
    }

    @Test
    void maxZeroAndEmptyProfilesReturnEmpty() {
        assertThat(service().claimNextBatch("bot-1", List.of("default"), 0)).isEmpty();
        assertThat(service().claimNextBatch("bot-1", List.of(), 5)).isEmpty();
        verify(repository, never()).findClaimable(anyList(), any(Instant.class), any(Pageable.class));
    }
}
