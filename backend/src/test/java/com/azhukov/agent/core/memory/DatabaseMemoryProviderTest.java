package com.azhukov.agent.core.memory;

import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(p.recall("u", "q", 3)).containsExactly("[c] fact");
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
        // The format is "[" + category + "] " + fact — null category renders as "null"
        assertThat(p.recall("u", "q", 5)).containsExactly("[null] fact text");
    }

    @Test
    void storeWithSpecialCharactersSavesEntityCorrectly() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String specialFact = "He said \"hello\" & <script>alert('xss')</script> — café ☕";
        String specialCategory = "cät-égorÿ/with;special";
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
        when(repo.findByUserIdAndTargetAndFactContaining("u", "mem", "old")).thenReturn(List.of());
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "old", "new");
        assertThat(result).isEqualTo("No entry found containing: old");
        verify(repo, never()).save(any());
    }

    @Test
    void replaceUpdatesMatchingEntitiesAndReturnsNull() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("this is old text here");
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("old text and more");
        when(repo.findByUserIdAndTargetAndFactContaining("u", "mem", "old text"))
            .thenReturn(List.of(e1, e2));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "old text", "new text");
        assertThat(result).isNull();
        assertThat(e1.getFact()).isEqualTo("this is new text here");
        assertThat(e2.getFact()).isEqualTo("new text and more");
        verify(repo, times(2)).save(any());
    }

    @Test
    void removeReturnsMessageWhenNoMatches() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.findByUserIdAndTargetAndFactContaining("u", "mem", "old")).thenReturn(List.of());
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "mem", "old");
        assertThat(result).isEqualTo("No entry found containing: old");
        verify(repo, never()).deleteAll(any());
    }

    @Test
    void removeDeletesMatchingEntitiesAndReturnsNull() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("old text here");
        when(repo.findByUserIdAndTargetAndFactContaining("u", "mem", "old text"))
            .thenReturn(List.of(e1));
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "mem", "old text");
        assertThat(result).isNull();
        verify(repo).deleteAll(List.of(e1));
    }

    @Test
    void readReturnsEmptyStringWhenNoEntries() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem")).thenReturn(List.of());
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        assertThat(p.read("u", "mem")).isEmpty();
    }

    @Test
    void readFormatsEntriesWithTargetHeader() {
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
        assertThat(result).startsWith("§ MEMORY");
        assertThat(result).contains("[auto] Fact one");
        assertThat(result).contains("[manual] Fact two");
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
        assertThat(snapshot.get("memory")).contains("[cat] mem fact");
        assertThat(snapshot.get("user")).contains("[info] user fact");
    }
}