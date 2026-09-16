package com.azhukov.agent.service.media;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WP-10 (docs/35): provider capability matrix.
 *
 * <p>Aggregates every {@link MediaProvider} bean into an honest matrix used
 * by toolset/dashboard/model prompt exposure: a capability cell is exposed
 * ONLY when a configured provider actually implements the operation.
 * Unavailable providers are reported with their reason — never advertised
 * as working.
 */
@Component
@RequiredArgsConstructor
public class MediaProviderRegistry {

    private final ObjectProvider<List<MediaProvider>> providersProvider;
    private final AgentProperties properties;

    public record CapabilityRow(String provider, String operation, boolean supported,
                                boolean configured, String unavailableReason,
                                Set<String> inputMime, Set<String> outputMime,
                                boolean streaming) {}

    /** Providers implementing the operation AND currently configured. */
    public List<MediaProvider> capable(MediaProvider.Operation operation) {
        List<MediaProvider> result = new ArrayList<>();
        for (MediaProvider provider : providers()) {
            if (provider.operations().contains(operation) && provider.isConfigured()) {
                result.add(provider);
            }
        }
        return result;
    }

    public boolean isCapabilityAvailable(MediaProvider.Operation operation) {
        return !capable(operation).isEmpty();
    }

    /** Resolve one provider by explicit selection or fail deterministically. */
    public Optional<MediaProvider> resolve(MediaProvider.Operation operation, String providerId) {
        List<MediaProvider> capable = capable(operation);
        if (capable.isEmpty()) {
            return Optional.empty();
        }
        if (providerId == null || providerId.isBlank()) {
            return Optional.of(capable.getFirst());
        }
        return capable.stream()
            .filter(p -> p.id().equals(providerId))
            .findFirst();
    }

    /** Full published matrix (toolset/dashboard/prompt exposure). */
    public List<CapabilityRow> matrix() {
        List<CapabilityRow> rows = new ArrayList<>();
        for (MediaProvider provider : providers()) {
            boolean configured = provider.isConfigured();
            for (MediaProvider.Operation operation : provider.operations()) {
                rows.add(new CapabilityRow(
                    provider.id(),
                    operation.name().toLowerCase(),
                    true,
                    configured,
                    configured ? null : provider.unavailableReason(),
                    provider.inputMimeTypes().getOrDefault(operation, Set.of()),
                    provider.outputMimeTypes().getOrDefault(operation, Set.of()),
                    provider.supportsStreaming(operation)));
            }
        }
        return rows;
    }

    /** Provider ids registered at all (any operation). */
    public List<String> providerIds() {
        return providers().stream().map(MediaProvider::id).toList();
    }

    private List<MediaProvider> providers() {
        List<MediaProvider> providers = providersProvider == null
            ? null : providersProvider.getIfAvailable();
        return providers == null ? List.of() : providers;
    }

    /** Default provider preference from config, e.g. agent.tts.provider. */
    public String configuredTtsProvider() {
        return properties == null || properties.getTts() == null
            ? null : properties.getTts().getProvider();
    }

    public Map<String, Object> describe() {
        return Map.of(
            "providers", providerIds(),
            "capabilities", matrix());
    }
}
