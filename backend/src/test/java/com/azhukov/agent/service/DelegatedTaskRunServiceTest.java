package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import com.azhukov.agent.persistence.repository.DelegatedTaskRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class DelegatedTaskRunServiceTest {

    @Mock
    private DelegatedTaskRunRepository repository;

    @Test
    void createPersistsRunningRunForParentSession() {
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);
        UUID parentSessionId = UUID.randomUUID();

        DelegatedTaskRunEntity run = service.create(parentSessionId, "work", "  inspect parity  ");

        assertThat(run.getId()).isNotNull();
        assertThat(run.getParentSessionId()).isEqualTo(parentSessionId);
        assertThat(run.getProfile()).isEqualTo("work");
        assertThat(run.getGoal()).isEqualTo("inspect parity");
        assertThat(run.getStatus()).isEqualTo("running");
        assertThat(run.getCreatedAt()).isNotNull();
        assertThat(eventService.replay("work", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.created");
                assertThat(event.sessionId()).isEqualTo(parentSessionId);
                assertThat(event.runId()).isEqualTo(run.getId());
            });
    }

    @Test
    void createIfCapacityPersistsWhenActiveRunsAreBelowCap() {
        UUID parentSessionId = UUID.randomUUID();
        when(repository.countByParentSessionIdAndStatusIn(eq(parentSessionId), any())).thenReturn(0L);
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        DelegatedTaskRunService.CreateAttempt attempt =
            service.createIfCapacity(parentSessionId, "work", "race-safe async", 1);

        assertThat(attempt.accepted()).isTrue();
        assertThat(attempt.activeCount()).isZero();
        assertThat(attempt.capacity()).isEqualTo(1);
        assertThat(attempt.run()).isNotNull();
        assertThat(attempt.run().getParentSessionId()).isEqualTo(parentSessionId);
        assertThat(eventService.replay("work", 0L, 10))
            .singleElement()
            .satisfies(event -> assertThat(event.type()).isEqualTo("delegate.created"));
    }

    @Test
    void createIfCapacityRejectsWithoutPersistingWhenActiveRunsReachCap() {
        UUID parentSessionId = UUID.randomUUID();
        when(repository.countByParentSessionIdAndStatusIn(eq(parentSessionId), any())).thenReturn(1L);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper());

        DelegatedTaskRunService.CreateAttempt attempt =
            service.createIfCapacity(parentSessionId, "default", "second async", 1);

        assertThat(attempt.accepted()).isFalse();
        assertThat(attempt.run()).isNull();
        assertThat(attempt.activeCount()).isEqualTo(1);
        assertThat(attempt.capacity()).isEqualTo(1);
        verify(repository, never()).save(any());
    }

    @Test
    void markStartedKeepsFirstChildSessionIdStableForBatchRuns() {
        UUID runId = UUID.randomUUID();
        UUID firstChild = UUID.randomUUID();
        UUID secondChild = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper());

        service.markStarted(runId, firstChild);
        service.markStarted(runId, secondChild);

        assertThat(entity.getChildSessionId()).isEqualTo(firstChild);
        assertThat(entity.getStartedAt()).isNotNull();
    }

    @Test
    void finishCompletedAfterCancelRequestStoresInterruptedTerminalStatus() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("cancel_requested");
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        service.finish(runId, "completed", "{\"results\":[]}", null);

        assertThat(entity.getStatus()).isEqualTo("interrupted");
        assertThat(entity.getError()).isEqualTo("cancelled");
        assertThat(entity.getResultJson()).isEqualTo("{\"results\":[]}");
        assertThat(entity.getCompletedAt()).isNotNull();
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> assertThat(event.type()).isEqualTo("delegate.interrupted"));
    }

    @Test
    void finishPublishesSelfContainedCompletionPayloadForGatewayDelivery() {
        UUID runId = UUID.randomUUID();
        UUID parentSessionId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, parentSessionId);
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        service.finish(runId, "completed", Map.of("results", List.of(Map.of("summary", "done"))), null);

        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.completed");
                assertThat(event.sessionId()).isEqualTo(parentSessionId);
                assertThat(event.runId()).isEqualTo(runId);
                assertThat(event.payload()).containsEntry("delegation_id", "deleg_" + runId);
                assertThat(event.payload()).containsEntry("delivery_pending", true);
                assertThat(event.payload()).containsKey("result_json");
                assertThat(event.payload().get("result")).isInstanceOf(Map.class);
            });
    }

    @Test
    void restorePendingCompletionsPublishesRestoredCompletionEvents() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("completed");
        entity.setResultJson("{\"results\":[{\"summary\":\"after restart\"}]}");
        entity.setCompletedAt(Instant.now());
        when(repository.findRestorablePendingDelivery(any(), any()))
            .thenReturn(List.of(entity));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        int restored = service.restorePendingCompletions(100);

        assertThat(restored).isEqualTo(1);
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.completed");
                assertThat(event.payload()).containsEntry("restored", true);
                assertThat(event.payload()).containsEntry("delivery_pending", true);
                assertThat(event.payload().get("result")).isInstanceOf(Map.class);
            });
    }

    @Test
    void restorePendingCompletionsDropsExpiredRowsInsteadOfReplayingForever() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("completed");
        entity.setResultJson("{\"results\":[{\"summary\":\"too old\"}]}");
        entity.setCompletedAt(Instant.now().minusSeconds(49 * 3600));
        entity.setDeliveryClaim("stale:claim");
        entity.setDeliveryClaimedAt(Instant.now().minusSeconds(600));
        when(repository.findRestorablePendingDelivery(any(), any()))
            .thenReturn(List.of(entity));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        int restored = service.restorePendingCompletions(100);

        assertThat(restored).isZero();
        assertThat(entity.getDeliveryDroppedAt()).isNotNull();
        assertThat(entity.getDeliveryClaim()).isNull();
        assertThat(entity.getDeliveryTarget()).isEqualTo("restore");
        assertThat(entity.getDeliveryError()).contains("older than 48 hours");
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.delivery_dropped");
                assertThat(event.payload()).containsEntry("delivery_state", "dropped");
                assertThat(event.payload()).containsEntry("delivery_pending", false);
                assertThat(event.payload()).containsEntry("replay_age_cap_hours", 48L);
            });
    }

    @Test
    void claimCompletionDeliveryClaimsOnlyPendingCompletedRuns() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("completed");
        entity.setCompletedAt(Instant.now());
        when(repository.claimPendingDelivery(eq(runId), any(), any(), any())).thenAnswer(invocation -> {
            entity.setDeliveryClaim(invocation.getArgument(1));
            entity.setDeliveryClaimedAt(invocation.getArgument(2));
            entity.setDeliveryAttempts(entity.getDeliveryAttempts() + 1);
            return 1;
        });
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        Optional<DelegatedTaskRunService.DeliveryClaim> claim =
            service.claimCompletionDelivery(runId, "gateway batch");

        assertThat(claim).isPresent();
        assertThat(claim.get().claimId()).startsWith("gateway_batch:");
        assertThat(entity.getDeliveryAttempts()).isEqualTo(1);
        assertThat(entity.getDeliveryClaim()).isEqualTo(claim.get().claimId());
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.delivery_claimed");
                assertThat(event.payload()).containsEntry("delivery_state", "claimed");
            });
    }

    @Test
    void claimCompletionDeliveryReturnsEmptyWhenAnotherConsumerOwnsIt() {
        UUID runId = UUID.randomUUID();
        when(repository.claimPendingDelivery(eq(runId), any(), any(), any())).thenReturn(0);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper());

        assertThat(service.claimCompletionDelivery(runId, "gateway")).isEmpty();
        verify(repository, never()).findById(runId);
    }

    @Test
    void completeDeliveryClaimAcksOnlyMatchingClaimAndPublishesDelivered() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("completed");
        entity.setCompletedAt(Instant.now());
        entity.setDeliveryClaim("gateway:claim");
        entity.setDeliveryAttempts(1);
        when(repository.completeDeliveryClaim(eq(runId), eq("gateway:claim"), eq("parent_session"), eq("idem-1"), any()))
            .thenAnswer(invocation -> {
                entity.setDeliveredAt(invocation.getArgument(4));
                entity.setDeliveryTarget(invocation.getArgument(2));
                entity.setDeliveryIdempotencyKey(invocation.getArgument(3));
                entity.setDeliveryError(null);
                entity.setDeliveryClaim(null);
                entity.setDeliveryClaimedAt(null);
                return 1;
            });
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        boolean completed = service.completeDeliveryClaim(runId, "gateway:claim", "parent_session", "idem-1");

        assertThat(completed).isTrue();
        assertThat(entity.getDeliveredAt()).isNotNull();
        assertThat(entity.getDeliveryTarget()).isEqualTo("parent_session");
        assertThat(entity.getDeliveryIdempotencyKey()).isEqualTo("idem-1");
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.delivered");
                assertThat(event.payload()).containsEntry("delivery_state", "delivered");
                assertThat(event.payload()).containsEntry("delivery_pending", false);
            });
    }

    @Test
    void releaseDeliveryClaimDropsAfterAttemptCapInsteadOfReplayingForever() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("completed");
        entity.setCompletedAt(Instant.now());
        entity.setDeliveryClaim("gateway:claim");
        entity.setDeliveryClaimedAt(Instant.now());
        entity.setDeliveryAttempts(8);
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        boolean released = service.releaseDeliveryClaim(runId, "gateway:claim", "gateway", "network timeout");

        assertThat(released).isTrue();
        assertThat(entity.getDeliveryDroppedAt()).isNotNull();
        assertThat(entity.getDeliveryClaim()).isNull();
        assertThat(entity.getDeliveryError()).isEqualTo("network timeout");
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.delivery_dropped");
                assertThat(event.payload()).containsEntry("delivery_state", "dropped");
                assertThat(event.payload()).containsEntry("delivery_pending", false);
            });
    }

    @Test
    void pendingDeliveryReturnsUndeliveredCompletedRuns() {
        UUID parentSessionId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(UUID.randomUUID(), parentSessionId);
        entity.setStatus("completed");
        entity.setCompletedAt(Instant.now());
        when(repository.findPendingDeliveryForProfile(eq(parentSessionId), eq("default"), any()))
            .thenReturn(List.of(entity));
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper());

        List<DelegatedTaskRunEntity> pending = service.pendingDelivery(parentSessionId, "default", 25);

        assertThat(pending).containsExactly(entity);
    }

    @Test
    void markDeliveredStoresTargetAndIdempotencyKey() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("completed");
        entity.setCompletedAt(Instant.now());
        entity.setDeliveryError("previous failure");
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        service.markDelivered(runId, "parent_session", "idem-1");

        assertThat(entity.getDeliveredAt()).isNotNull();
        assertThat(entity.getDeliveryTarget()).isEqualTo("parent_session");
        assertThat(entity.getDeliveryIdempotencyKey()).isEqualTo("idem-1");
        assertThat(entity.getDeliveryError()).isNull();
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> assertThat(event.type()).isEqualTo("delegate.delivered"));
    }

    @Test
    void markDeliveryFailedIncrementsAttemptsAndKeepsPending() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("completed");
        entity.setCompletedAt(Instant.now());
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        service.markDeliveryFailed(runId, "gateway", "network timeout");

        assertThat(entity.getDeliveredAt()).isNull();
        assertThat(entity.getDeliveryTarget()).isEqualTo("gateway");
        assertThat(entity.getDeliveryError()).isEqualTo("network timeout");
        assertThat(entity.getDeliveryAttempts()).isEqualTo(1);
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> assertThat(event.type()).isEqualTo("delegate.delivery_failed"));
    }

    @Test
    void markDeliveryFailedDropsAfterAttemptCap() {
        UUID runId = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, UUID.randomUUID());
        entity.setStatus("completed");
        entity.setCompletedAt(Instant.now());
        entity.setDeliveryAttempts(7);
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        EventService eventService = new EventService(10);
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper(), eventService);

        service.markDeliveryFailed(runId, "gateway", "still unroutable");

        assertThat(entity.getDeliveryAttempts()).isEqualTo(8);
        assertThat(entity.getDeliveryDroppedAt()).isNotNull();
        assertThat(eventService.replay("default", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.delivery_dropped");
                assertThat(event.payload()).containsEntry("delivery_state", "dropped");
                assertThat(event.payload()).containsEntry("delivery_pending", false);
            });
    }

    @Test
    void requestCancelIsScopedToParentSession() {
        UUID runId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        DelegatedTaskRunEntity entity = runningRun(runId, owner);
        when(repository.findById(runId)).thenReturn(Optional.of(entity));
        DelegatedTaskRunService service = new DelegatedTaskRunService(repository, new ObjectMapper());

        assertThatThrownBy(() -> service.requestCancel(runId, UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("was not found for this session");
    }

    private DelegatedTaskRunEntity runningRun(UUID runId, UUID parentSessionId) {
        DelegatedTaskRunEntity entity = new DelegatedTaskRunEntity();
        entity.setId(runId);
        entity.setParentSessionId(parentSessionId);
        entity.setProfile("default");
        entity.setGoal("goal");
        entity.setStatus("running");
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
