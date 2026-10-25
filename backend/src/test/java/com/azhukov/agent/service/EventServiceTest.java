package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventServiceTest {

    @Test
    void publishDerivesOwnerFromPersistedSessionAndScopesReplay() {
        SessionRepository sessions = mock(SessionRepository.class);
        UUID ownedSessionId = UUID.randomUUID();
        SessionEntity session = new SessionEntity();
        session.setId(ownedSessionId);
        session.setUserId("user-a");
        when(sessions.findById(ownedSessionId)).thenReturn(java.util.Optional.of(session));
        EventService service = new EventService(10, sessions);

        EventService.EventEnvelope owned = service.publish(
            "delegate.completed", "default", ownedSessionId, UUID.randomUUID(), Map.of());
        service.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of(), "user-b");

        assertThat(owned.userId()).isEqualTo("user-a");
        assertThat(service.replay(null, 0L, 10, "user-a"))
            .extracting(EventService.EventEnvelope::sessionId)
            .containsExactly(ownedSessionId);
    }

    @Test
    void scopedLatestCursorDoesNotRevealForeignEvents() {
        EventService service = new EventService(10);
        service.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of(), "user-a");
        service.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of(), "user-b");

        assertThat(service.latestCursor("default", "user-a")).isEqualTo(1L);
        assertThat(service.latestCursor("default", "user-b")).isEqualTo(2L);
        assertThat(service.latestCursor("default", "user-c")).isZero();
    }

    @Test
    void legacyEventsWithoutOwnerAreHiddenFromScopedReads() {
        EventService service = new EventService(10);
        service.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of());

        assertThat(service.replay(null, 0L, 10, "user-a")).isEmpty();
        assertThat(service.replay(null, 0L, 10)).hasSize(1);
    }

    @Test
    void publishAssignsMonotonicCursorAndStableEventId() {
        EventService service = new EventService(10);
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        EventService.EventEnvelope first = service.publish("delegate.created", "work", sessionId, runId, Map.of("ok", true));
        EventService.EventEnvelope second = service.publish("delegate.completed", "work", sessionId, runId, Map.of());

        assertThat(first.cursor()).isEqualTo(1L);
        assertThat(first.id()).isEqualTo("evt_0000000000000001");
        assertThat(first.profile()).isEqualTo("work");
        assertThat(first.sessionId()).isEqualTo(sessionId);
        assertThat(first.runId()).isEqualTo(runId);
        assertThat(first.payload()).containsEntry("ok", true);
        assertThat(second.cursor()).isEqualTo(2L);
        assertThat(service.latestCursor()).isEqualTo(2L);
    }

    @Test
    void replayAfterCursorReturnsOnlyNewerEvents() {
        EventService service = new EventService(10);
        service.publish("delegate.created", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of());
        service.publish("delegate.started", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of());
        service.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of());

        assertThat(service.replay("default", 1L, 10))
            .extracting(EventService.EventEnvelope::type)
            .containsExactly("delegate.started", "delegate.completed");
    }

    @Test
    void replayIsProfileScopedToPreventMisdelivery() {
        EventService service = new EventService(10);
        service.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "default"));
        service.publish("delegate.completed", "work", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "work"));

        assertThat(service.replay("work", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.profile()).isEqualTo("work");
                assertThat(event.payload()).containsEntry("name", "work");
            });
        assertThat(service.replay(null, 0L, 10)).hasSize(2);
    }

    @Test
    void replayTreatsAllAsLiteralProfileScope() {
        EventService service = new EventService(10);
        service.publish("delegate.completed", "all", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "all"));
        service.publish("delegate.completed", "default", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "default"));

        assertThat(service.replay("all", 0L, 10))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.profile()).isEqualTo("all");
                assertThat(event.payload()).containsEntry("name", "all");
            });
    }

    @Test
    void boundedBufferEvictsOldestEvents() {
        EventService service = new EventService(2);
        service.publish("first", "default", null, null, Map.of());
        service.publish("second", "default", null, null, Map.of());
        service.publish("third", "default", null, null, Map.of());

        assertThat(service.replay(null, 0L, 10))
            .extracting(EventService.EventEnvelope::type)
            .containsExactly("second", "third");
    }

    @Test
    void invalidProfileFailsClosed() {
        EventService service = new EventService(10);

        assertThatThrownBy(() -> service.publish("delegate.created", "../work", null, null, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid profile name");
    }

    @Test
    void awaitReturnsEventsPublishedAfterCursor() throws Exception {
        EventService service = new EventService(10);
        CompletableFuture<java.util.List<EventService.EventEnvelope>> future = CompletableFuture.supplyAsync(() -> {
            try {
                return service.await("work", 0L, 10, Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        });

        service.publish("delegate.completed", "work", UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "work"));

        assertThat(future.get(1, TimeUnit.SECONDS))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.type()).isEqualTo("delegate.completed");
                assertThat(event.profile()).isEqualTo("work");
            });
    }

    @Test
    void awaitReturnsEmptyOnTimeout() throws Exception {
        EventService service = new EventService(10);

        assertThat(service.await("default", 0L, 10, Duration.ofMillis(1))).isEmpty();
    }
}
