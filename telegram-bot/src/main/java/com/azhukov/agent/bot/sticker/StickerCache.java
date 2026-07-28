package com.azhukov.agent.bot.sticker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * B2.1 / C4: Caches sticker descriptions by file_unique_id.
 * <p>
 * When a sticker is received, the bot checks this cache first.
 * On a miss, it calls the backend vision API for analysis and caches the result.
 */
@Service
public class StickerCache {

    private static final Logger log = LoggerFactory.getLogger(StickerCache.class);

    private final StickerCacheRepository repository;

    public StickerCache(StickerCacheRepository repository) {
        this.repository = repository;
    }

    /**
     * Get a cached sticker description by file_unique_id.
     *
     * @param fileUniqueId the Telegram file_unique_id
     * @return Optional with description if cached, empty otherwise
     */
    public Optional<String> get(String fileUniqueId) {
        if (fileUniqueId == null || fileUniqueId.isBlank()) {
            return Optional.empty();
        }
        Optional<StickerCacheEntity> entity = repository.findById(fileUniqueId);
        if (entity.isPresent()) {
            log.debug("Sticker cache hit for fileUniqueId={}", fileUniqueId);
            return Optional.ofNullable(entity.get().getDescription());
        }
        log.debug("Sticker cache miss for fileUniqueId={}", fileUniqueId);
        return Optional.empty();
    }

    /**
     * Cache a sticker description.
     *
     * @param fileUniqueId the Telegram file_unique_id
     * @param description  the vision-analyzed description
     */
    public void put(String fileUniqueId, String description) {
        if (fileUniqueId == null || fileUniqueId.isBlank() || description == null) {
            return;
        }
        StickerCacheEntity entity = new StickerCacheEntity(fileUniqueId, description);
        repository.save(entity);
        log.debug("Cached sticker description for fileUniqueId={}", fileUniqueId);
    }

    /**
     * Check if a sticker description is cached.
     *
     * @param fileUniqueId the Telegram file_unique_id
     * @return true if cached
     */
    public boolean contains(String fileUniqueId) {
        if (fileUniqueId == null || fileUniqueId.isBlank()) {
            return false;
        }
        return repository.existsById(fileUniqueId);
    }
}