package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.DelegatedTaskRunEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.DelegatedTaskRunRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP-1: delegated-task terminal runs must create a durable delivery work item
 * in the same transaction, with the target resolved from the parent session's
 * origin — never from live bot state.
 */
@ExtendWith(MockitoExtension.class)
class DelegatedTaskRunDeliveryProducerTest {

    @Mock
    private DelegatedTaskRunRepository repository;

    @Mock
    private ObjectProvider<DeliveryWorkItemService> deliveryProvider;

    @Mock
    private ObjectProvider<SessionRepository> sessionRepositoryProvider;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private DeliveryWorkItemService deliveryService;

    @Test
    void finishEnqueuesDeliveryWorkForSessionWithOrigin() {
        UUID parentSessionId = UUID.randomUUID();
        SessionEntity origin = new SessionEntity();
        origin.setOriginPlatform("telegram");
        origin.setOriginChatId("100200300");
        origin.setOriginThreadId("17585");

        DelegatedTaskRunEntity run = runningRun(UUID.randomUUID(), parentSessionId);
        when(repository.findById(run.getId())).thenReturn(Optional.of(run));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepositoryProvider.getIfAvailable()).thenReturn(sessionRepository);
        when(sessionRepository.findById(parentSessionId)).thenReturn(Optional.of(origin));
        when(deliveryProvider.getIfAvailable()).thenReturn(deliveryService);
        when(deliveryService.enqueue(any())).thenAnswer(inv -> inv.getArgument(0));

        service().finish(run.getId(), "completed", java.util.Map.of("summary", "done"), null);

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(DeliveryWorkItemService.EnqueueRequest.class);
        verify(deliveryService).enqueue(requestCaptor.capture());
        DeliveryWorkItemService.EnqueueRequest request = requestCaptor.getValue();
        assertThat(request.sourceType()).isEqualTo(DeliveryWorkItemService.SOURCE_DELEGATED_TASK_RUN);
        assertThat(request.sourceId()).isEqualTo(run.getId().toString());
        assertThat(request.target()).isEqualTo("telegram:100200300:17585");
        assertThat(request.parentSessionId()).isEqualTo(parentSessionId);
        assertThat(request.payloadText()).contains("done");
    }

    @Test
    void finishSkipsDeliveryWorkWhenSessionHasNoOrigin() {
        UUID parentSessionId = UUID.randomUUID();
        SessionEntity legacy = new SessionEntity(); // no origin columns — legacy row

        DelegatedTaskRunEntity run = runningRun(UUID.randomUUID(), parentSessionId);
        when(repository.findById(run.getId())).thenReturn(Optional.of(run));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(deliveryProvider.getIfAvailable()).thenReturn(deliveryService);
        when(sessionRepositoryProvider.getIfAvailable()).thenReturn(sessionRepository);
        when(sessionRepository.findById(parentSessionId)).thenReturn(Optional.of(legacy));

        service().finish(run.getId(), "completed", "text result", null);

        verifyNoInteractions(deliveryService);
    }

    @Test
    void finishWithoutDeliveryProviderStaysEventOnly() {
        UUID parentSessionId = UUID.randomUUID();
        DelegatedTaskRunEntity run = runningRun(UUID.randomUUID(), parentSessionId);
        when(repository.findById(run.getId())).thenReturn(Optional.of(run));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        new DelegatedTaskRunService(
            repository, new ObjectMapper(), new EventService(10), null, sessionRepositoryProvider)
            .finish(run.getId(), "completed", "text result", null);

        verifyNoInteractions(deliveryService, sessionRepository);
    }

    @Test
    void enqueueFailureNeverBreaksFinish() {
        UUID parentSessionId = UUID.randomUUID();
        SessionEntity origin = new SessionEntity();
        origin.setOriginPlatform("telegram");
        origin.setOriginChatId("1");

        DelegatedTaskRunEntity run = runningRun(UUID.randomUUID(), parentSessionId);
        when(repository.findById(run.getId())).thenReturn(Optional.of(run));
        when(repository.save(any(DelegatedTaskRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepositoryProvider.getIfAvailable()).thenReturn(sessionRepository);
        when(sessionRepository.findById(parentSessionId)).thenReturn(Optional.of(origin));
        when(deliveryProvider.getIfAvailable()).thenReturn(deliveryService);
        when(deliveryService.enqueue(any())).thenThrow(new IllegalStateException("ledger unavailable"));

        DelegatedTaskRunEntity saved = service().finish(run.getId(), "completed", "result", null);

        assertThat(saved.getStatus()).isEqualTo("completed");
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    private DelegatedTaskRunService service() {
        return new DelegatedTaskRunService(
            repository, new ObjectMapper(), new EventService(10), deliveryProvider, sessionRepositoryProvider);
    }

    private DelegatedTaskRunEntity runningRun(UUID runId, UUID parentSessionId) {
        DelegatedTaskRunEntity entity = new DelegatedTaskRunEntity();
        entity.setId(runId);
        entity.setParentSessionId(parentSessionId);
        entity.setProfile("default");
        entity.setGoal("delivery parity");
        entity.setStatus("running");
        entity.setCreatedAt(java.time.Instant.now());
        return entity;
    }
}
