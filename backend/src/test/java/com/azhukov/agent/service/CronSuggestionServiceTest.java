package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CronSuggestionService}.
 */
@ExtendWith(MockitoExtension.class)
class CronSuggestionServiceTest {

    @Mock private CronJobService cronJobService;
    private CronSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new CronSuggestionService(cronJobService);
    }

    @Test
    void addSuggestion_isPending() {
        var spec = new CronSuggestionService.JobSpec("daily-report", "0 9 * * *", "Generate report", null, null);
        var record = service.addSuggestion("Daily Report", "Generates a daily report", "catalog", spec, "daily-report-key");

        assertThat(record).isNotNull();
        assertThat(record.status()).isEqualTo("pending");
        assertThat(record.title()).isEqualTo("Daily Report");
        assertThat(record.source()).isEqualTo("catalog");
        assertThat(record.dedupKey()).isEqualTo("daily-report-key");
    }

    @Test
    void addSuggestion_duplicateDedupKey_returnsNull() {
        var spec = new CronSuggestionService.JobSpec("daily-report", "0 9 * * *", "Generate report", null, null);
        service.addSuggestion("Daily Report", "desc", "catalog", spec, "dup-key");
        var second = service.addSuggestion("Daily Report 2", "desc", "catalog", spec, "dup-key");
        assertThat(second).isNull();
    }

    @Test
    void addSuggestion_emptyTitle_throws() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            service.addSuggestion("", "desc", "catalog", spec, "key")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addSuggestion_emptyDedupKey_throws() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            service.addSuggestion("Title", "desc", "catalog", spec, "")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addSuggestion_maxPendingDropsExcess() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        for (int i = 0; i < 5; i++) {
            service.addSuggestion("Suggestion " + i, "desc", "catalog", spec, "key-" + i);
        }
        // 6th should be dropped
        var sixth = service.addSuggestion("Suggestion 5", "desc", "catalog", spec, "key-5");
        assertThat(sixth).isNull();
        assertThat(service.listPending()).hasSize(5);
    }

    @Test
    void listPending_returnsPendingOnly() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        service.addSuggestion("Pending 1", "desc", "catalog", spec, "key-1");
        service.addSuggestion("Pending 2", "desc", "catalog", spec, "key-2");

        var pending = service.listPending();
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).title()).isEqualTo("Pending 1");
        assertThat(pending.get(1).title()).isEqualTo("Pending 2");
    }

    @Test
    void getSuggestion_byId() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        var record = service.addSuggestion("Find Me", "desc", "catalog", spec, "key");

        var found = service.getSuggestion(record.id());
        assertThat(found).isNotNull();
        assertThat(found.title()).isEqualTo("Find Me");
    }

    @Test
    void getSuggestion_byIndex() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        service.addSuggestion("First", "desc", "catalog", spec, "key-1");
        service.addSuggestion("Second", "desc", "catalog", spec, "key-2");

        var found = service.getSuggestion("2");
        assertThat(found).isNotNull();
        assertThat(found.title()).isEqualTo("Second");
    }

    @Test
    void getSuggestion_byTitle() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        service.addSuggestion("My Suggestion", "desc", "catalog", spec, "key");

        var found = service.getSuggestion("my suggestion");
        assertThat(found).isNotNull();
        assertThat(found.title()).isEqualTo("My Suggestion");
    }

    @Test
    void getSuggestion_notFound_returnsNull() {
        assertThat(service.getSuggestion("nonexistent")).isNull();
    }

    @Test
    void acceptSuggestion_createsCronJob() {
        var spec = new CronSuggestionService.JobSpec("daily-report", "0 9 * * *", "Generate report", "telegram", null);
        var record = service.addSuggestion("Daily Report", "desc", "catalog", spec, "key");

        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("daily-report");
        when(cronJobService.create(any(), any(), any(), any(), any())).thenReturn(entity);

        var created = service.acceptSuggestion(record.id());

        assertThat(created).isNotNull();
        assertThat(created.getName()).isEqualTo("daily-report");
        verify(cronJobService).create("daily-report", "0 9 * * *", "Generate report", "telegram", null);

        // Suggestion should now be accepted
        var all = service.listAll();
        assertThat(all.get(0).status()).isEqualTo("accepted");
    }

    @Test
    void acceptSuggestion_notPending_returnsNull() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        var record = service.addSuggestion("Title", "desc", "catalog", spec, "key");
        service.dismissSuggestion(record.id());

        var result = service.acceptSuggestion(record.id());
        assertThat(result).isNull();
        verify(cronJobService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void acceptSuggestion_notFound_returnsNull() {
        var result = service.acceptSuggestion("nonexistent");
        assertThat(result).isNull();
    }

    @Test
    void dismissSuggestion_latchesDedupKey() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        var record = service.addSuggestion("Dismiss Me", "desc", "catalog", spec, "latch-key");

        boolean dismissed = service.dismissSuggestion(record.id());
        assertThat(dismissed).isTrue();

        // Same dedup_key should not be re-offered
        var second = service.addSuggestion("Dismiss Me Again", "desc", "catalog", spec, "latch-key");
        assertThat(second).isNull();
    }

    @Test
    void dismissSuggestion_notFound_returnsFalse() {
        boolean result = service.dismissSuggestion("nonexistent");
        assertThat(result).isFalse();
    }

    @Test
    void clearAccepted_removesAcceptedRecords() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        var r1 = service.addSuggestion("S1", "desc", "catalog", spec, "k1");
        service.addSuggestion("S2", "desc", "catalog", spec, "k2");

        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        when(cronJobService.create(any(), any(), any(), any(), any())).thenReturn(entity);
        service.acceptSuggestion(r1.id());

        int removed = service.clearAccepted();
        assertThat(removed).isEqualTo(1);
        assertThat(service.listAll()).hasSize(1);
        assertThat(service.listAll().get(0).title()).isEqualTo("S2");
    }

    @Test
    void clearAccepted_noAccepted_returnsZero() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        service.addSuggestion("S1", "desc", "catalog", spec, "k1");
        int removed = service.clearAccepted();
        assertThat(removed).isZero();
    }

    @Test
    void listAll_returnsAllStatuses() {
        var spec = new CronSuggestionService.JobSpec("name", "0 9 * * *", "prompt", null, null);
        var r1 = service.addSuggestion("S1", "desc", "catalog", spec, "k1");
        service.addSuggestion("S2", "desc", "catalog", spec, "k2");
        service.dismissSuggestion(r1.id());

        var all = service.listAll();
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(s -> s.status().equals("dismissed"))).hasSize(1);
        assertThat(all.stream().filter(s -> s.status().equals("pending"))).hasSize(1);
    }
}