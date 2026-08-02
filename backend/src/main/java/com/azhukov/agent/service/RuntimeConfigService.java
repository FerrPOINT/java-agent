package com.azhukov.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages runtime model overrides without mutating the shared {@code @ConfigurationProperties} bean.
 * The override is stored in a thread-safe {@link AtomicReference} and takes precedence over
 * the static configuration when set.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RuntimeConfigService {

    private final AtomicReference<String> modelOverride = new AtomicReference<>();

    /** Set a runtime model name override. Null or blank clears the override. */
    public void setModelOverride(String model) {
        if (model == null || model.isBlank()) {
            modelOverride.set(null);
            log.info("Runtime model override cleared");
        } else {
            modelOverride.set(model);
            log.info("Runtime model override set to: {}", model);
        }
    }

    /** Get the runtime model override, or null if none set. */
    public String getModelOverride() {
        return modelOverride.get();
    }

    /** Clear the runtime model override. */
    public void clearModelOverride() {
        modelOverride.set(null);
    }
}
