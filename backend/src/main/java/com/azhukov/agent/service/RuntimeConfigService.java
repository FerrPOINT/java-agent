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

    private final AtomicReference<RuntimeModelSelection> modelSelection = new AtomicReference<>();

    /** Set a runtime model name override. Null or blank clears the override. */
    public void setModelOverride(String model) {
        if (model == null || model.isBlank()) {
            modelSelection.set(null);
            log.info("Runtime model override cleared");
        } else {
            modelSelection.set(new RuntimeModelSelection(null, model.trim(), null, null));
            log.info("Runtime model override set to: {}", model.trim());
        }
    }

    /** Set a Hermes-style runtime model assignment without mutating static configuration. */
    public RuntimeModelSelection setModelSelection(String provider, String model, String baseUrl, String apiKey) {
        if (model == null || model.isBlank()) {
            modelSelection.set(null);
            log.info("Runtime model selection cleared");
            return null;
        }
        RuntimeModelSelection selection = new RuntimeModelSelection(
            clean(provider),
            model.trim(),
            clean(baseUrl),
            clean(apiKey));
        modelSelection.set(selection);
        log.info("Runtime model selection set to provider={}, model={}",
            selection.provider(), selection.model());
        return selection;
    }

    /** Get the runtime model override, or null if none set. */
    public String getModelOverride() {
        RuntimeModelSelection selection = modelSelection.get();
        return selection != null ? selection.model() : null;
    }

    /** Get the full runtime model selection, or null if none set. */
    public RuntimeModelSelection getModelSelection() {
        return modelSelection.get();
    }

    /** Clear the runtime model override. */
    public void clearModelOverride() {
        modelSelection.set(null);
    }

    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    public record RuntimeModelSelection(
        String provider,
        String model,
        String baseUrl,
        String apiKey
    ) {
        @Override
        public String toString() {
            return "RuntimeModelSelection[" +
                "provider=" + provider +
                ", model=" + model +
                ", baseUrl=" + baseUrl +
                ", apiKey=" + (apiKey != null && !apiKey.isBlank() ? "<redacted>" : apiKey) +
                ']';
        }
    }
}
