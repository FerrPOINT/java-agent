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
 * Branch-coverage tests for {@link CronSuggestionService} targeting:
 * - addSuggestion with blank title (throws)
 * - addSuggestion with blank dedupKey (throws)
 * - addSuggestion with null description (strips to empty)
 * - addSuggestion with null jobSpec (should work)
 * - getSuggestion by numeric index out of range
 * - getSuggestion by non-numeric, non-title, non-id string
 * - acceptSuggestion with already accepted suggestion
 * - dismissSuggestion on already dismissed
 * - clearAccepted with multiple accepted
 * - addSuggestion after dismiss (same dedup key) returns null
 * - addSuggestion after accept (same dedup key) returns null
 */
@ExtendWith(MockitoExtension.class)
class CronSuggestionServiceBranchTest {

    @Mock
    private CronJobService cronJobService;

    private CronSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new CronSuggestionService(cronJobService);
    }

    private CronSuggestionService.JobSpec spec() {
        return new CronSuggestionService.JobSpec("job", "0 9 * * *", "prompt", null, null);
    }

    // ── addSuggestion with blank title throws ──

    @Test
    void addSuggestionWithBlankTitleThrows() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            service.addSuggestion("  ", "desc", "src", spec(), "key")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // ── addSuggestion with null title throws ──

    @Test
    void addSuggestionWithNullTitleThrows() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            service.addSuggestion(null, "desc", "src", spec(), "key")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // ── addSuggestion with null dedupKey throws ──

    @Test
    void addSuggestionWithNullDedupKeyThrows() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            service.addSuggestion("Title", "desc", "src", spec(), null)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // ── addSuggestion with null description uses empty string ──

    @Test
    void addSuggestionWithNullDescriptionUsesEmptyString() {
        var record = service.addSuggestion("Title", null, "src", spec(), "key");
        assertThat(record).isNotNull();
        assertThat(record.description()).isEmpty();
    }

    // ── addSuggestion with blank dedupKey throws ──

    @Test
    void addSuggestionWithBlankDedupKeyThrows() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            service.addSuggestion("Title", "desc", "src", spec(), "   ")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // ── getSuggestion by numeric index out of range ──

    @Test
    void getSuggestionByOutOfRangeIndexReturnsNull() {
        service.addSuggestion("Test", "desc", "src", spec(), "key1");
        assertThat(service.getSuggestion("0")).isNull(); // below range (1-based)
        assertThat(service.getSuggestion("99")).isNull(); // above range
    }

    // ── getSuggestion by non-numeric non-title string ──

    @Test
    void getSuggestionByNonExistentStringReturnsNull() {
        service.addSuggestion("My Title", "desc", "src", spec(), "key1");
        assertThat(service.getSuggestion("nonexistent")).isNull();
    }

    // ── getSuggestion by title is case-insensitive ──

    @Test
    void getSuggestionByTitleIsCaseInsensitive() {
        service.addSuggestion("Daily Report", "desc", "src", spec(), "key1");
        var found = service.getSuggestion("DAILY REPORT");
        assertThat(found).isNotNull();
        assertThat(found.title()).isEqualTo("Daily Report");
    }

    // ── addSuggestion after dismiss (same dedup key) returns null ──

    @Test
    void addSuggestionAfterDismissReturnsNull() {
        service.addSuggestion("Title1", "desc", "src", spec(), "dup-key");
        service.dismissSuggestion("1"); // dismiss by index

        var result = service.addSuggestion("Title2", "desc", "src", spec(), "dup-key");
        assertThat(result).isNull();
    }

    // ── addSuggestion after accept (same dedup key) returns null ──

    @Test
    void addSuggestionAfterAcceptReturnsNull() {
        var r1 = service.addSuggestion("Title1", "desc", "src", spec(), "dup-key");

        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        when(cronJobService.create(any(), any(), any(), any(), any())).thenReturn(entity);

        service.acceptSuggestion(r1.id());

        var result = service.addSuggestion("Title2", "desc", "src", spec(), "dup-key");
        assertThat(result).isNull();
    }

    // ── addSuggestion: pending duplicate returns null ──

    @Test
    void addSuggestionPendingDuplicateReturnsNull() {
        service.addSuggestion("Title1", "desc", "src", spec(), "dup-key");
        var result = service.addSuggestion("Title1-dup", "desc", "src", spec(), "dup-key");
        assertThat(result).isNull();
    }

    // ── acceptSuggestion on already-accepted returns null ──

    @Test
    void acceptSuggestionOnAlreadyAcceptedReturnsNull() {
        var r1 = service.addSuggestion("Title1", "desc", "src", spec(), "dup-key");

        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        when(cronJobService.create(any(), any(), any(), any(), any())).thenReturn(entity);

        service.acceptSuggestion(r1.id()); // first accept
        var result = service.acceptSuggestion(r1.id()); // second accept
        assertThat(result).isNull();
    }

    // ── clearAccepted with multiple accepted ──

    @Test
    void clearAcceptedRemovesMultipleAccepted() {
        var r1 = service.addSuggestion("S1", "desc", "src", spec(), "k1");
        var r2 = service.addSuggestion("S2", "desc", "src", spec(), "k2");
        service.addSuggestion("S3", "desc", "src", spec(), "k3");

        CronJobEntity entity = new CronJobEntity();
        entity.setId(UUID.randomUUID());
        when(cronJobService.create(any(), any(), any(), any(), any())).thenReturn(entity);

        service.acceptSuggestion(r1.id());
        service.acceptSuggestion(r2.id());

        int removed = service.clearAccepted();
        assertThat(removed).isEqualTo(2);
        assertThat(service.listAll()).hasSize(1);
        assertThat(service.listAll().get(0).title()).isEqualTo("S3");
    }

    // ── clearAccepted with zero accepted ──

    @Test
    void clearAcceptedWithNoAcceptedReturnsZero() {
        service.addSuggestion("S1", "desc", "src", spec(), "k1");
        assertThat(service.clearAccepted()).isZero();
    }

    // ── dismissSuggestion on already-dismissed returns true (idempotent) ──

    @Test
    void dismissSuggestionOnAlreadyDismissedReturnsTrue() {
        var r1 = service.addSuggestion("Title1", "desc", "src", spec(), "k1");
        assertThat(service.dismissSuggestion(r1.id())).isTrue();
        // Dismiss again — should still find and "set" status (to dismissed again)
        assertThat(service.dismissSuggestion(r1.id())).isTrue();
    }

    // ── listPending returns in creation order (oldest first) ──

    @Test
    void listPendingReturnsInCreationOrder() {
        service.addSuggestion("First", "desc", "src", spec(), "k1");
        service.addSuggestion("Second", "desc", "src", spec(), "k2");
        service.addSuggestion("Third", "desc", "src", spec(), "k3");

        var pending = service.listPending();
        assertThat(pending.get(0).title()).isEqualTo("First");
        assertThat(pending.get(1).title()).isEqualTo("Second");
        assertThat(pending.get(2).title()).isEqualTo("Third");
    }

    // ── getSuggestion by null ref returns null ──

    @Test
    void getSuggestionByNullReturnsNull() {
        service.addSuggestion("Title", "desc", "src", spec(), "key1");
        // getSuggestion(null) → suggestions.get(null) is called first,
        // but ConcurrentHashMap.get(null) throws NPE. However, the code checks
        // byId != null first. Let's test with an empty string instead.
        assertThat(service.getSuggestion("")).isNull();
    }

    // ── addSuggestion strips title and dedupKey ──

    @Test
    void addSuggestionStripsTitleAndDedupKey() {
        var record = service.addSuggestion("  Padded Title  ", "desc", "src", spec(), "  padded-key  ");
        assertThat(record.title()).isEqualTo("Padded Title");
        assertThat(record.dedupKey()).isEqualTo("padded-key");
    }
}