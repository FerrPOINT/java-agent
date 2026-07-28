package com.azhukov.agent.bot.sticker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StickerCacheTest {

    private StickerCacheRepository repository;
    private StickerCache cache;

    @BeforeEach
    void setUp() {
        repository = mock(StickerCacheRepository.class);
        cache = new StickerCache(repository);
    }

    @Test
    void get_returnsCachedDescription() {
        StickerCacheEntity entity = new StickerCacheEntity("file123", "A funny cat sticker");
        when(repository.findById("file123")).thenReturn(Optional.of(entity));

        Optional<String> result = cache.get("file123");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("A funny cat sticker");
        verify(repository).findById("file123");
    }

    @Test
    void get_missReturnsEmpty() {
        when(repository.findById("nonexistent")).thenReturn(Optional.empty());

        Optional<String> result = cache.get("nonexistent");

        assertThat(result).isEmpty();
        verify(repository).findById("nonexistent");
    }

    @Test
    void put_cachesDescription() {
        cache.put("file456", "A dog sticker");

        verify(repository).save(argThat(entity ->
            "file456".equals(entity.getFileUniqueId()) &&
            "A dog sticker".equals(entity.getDescription())
        ));
    }
}