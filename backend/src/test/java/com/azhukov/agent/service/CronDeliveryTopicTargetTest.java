package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP-k (Hermes delivery-into-origin-topic parity): cron/delegate answers
 * land in the forum topic the session was born in.
 */
@ExtendWith(MockitoExtension.class)
class CronDeliveryTopicTargetTest {

    @Mock private ObjectProvider<SessionRepository> sessionRepositoryProvider;
    @Mock private SessionRepository sessionRepository;

    private CronJobService service;
    private CronJobEntity job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new CronJobService(
            null, null, null, null, null, null, null, null,
            (ObjectProvider<DeliveryWorkItemService>) mock(ObjectProvider.class),
            sessionRepositoryProvider, null);
        job = new CronJobEntity();
        lenient().when(sessionRepositoryProvider.getIfAvailable()).thenReturn(sessionRepository);
    }

    private SessionEntity sessionWithThread(String threadId) {
        SessionEntity s = new SessionEntity();
        s.setOriginPlatform("telegram");
        s.setOriginChatId("100");
        s.setOriginThreadId(threadId);
        return s;
    }

    @Test
    void bareTargetGainsSessionOriginThread() {
        job.setDeliverTo("telegram:100");
        job.setLastRunSessionId(UUID.randomUUID());
        when(sessionRepository.findById(any(UUID.class)))
            .thenReturn(Optional.of(sessionWithThread("777")));

        // direct call path: resolveDeliveryTarget is private; test via withSessionOriginThread
        String target = service.withSessionOriginThread(job, "telegram:100");
        assertThat(target).isEqualTo("telegram:100:777");
    }

    @Test
    void explicitThreePartTargetIsNotModified() {
        job.setDeliverTo("telegram:100:999");
        job.setLastRunSessionId(UUID.randomUUID());
        lenient().when(sessionRepository.findById(any(UUID.class)))
            .thenReturn(Optional.of(sessionWithThread("777")));

        String target = service.withSessionOriginThread(job, "telegram:100:999");
        assertThat(target).isEqualTo("telegram:100:999");
    }

    @Test
    void sessionWithoutThreadKeepsPlainTarget() {
        job.setDeliverTo("telegram:100");
        job.setLastRunSessionId(UUID.randomUUID());
        when(sessionRepository.findById(any(UUID.class)))
            .thenReturn(Optional.of(sessionWithThread(null)));

        assertThat(service.withSessionOriginThread(job, "telegram:100"))
            .isEqualTo("telegram:100");
    }

    @Test
    void missingSessionRepositoryKeepsTarget() {
        when(sessionRepositoryProvider.getIfAvailable()).thenReturn(null);
        job.setDeliverTo("telegram:100");
        job.setLastRunSessionId(UUID.randomUUID());

        assertThat(service.withSessionOriginThread(job, "telegram:100"))
            .isEqualTo("telegram:100");
    }

    @Test
    void repositoryFailureDegradesToPlainTarget() {
        job.setDeliverTo("telegram:100");
        job.setLastRunSessionId(UUID.randomUUID());
        when(sessionRepository.findById(any(UUID.class))).thenThrow(new RuntimeException("db down"));

        assertThat(service.withSessionOriginThread(job, "telegram:100"))
            .isEqualTo("telegram:100");
    }
}
