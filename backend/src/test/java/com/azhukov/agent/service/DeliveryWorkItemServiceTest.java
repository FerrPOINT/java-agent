package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.DeliveryWorkItemEntity;
import com.azhukov.agent.persistence.repository.DeliveryWorkItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryWorkItemServiceTest {

    @Mock
    private DeliveryWorkItemRepository repository;

    private DeliveryWorkItemService service() {
        return new DeliveryWorkItemService(repository);
    }

    // ── enqueue validation ──────────────────────────────────────────────

    @Test
    void enqueueNormalizesPlatformTargetAndComputesHashes() {
        when(repository.findBySourceTypeAndSourceIdAndTargetHash(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(DeliveryWorkItemEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryWorkItemEntity item = service().enqueue(new DeliveryWorkItemService.EnqueueRequest(
            DeliveryWorkItemService.SOURCE_CRON_EXECUTION, "42", "Work", "user-1",
            UUID.randomUUID(), " Telegram:12345 ", "report body"));

        ArgumentCaptor<DeliveryWorkItemEntity> captor = ArgumentCaptor.forClass(DeliveryWorkItemEntity.class);
        verify(repository).save(captor.capture());
        DeliveryWorkItemEntity saved = captor.getValue();
        assertThat(saved.getTargetKind()).isEqualTo(DeliveryWorkItemService.TARGET_PLATFORM);
        assertThat(saved.getPlatform()).isEqualTo("telegram");
        assertThat(saved.getChatId()).isEqualTo("12345");
        assertThat(saved.getProfile()).isEqualTo("work");
        assertThat(saved.getState()).isEqualTo(DeliveryWorkItemService.STATE_PENDING);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getPayloadHash()).hasSize(64);
        assertThat(saved.getTargetHash()).hasSize(64);
        assertThat(saved.getAvailableAt()).isNotNull();
        assertThat(item).isSameAs(saved);
    }

    @Test
    void enqueueParsesThreadTarget() {
        when(repository.findBySourceTypeAndSourceIdAndTargetHash(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(DeliveryWorkItemEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryWorkItemEntity saved = service().enqueue(request("delegated-run-1", "telegram:-100:17585"));

        assertThat(saved.getThreadId()).isEqualTo("17585");
        assertThat(saved.getTargetKind()).isEqualTo(DeliveryWorkItemService.TARGET_PLATFORM);
    }

    @Test
    void enqueueAcceptsLocalTargetWithoutPlatformFields() {
        when(repository.findBySourceTypeAndSourceIdAndTargetHash(any(), any(), any())).thenReturn(Optional.empty());
        when(repository.save(any(DeliveryWorkItemEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DeliveryWorkItemEntity saved = service().enqueue(request("run-1", "local"));

        assertThat(saved.getTargetKind()).isEqualTo(DeliveryWorkItemService.TARGET_LOCAL);
        assertThat(saved.getPlatform()).isNull();
        assertThat(saved.getChatId()).isNull();
        assertThat(saved.getThreadId()).isNull();
    }

    @Test
    void enqueueIsIdempotentPerSourceAndTarget() {
        DeliveryWorkItemEntity existing = new DeliveryWorkItemEntity();
        existing.setId(UUID.randomUUID());
        when(repository.findBySourceTypeAndSourceIdAndTargetHash(any(), any(), any()))
            .thenReturn(Optional.of(existing));

        DeliveryWorkItemEntity result = service().enqueue(request("cron-9", "telegram:1"));

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void enqueueRejectsMalformedTarget() {
        assertThatThrownBy(() -> service().enqueue(request("cron-9", "origin")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delivery target");
    }

    @Test
    void enqueueRejectsBarePlatformWithoutChat() {
        assertThatThrownBy(() -> service().enqueue(request("cron-9", "telegram:")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delivery target");
    }

    @Test
    void enqueueRejectsBlankPayload() {
        assertThatThrownBy(() -> service().enqueue(
            new DeliveryWorkItemService.EnqueueRequest(
                DeliveryWorkItemService.SOURCE_DELEGATED_TASK_RUN, "cron-9", null, null, null,
                "telegram:1", "   ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("delivery payload");
    }

    @Test
    void enqueueRejectsUnknownSourceType() {
        assertThatThrownBy(() -> service().enqueue(new DeliveryWorkItemService.EnqueueRequest(
            "heartbeat", "1", "default", null, null, "telegram:1", "text")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported delivery source_type");
    }

    // ── claim ───────────────────────────────────────────────────────────

    @Test
    void claimNextClaimsOldestPendingForProfile() {
        DeliveryWorkItemEntity candidate = pendingItem("default");
        when(repository.findClaimable(eq(List.of("default")), any(Instant.class), any()))
            .thenReturn(List.of(candidate));
        when(repository.claimPending(eq(candidate.getId()), any(String.class), any(Instant.class))).thenReturn(1);
        when(repository.findById(candidate.getId())).thenReturn(Optional.of(candidate));

        Optional<DeliveryWorkItemService.ClaimedWorkItem> claimed =
            service().claimNext("bot-1", List.of("default"));

        assertThat(claimed).isPresent();
        assertThat(claimed.get().claimToken()).startsWith("bot-1:");
        verify(repository).claimPending(eq(candidate.getId()), any(String.class), any(Instant.class));
    }

    @Test
    void claimNextReturnsEmptyWithoutProfiles() {
        assertThat(service().claimNext("bot-1", List.of())).isEmpty();
        verify(repository, never()).findClaimable(any(), any(), any());
    }

    @Test
    void claimNextSkipsCandidateLosingRaceAndTriesNext() {
        DeliveryWorkItemEntity lost = pendingItem("default");
        DeliveryWorkItemEntity won = pendingItem("default");
        when(repository.findClaimable(eq(List.of("default")), any(Instant.class), any()))
            .thenReturn(List.of(lost, won));
        when(repository.claimPending(eq(lost.getId()), any(), any())).thenReturn(0);
        when(repository.claimPending(eq(won.getId()), any(), any())).thenReturn(1);
        when(repository.findById(won.getId())).thenReturn(Optional.of(won));

        Optional<DeliveryWorkItemService.ClaimedWorkItem> claimed =
            service().claimNext("bot-1", List.of("default"));

        assertThat(claimed).isPresent();
        assertThat(claimed.get().item().getId()).isEqualTo(won.getId());
    }

    // ── terminal transitions ────────────────────────────────────────────

    @Test
    void markDeliveredRequiresValidClaimToken() {
        assertThat(service().markDelivered(UUID.randomUUID(), "", receipt("m1"))).isFalse();
        assertThat(service().markDelivered(null, "tok", receipt("m1"))).isFalse();
        assertThat(service().markDelivered(UUID.randomUUID(), "tok", null)).isFalse();
        verify(repository, never()).markDelivered(any(), any(), any(), any(), any());
    }

    @Test
    void markDeliveredDelegatesToConditionalUpdate() {
        UUID id = UUID.randomUUID();
        when(repository.markDelivered(eq(id), eq("tok"), any(Instant.class), eq("m1"), eq("idem-1"))).thenReturn(1);

        assertThat(service().markDelivered(id, "tok", receipt("m1"))).isTrue();
    }

    @Test
    void releaseKnownFailureRedactsSecretsAndSchedulesRetry() {
        UUID id = UUID.randomUUID();
        DeliveryWorkItemEntity claimed = pendingItem("default");
        claimed.setState(DeliveryWorkItemService.STATE_CLAIMED);
        claimed.setClaimToken("tok");
        claimed.setAttempts(1);
        when(repository.findById(id)).thenReturn(Optional.of(claimed));
        when(repository.releaseKnownFailure(eq(id), eq("tok"), any(Instant.class), eq("rate_limited"), any()))
            .thenReturn(1);

        assertThat(service().releaseKnownFailure(id, "tok", "RATE_LIMITED", "token=abc123 retry later")).isTrue();

        verify(repository).releaseKnownFailure(
            eq(id), eq("tok"), any(Instant.class), eq("rate_limited"), eq("token=[redacted] retry later"));
    }

    @Test
    void releaseKnownFailureDropsWorkAfterAttemptCap() {
        UUID id = UUID.randomUUID();
        DeliveryWorkItemEntity claimed = pendingItem("default");
        claimed.setState(DeliveryWorkItemService.STATE_CLAIMED);
        claimed.setClaimToken("tok");
        claimed.setAttempts(8);
        when(repository.findById(id)).thenReturn(Optional.of(claimed));
        when(repository.markTerminal(eq(id), eq("tok"), eq(DeliveryWorkItemService.STATE_DROPPED),
            any(Instant.class), any(), any())).thenReturn(1);

        assertThat(service().releaseKnownFailure(id, "tok", "fatal", "boom")).isTrue();

        verify(repository, never()).releaseKnownFailure(any(), any(), any(), any(), any());
    }

    @Test
    void markUnknownAndDropAreTerminalAndIdempotentByToken() {
        UUID id = UUID.randomUUID();
        when(repository.markTerminal(eq(id), eq("tok"), eq(DeliveryWorkItemService.STATE_UNKNOWN),
            any(Instant.class), any(), any())).thenReturn(1);
        when(repository.markTerminal(eq(id), eq("tok"), eq(DeliveryWorkItemService.STATE_DROPPED),
            any(Instant.class), any(), any())).thenReturn(1);

        assertThat(service().markUnknown(id, "tok", "ambiguous", null)).isTrue();
        assertThat(service().drop(id, "tok", "expired", null)).isTrue();
        // Wrong token cannot terminalize another consumer's claim.
        assertThat(service().drop(id, "other", "expired", null)).isFalse();
    }

    @Test
    void markLocalDeliveredOnlyTerminatesPendingLocalWork() {
        UUID id = UUID.randomUUID();
        DeliveryWorkItemEntity local = pendingItem("default");
        local.setTargetKind(DeliveryWorkItemService.TARGET_LOCAL);
        local.setTargetHash("local");
        when(repository.findById(id)).thenReturn(Optional.of(local));

        assertThat(service().markLocalDelivered(id)).isTrue();
        assertThat(local.getState()).isEqualTo(DeliveryWorkItemService.STATE_LOCAL_DELIVERED);
        assertThat(local.getDeliveredAt()).isNotNull();

        DeliveryWorkItemEntity platform = pendingItem("default");
        assertThat(service().markLocalDelivered(platform.getId())).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private DeliveryWorkItemService.EnqueueRequest request(String sourceId, String target) {
        return new DeliveryWorkItemService.EnqueueRequest(
            DeliveryWorkItemService.SOURCE_DELEGATED_TASK_RUN, sourceId, null, null, null, target, "payload text");
    }

    private DeliveryWorkItemService.DeliveryReceipt receipt(String messageId) {
        return new DeliveryWorkItemService.DeliveryReceipt(messageId, "idem-1");
    }

    private DeliveryWorkItemEntity pendingItem(String profile) {
        DeliveryWorkItemEntity entity = new DeliveryWorkItemEntity();
        entity.setId(UUID.randomUUID());
        entity.setSourceType(DeliveryWorkItemService.SOURCE_CRON_EXECUTION);
        entity.setSourceId(UUID.randomUUID().toString());
        entity.setProfile(profile);
        entity.setTargetKind(DeliveryWorkItemService.TARGET_PLATFORM);
        entity.setPlatform("telegram");
        entity.setChatId("1");
        entity.setTargetHash("hash-" + UUID.randomUUID());
        entity.setPayloadText("payload");
        entity.setPayloadHash("phash");
        entity.setState(DeliveryWorkItemService.STATE_PENDING);
        entity.setAttempts(0);
        entity.setAvailableAt(Instant.now().minusSeconds(60));
        entity.setCreatedAt(Instant.now().minusSeconds(120));
        return entity;
    }
}
