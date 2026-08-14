package com.azhukov.agent.core.memory;

import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests that DatabaseMemoryProvider.replace() and remove() use substring
 * (contains) matching, parity with Hermes — not exact match only.
 */
class DatabaseMemoryProviderContainsMatchTest {

    @Test
    void replaceUsesContainsMatchNotExactOnly() {
        MemoryRepository repo = mock(MemoryRepository.class);
        // Entry contains "old text" as a substring of a larger fact
        MemoryEntity e = new MemoryEntity();
        e.setFact("the old text is here");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "old text", "new text");

        // Should match because fact contains "old text" (substring match)
        assertThat(result).isNull();
        assertThat(e.getFact()).isEqualTo("new text");
        verify(repo).save(e);
    }

    @Test
    void replaceSetsNewFactEntirelyNotSubstringReplace() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e = new MemoryEntity();
        e.setFact("exact old text");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "exact old text", "brand new fact");

        assertThat(result).isNull();
        // The fact should be replaced entirely, not substring-replaced
        assertThat(e.getFact()).isEqualTo("brand new fact");
        verify(repo).save(e);
    }

    @Test
    void removeUsesContainsMatchNotExactOnly() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e = new MemoryEntity();
        e.setFact("the target text is here");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "mem", "target text");

        assertThat(result).isNull();
        verify(repo).delete(e);
    }

    @Test
    void replaceWithMultipleUniqueContainsMatchesReturnsError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("same fact variant 1");
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("same fact variant 2");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "mem"))
            .thenReturn(List.of(e1, e2));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "mem", "same fact", "updated fact");

        // Multiple unique matches → error, no replacements performed
        assertThat(result).isNotNull();
        assertThat(result).contains("Multiple entries match");
        assertThat(result).contains("Be more specific");
        assertThat(result).contains("1. same fact variant 1");
        assertThat(result).contains("2. same fact variant 2");
        assertThat(e1.getFact()).isEqualTo("same fact variant 1");
        assertThat(e2.getFact()).isEqualTo("same fact variant 2");
        verify(repo, never()).save(any());
    }

    // ── Fix 2: Replace overflow check ──

    @Test
    void replace_overflowCheck_rejectsWhenTotalExceedsLimit() {
        MemoryRepository repo = mock(MemoryRepository.class);
        // Two entries near the limit
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("a".repeat(1500));
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("b".repeat(500));
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(e1, e2));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        // Replace e1 with something that pushes total over 2200
        String result = p.replace("u", "memory", "a".repeat(1500), "x".repeat(2000));
        assertThat(result).contains("Replacement would put memory at");
        assertThat(result).contains("2200");
        // Original unchanged
        assertThat(e1.getFact()).isEqualTo("a".repeat(1500));
        verify(repo, never()).save(any());
    }

    @Test
    void replace_withinLimit_succeeds() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("short entry");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(e1));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "memory", "short", "a bit longer entry but still fine");
        assertThat(result).isNull();
        assertThat(e1.getFact()).isEqualTo("a bit longer entry but still fine");
        verify(repo).save(e1);
    }

    // ── Fix 3: Multiple-match preview format ──

    @Test
    void replace_multipleMatches_includesPreviewsInError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("The quick brown fox jumps");
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("The slow brown fox walks");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(e1, e2));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "memory", "brown fox", "replacement");
        assertThat(result).contains("Multiple entries match");
        assertThat(result).contains("1. The quick brown fox jumps");
        assertThat(result).contains("2. The slow brown fox walks");
    }

    @Test
    void remove_multipleMatches_includesPreviewsInError() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("The quick brown fox jumps");
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("The slow brown fox walks");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(e1, e2));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.remove("u", "memory", "brown fox");
        assertThat(result).contains("Multiple entries match");
        assertThat(result).contains("1. The quick brown fox jumps");
        assertThat(result).contains("2. The slow brown fox walks");
        verify(repo, never()).delete(any());
    }

    @Test
    void replace_previewTruncatedAt80Chars() {
        MemoryRepository repo = mock(MemoryRepository.class);
        String longFact1 = "This is a very long entry that contains the search term and goes well beyond eighty characters in total length";
        String longFact2 = "Another long entry that contains the search term and also exceeds the eighty character preview limit";
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact(longFact1);
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact(longFact2);
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(e1, e2));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        String result = p.replace("u", "memory", "search term", "replacement");
        assertThat(result).contains(longFact1.substring(0, 80) + "...");
        assertThat(result).contains(longFact2.substring(0, 80) + "...");
    }

    // ── Fix 1: getCharCount / getEntryCount ──

    @Test
    void getCharCount_returnsPureEntryContentWithoutHeaders() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setCategory("auto");
        e1.setFact("Fact one");
        MemoryEntity e2 = new MemoryEntity();
        e2.setCategory("auto");
        e2.setFact("Fact two");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(e1, e2));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        int charCount = p.getCharCount("u", "memory");
        // Should be "Fact one\n§\nFact two".length() = 9 + 3 + 8 = 20
        assertThat(charCount).isEqualTo("Fact one\n§\nFact two".length());
        // M4: read() now returns the same plain format, so char count equals read().length()
        assertThat(charCount).isEqualTo(p.read("u", "memory").length());
    }

    @Test
    void getEntryCount_returnsRawEntryCount() {
        MemoryRepository repo = mock(MemoryRepository.class);
        MemoryEntity e1 = new MemoryEntity();
        e1.setFact("Fact one");
        MemoryEntity e2 = new MemoryEntity();
        e2.setFact("Fact two");
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of(e1, e2));

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        assertThat(p.getEntryCount("u", "memory")).isEqualTo(2);
    }

    @Test
    void getCharCount_emptyStore_returnsZero() {
        MemoryRepository repo = mock(MemoryRepository.class);
        when(repo.findByUserIdAndTargetOrderByCreatedAtDesc("u", "memory"))
            .thenReturn(List.of());

        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        assertThat(p.getCharCount("u", "memory")).isEqualTo(0);
        assertThat(p.getEntryCount("u", "memory")).isEqualTo(0);
    }

    @Test
    void getRawEntries_returnsFactTextsOnly() {
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
        List<String> raw = p.getRawEntries("u", "memory");
        assertThat(raw).containsExactly("Fact one", "Fact two");
        // Should NOT contain category prefixes
        assertThat(raw.get(0)).doesNotContain("[auto]");
    }
}