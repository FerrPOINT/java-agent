package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-4.6: session prune over persisted fields only. Ended sessions matching
 * filters are deleted through the existing delete path; live sessions are
 * never touched; dry-run reports without deleting.
 */
@ExtendWith(MockitoExtension.class)
class SessionPruneServiceTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private SessionQueryService sessionQueryService;

    private SessionEntity ended(String endReason, Instant lastActive, int messages) {
        SessionEntity entity = new SessionEntity();
        entity.setId(UUID.randomUUID());
        entity.setEndReason(endReason);
        entity.setLastActive(lastActive);
        entity.setMessageCount(messages);
        entity.setTitle("old session");
        entity.setProfile("default");
        return entity;
    }

    private SessionPruneService service() {
        return new SessionPruneService(sessionRepository, sessionQueryService);
    }

    @Test
    void deletesOnlyEndedSessionsOlderThanCutoff() {
        SessionEntity oldEnough = ended("compression", Instant.now().minusSeconds(90 * 86400 + 3600), 5);
        SessionEntity tooFresh = ended("compression", Instant.now().minusSeconds(3600), 5);
        when(sessionRepository.listEndedSessions(isNull(), any(Pageable.class)))
            .thenReturn(List.of(oldEnough, tooFresh));
        when(sessionQueryService.deleteSession(oldEnough.getId())).thenReturn(true);

        var result = service().prune(new SessionPruneService.PruneRequest(
            null, null, null, null, null, null, null, null, null,
            null, null, false, false));

        assertThat(result.pruned()).hasSize(1);
        assertThat(result.pruned().get(0)).containsEntry("id", oldEnough.getId().toString());
        assertThat(result.deletedMessages()).isEqualTo(5);
        verify(sessionQueryService, never()).deleteSession(tooFresh.getId());
    }

    @Test
    void dryRunReportsWithoutDeleting() {
        SessionEntity oldEnough = ended("new_session", Instant.now().minusSeconds(120 * 86400), 3);
        when(sessionRepository.listEndedSessions(isNull(), any(Pageable.class)))
            .thenReturn(List.of(oldEnough));

        var result = service().prune(new SessionPruneService.PruneRequest(
            null, null, null, null, null, null, null, null, null,
            null, null, false, true));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.pruned()).hasSize(1);
        verify(sessionQueryService, never()).deleteSession(any(UUID.class));
    }

    @Test
    void endReasonAndSourceFiltersNarrowMatches() {
        SessionEntity compression = ended("compression", Instant.now().minusSeconds(120 * 86400), 2);
        SessionEntity userExit = ended("new_session", Instant.now().minusSeconds(120 * 86400), 2);
        userExit.setSource("telegram");
        when(sessionRepository.listEndedSessions(eq("default"), any(Pageable.class)))
            .thenReturn(List.of(compression, userExit));

        var result = service().prune(new SessionPruneService.PruneRequest(
            "default", "telegram", null, null, null, null, null, null, null,
            null, null, false, false));

        assertThat(result.pruned()).hasSize(1);
        assertThat(result.pruned().get(0)).containsEntry("end_reason", "new_session");
    }

    @Test
    void messageBoundsFilter() {
        SessionEntity small = ended("idle_timeout", Instant.now().minusSeconds(120 * 86400), 2);
        SessionEntity big = ended("idle_timeout", Instant.now().minusSeconds(120 * 86400), 500);
        when(sessionRepository.listEndedSessions(isNull(), any(Pageable.class)))
            .thenReturn(List.of(small, big));

        var result = service().prune(new SessionPruneService.PruneRequest(
            null, null, null, null, null, null, null, null, null,
            10, 100, false, true));

        assertThat(result.pruned()).isEmpty();
    }

    @Test
    void archivedSessionsSkippedUnlessRequested() {
        SessionEntity archived = ended("compression", Instant.now().minusSeconds(120 * 86400), 2);
        archived.setArchived(true);
        when(sessionRepository.listEndedSessions(isNull(), any(Pageable.class)))
            .thenReturn(List.of(archived));

        var skipped = service().prune(new SessionPruneService.PruneRequest(
            null, null, null, null, null, null, null, null, null,
            null, null, false, true));
        assertThat(skipped.pruned()).isEmpty();

        var included = service().prune(new SessionPruneService.PruneRequest(
            null, null, null, null, null, null, null, null, null,
            null, null, true, true));
        assertThat(included.pruned()).hasSize(1);
    }

    @Test
    void unsupportedFiltersAreCatalogued() {
        Map<String, Object> raw = Map.of("cwd_prefix", "/opt/dev", "branch", "feature-1");
        var request = new SessionPruneService.PruneRequest(
            null, null, null, null, null, null, null, null, null,
            null, null, false, true);
        assertThat(request.unsupportedRequested(raw))
            .containsOnlyKeys("cwd_prefix", "branch");
    }
}
