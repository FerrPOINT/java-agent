package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Role;
import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DatabaseMemoryProviderTest {

    @Test
    void recallFormatsResults() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e = new MemoryEntity();
        e.setCategory("c");
        e.setFact("fact");
        when(repo.searchByUserId("u", "q", 3)).thenReturn(List.of(e));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        assertThat(p.recall("u", "q", 3)).containsExactly("fact");
    }

    @Test
    void recallWithEmptyListReturnsEmptyList() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.searchByUserId("u", "q", 3)).thenReturn(List.of());
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        assertThat(p.recall("u", "q", 3)).isEmpty();
    }

    @Test
    void recallWithNullCategoryRendersNullInFormattedOutput() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e = new MemoryEntity();
        e.setCategory(null);
        e.setFact("fact text");
        when(repo.searchByUserId("u", "q", 5)).thenReturn(List.of(e));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        // M4: recall returns plain fact text, no category prefix
        assertThat(p.recall("u", "q", 5)).containsExactly("fact text");
    }

    @Test
    void storeTrimsContentBeforeSaving() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        // H2: Content should be trimmed before saving
        p.store("user-1", "cat", "  fact with spaces  ");
        verify(repo).save(argThat(e ->
            e.getFact().equals("fact with spaces")
        ));
    }

    @Test
    void storeWithSpecialCharactersSavesEntityCorrectly() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String specialFact = "He said \"hello\" & <script>alert('xss')</script> — café ☕";
        String specialCategory = "cät-egorÿ/with;special";
        p.store("user-1", specialCategory, specialFact);
        verify(repo).save(argThat(e ->
            e.getUserId().equals("user-1") &&
            e.getCategory().equals(specialCategory) &&
            e.getFact().equals(specialFact) &&
            e.getTarget().equals("memory")
        ));
    }

    @Test
    void storeWithNullUserIdStillSavesEntity() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        p.store(null, "cat", "fact");
        verify(repo).save(argThat(e ->
            e.getUserId() == null &&
            e.getCategory().equals("cat") &&
            e.getFact().equals("fact")
        ));
    }

    @Test
    void storeWithTargetSetsTargetField() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        p.store("u", "custom-target", "cat", "fact");
        verify(repo).save(argThat(e ->
            e.getTarget().equals("custom-target") &&
            e.getCategory().equals("cat") &&
            e.getFact().equals("fact")
        ));
    }

    @Test
    void storeWithNullTargetDefaultsToMemory() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        p.store("u", null, "cat", "fact");
        verify(repo).save(argThat(e -> e.getTarget().equals("memory")));
    }

    @Test
    void storeSavesEntityWithAllFieldMappingsAndCreatedAtSet() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        Instant before = Instant.now();
        p.store("u", "cat", "fact");

        ArgumentCaptor<MemoryEntity> captor = ArgumentCaptor.forClass(MemoryEntity.class);
        verify(repo).save(captor.capture());
        MemoryEntity saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo("u");
        assertThat(saved.getCategory()).isEqualTo("cat");
        assertThat(saved.getFact()).isEqualTo("fact");
        assertThat(saved.getTarget()).isEqualTo("memory");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    void replaceReturnsMessageWhenNoMatches() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem")).thenReturn(List.of());
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "old", "new");
        assertThat(result).isEqualTo("No entry found containing: old");
        verify(repo, never()).save(any());
    }

    @Test
    void replaceWithMultipleUniqueMatchesReturnsError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("old text version 1");
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("old text version 2");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e1, e2));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "old text", "new text");
        // Multiple unique matches → error, no replacements performed
        assertThat(result).isNotNull();
        assertThat(result).contains("Multiple entries");
        assertThat(e1.getFact()).isEqualTo("old text version 1");
        assertThat(e2.getFact()).isEqualTo("old text version 2");
        verify(repo, never()).save(any());
    }

    @Test
    void replaceWithSingleMatchUpdatesAndReturnsNull() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("old text");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e1));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "old text", "new text");
        assertThat(result).isNull();
        assertThat(e1.getFact()).isEqualTo("new text");
        verify(repo).save(e1);
    }

    @Test
    void replaceMatchesSubstringEntries() {
        MemoryRepository repo = mock(MemoryRepository.class);
        // Substring match: entry contains "old text" as part of a larger fact
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("the old text is here");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e1));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "old text", "new text");
        assertThat(result).isNull();
        assertThat(e1.getFact()).isEqualTo("new text");
        verify(repo).save(e1);
    }

    @Test
    void removeReturnsMessageWhenNoMatches() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem")).thenReturn(List.of());
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "mem", "old");
        assertThat(result).isEqualTo("No entry found containing: old");
        verify(repo, never()).delete(any());
    }

    @Test
    void removeWithMultipleUniqueMatchesReturnsError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("old text version 1");
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("old text version 2");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e1, e2));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "mem", "old text");
        // Multiple unique matches → error, no deletion performed
        assertThat(result).isNotNull();
        assertThat(result).contains("Multiple entries");
        verify(repo, never()).delete(any());
    }

    @Test
    void removeWithSingleMatchDeletesAndReturnsNull() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("old text");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e1));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "mem", "old text");
        assertThat(result).isNull();
        verify(repo).delete(e1);
    }

    @Test
    void readReturnsEmptyStringWhenNoEntries() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem")).thenReturn(List.of());
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        assertThat(p.read("u", "mem")).isEmpty();
    }

    @Test
    void readReturnsPlainEntriesJoinedByDelimiter() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setCategory("auto");
        e1.setFact("Fact one");
        MemoryEntity e2 = new MemoryEntity();
        e2.setCategory("manual");
        e2.setFact("Fact two");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(e1, e2));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.read("u", "memory");
        // M4: read() returns plain entries joined by delimiter (no § header, no [category] prefix)
        assertThat(result).doesNotContain("§ MEMORY");
        assertThat(result).doesNotContain("[auto]");
        assertThat(result).doesNotContain("[manual]");
        assertThat(result).contains("Fact one");
        assertThat(result).contains("Fact two");
    }

    @Test
    void getSnapshotReturnsMemoryAndUserBlocks() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity memEntity = new MemoryEntity();
        memEntity.setCategory("cat");
        memEntity.setFact("mem fact");
        MemoryEntity userEntity = new MemoryEntity();
        userEntity.setCategory("info");
        userEntity.setFact("user fact");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(memEntity));
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "user"))
            .thenReturn(List.of(userEntity));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        var snapshot = p.getSnapshot("u");
        assertThat(snapshot).containsKeys("memory", "user");
        // M4: snapshot uses plain entries, no category prefix
        assertThat(snapshot.get("memory")).contains("mem fact");
        assertThat(snapshot.get("user")).contains("user fact");
    }

    // S4: syncTurn no longer writes facts — just logs for audit
    @Test
    void syncTurn_doesNotWriteMemoryFacts() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        p.syncTurn("session-1", List.of(Message.user("hello"), Message.assistant("hi", 0)));
        // S4: Should NOT save any memory entities — just log
        verify(repo, never()).save(any());
    }

    @Test
    void syncTurn_emptyMessages_doesNothing() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        p.syncTurn("session-1", List.of());
        verifyNoInteractions(repo);
    }

    @Test
    void syncTurn_nullMessages_doesNothing() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        p.syncTurn("session-1", null);
        verifyNoInteractions(repo);
    }

    // ── Drift detection tests (parity with Hermes _detect_external_drift) ──

    @Test
    void store_withOversizedFact_throwsDriftError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String oversized = "x".repeat(2201); // exceeds MEMORY_CHAR_LIMIT (2200)
        assertThatThrownBy(() -> p.store("u", "memory", "cat", oversized))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to write memory target 'memory'")
            .hasMessageContaining("exceeds the store's char limit");
        verify(repo, never()).save(any());
    }

    @Test
    void store_withOversizedUserFact_throwsDriftError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String oversized = "x".repeat(1376); // exceeds USER_CHAR_LIMIT (1375)
        assertThatThrownBy(() -> p.store("u", "user", "cat", oversized))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to write memory target 'user'");
        verify(repo, never()).save(any());
    }

    @Test
    void store_withExactLimit_succeeds() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String exactLimit = "x".repeat(2200); // exactly MEMORY_CHAR_LIMIT
        p.store("u", "memory", "cat", exactLimit);
        verify(repo).save(any(MemoryEntity.class));
    }

    @Test
    void store_withOptimisticLockFailure_throwsDriftError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.save(any(MemoryEntity.class)))
            .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                MemoryEntity.class, "fake-id", null));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        assertThatThrownBy(() -> p.store("u", "memory", "cat", "fact"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to write memory target 'memory'")
            .hasMessageContaining("optimistic lock conflict");
    }

    @Test
    void replace_withOversizedNewText_returnsDriftError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity existing = new MemoryEntity();
        existing.setUserId("u");
        existing.setTarget("memory");
        existing.setFact("old");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(existing));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String oversized = "x".repeat(2201);
        String result = p.replace("u", "memory", "old", oversized);
        assertThat(result).contains("Refusing to write memory target 'memory'")
            .contains("exceeds the store's char limit");
        verify(repo, never()).save(any());
    }

    @Test
    void replace_withOptimisticLockFailure_returnsDriftError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity existing = new MemoryEntity();
        existing.setUserId("u");
        existing.setTarget("memory");
        existing.setFact("old");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(existing));
        when(repo.save(any(MemoryEntity.class)))
            .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                MemoryEntity.class, "fake-id", null));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "memory", "old", "new");
        assertThat(result).contains("Refusing to write memory target 'memory'")
            .contains("optimistic lock conflict");
    }

    @Test
    void remove_withOptimisticLockFailure_returnsDriftError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity existing = new MemoryEntity();
        existing.setUserId("u");
        existing.setTarget("memory");
        existing.setFact("old");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(existing));
        doThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                MemoryEntity.class, "fake-id", null))
            .when(repo).delete(existing);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "memory", "old");
        assertThat(result).contains("Refusing to write memory target 'memory'")
            .contains("optimistic lock conflict");
    }

    @Test
    void driftErrorMessageContainsRemediationGuidance() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String oversized = "x".repeat(2201);
        assertThatThrownBy(() -> p.store("u", "memory", "cat", oversized))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Resolve the drift first")
            .hasMessageContaining("silent data loss")
            .hasMessageContaining("#26045");
    }
}