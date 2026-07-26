package com.azhukov.agent.core.memory;

import com.azhukov.agent.persistence.entity.MemoryEntity;
import com.azhukov.agent.persistence.repository.MemoryRepository;
import org.junit.jupiter.api.Test;

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
    void storeSavesEntity() {
        MemoryRepository repo = mock(MemoryRepository.class);
        DatabaseMemoryProvider p = new DatabaseMemoryProvider(repo);
        p.store("u", "c", "fact");
        verify(repo).save(argThat(e -> e.getUserId().equals("u") && e.getCategory().equals("c") && e.getFact().equals("fact")));
    }
}
