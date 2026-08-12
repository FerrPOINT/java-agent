package com.azhukov.agent.core.memory;

import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M24: Test that DatabaseMemoryProvider.replace() and remove() use exact
 * match instead of contains() to prevent unintended broad edits/deletions.
 */
class DatabaseMemoryProviderExactMatchTest {

    @Test
    void replaceUsesExactMatchNotContains() {
        MemoryRepository repo = mock(MemoryRepository.class);
        // Simulate: no exact match for "old text" even though entries contain it
        when(repo.findByUserIdAndTargetAndFact("u", "mem", "old text"))
            .thenReturn(List.of());

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "old text", "new text");

        // Should return "not found" message because no EXACT match
        assertThat(result).contains("No entry found with exact text");
        // Verify exact match query was used, not contains
        verify(repo).findByUserIdAndTargetAndFact("u", "mem", "old text");
        verify(repo, never()).findByUserIdAndTargetAndFactContaining(any(), any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void replaceSetsNewFactEntirelyNotSubstringReplace() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e = new MemoryEntity();
        e.setFact("exact old text");
        when(repo.findByUserIdAndTargetAndFact("u", "mem", "exact old text"))
            .thenReturn(List.of(e));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "exact old text", "brand new fact");

        assertThat(result).isNull();
        // The fact should be replaced entirely, not substring-replaced
        assertThat(e.getFact()).isEqualTo("brand new fact");
        verify(repo).save(e);
    }

    @Test
    void removeUsesExactMatchNotContains() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.findByUserIdAndTargetAndFact("u", "mem", "target text"))
            .thenReturn(List.of());

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "mem", "target text");

        assertThat(result).contains("No entry found with exact text");
        verify(repo).findByUserIdAndTargetAndFact("u", "mem", "target text");
        verify(repo, never()).findByUserIdAndTargetAndFactContaining(any(), any(), any());
        verify(repo, never()).deleteAll(any());
    }

    @Test
    void replaceWithMultipleExactMatchesReplacesAll() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("same fact");
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("same fact");
        when(repo.findByUserIdAndTargetAndFact("u", "mem", "same fact"))
            .thenReturn(List.of(e1, e2));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "same fact", "updated fact");

        assertThat(result).isNull();
        assertThat(e1.getFact()).isEqualTo("updated fact");
        assertThat(e2.getFact()).isEqualTo("updated fact");
        verify(repo, times(2)).save(any());
    }
}