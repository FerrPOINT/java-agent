package com.azhukov.agent.bot.settings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for persisting and retrieving bot runtime configuration as key-value pairs.
 * <p>
 * Used by commands (e.g. /set_home, /personality, /topic) to persist settings across restarts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BotSettingsService {

    private final BotSettingsRepository repository;

    /**
     * Retrieve a setting by key, returning the default if not found.
     *
     * @param key          the setting key
     * @param defaultValue  value to return when the key is absent or value is null
     * @return the stored value, or defaultValue if not present
     */
    @Transactional(readOnly = true)
    public String getSetting(String key, String defaultValue) {
        return repository.findByKey(key)
            .map(BotSettingsEntity::getValue)
            .map(v -> v != null ? v : defaultValue)
            .orElse(defaultValue);
    }

    /**
     * Insert or update a setting by key.
     *
     * @param key   the setting key
     * @param value the value to persist (may be null to clear)
     */
    @Transactional
    public void setSetting(String key, String value) {
        BotSettingsEntity entity = repository.findByKey(key).orElse(null);
        if (entity == null) {
            entity = new BotSettingsEntity();
            entity.setKey(key);
            entity.setValue(value);
            entity.setUpdatedAt(Instant.now());
        } else {
            entity.setValue(value);
            entity.setUpdatedAt(Instant.now());
        }
        repository.save(entity);
    }

    /**
     * Retrieve all settings whose key starts with the given prefix.
     *
     * @param prefix the key prefix to match (e.g. "topic_session:123:")
     * @return a map of key → value for all matching settings
     */
    @Transactional(readOnly = true)
    public Map<String, String> getSettingsByPrefix(String prefix) {
        List<BotSettingsEntity> entities = repository.findByKeyStartingWith(prefix);
        Map<String, String> result = new LinkedHashMap<>();
        for (BotSettingsEntity e : entities) {
            if (e.getValue() != null) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }
}