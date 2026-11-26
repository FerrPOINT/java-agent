package com.azhukov.agent.service;

import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.service.RuntimeConfigService.RuntimeModelSelection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Resolves the primary model and credentials belonging to a named agent profile. */
@Service
@RequiredArgsConstructor
public class ProfileModelRouting {
    private final ProfileService profiles;
    private final ProfileEnvStore envStore;

    public ModelRequestOptions bind(String profile, ModelRequestOptions requested) {
        var options = requested == null ? ModelRequestOptions.empty() : requested;
        if (options.boundProfile() != null) {
            if (!profile.equals(options.boundProfile())) {
                throw new IllegalArgumentException("Model route belongs to another profile");
            }
            return options;
        }
        if ("default".equals(profile)) {
            return options;
        }
        var route = configured(profile);
        return options.bindProfile(profile, route.model(), route.provider(), route.baseUrl(), route.apiKey());
    }

    private RuntimeModelSelection configured(String profile) {
        try {
            return resolve(profile, profiles.readNamedModel(profile));
        } catch (IOException | RuntimeException e) {
            // YAML parser and transport exceptions may contain credentials.
            throw new ProfileModelUnavailable();
        }
    }

    void validateCandidate(String profile, Map<String, Object> config) {
        try {
            resolve(profile, ProfileService.modelSelection(config));
        } catch (IOException | RuntimeException e) {
            throw new ProfileModelUnavailable();
        }
    }

    private RuntimeModelSelection resolve(String profile, RuntimeModelSelection route) throws IOException {
        Map<String, String> env = route.baseUrl() == null || route.apiKey() == null
            ? envStore.readAll(profile) : Map.of();
        String endpoint = first(route.baseUrl(), env.get("AGENT_MODEL_BASE_URL"));
        String key = first(route.apiKey(), env.get("AGENT_MODEL_API_KEY"));
        if (route.provider() == null || route.model() == null || endpoint == null || key == null) {
            throw new IllegalArgumentException("Incomplete profile model configuration");
        }
        validateEndpoint(endpoint);
        return new RuntimeModelSelection(route.provider(), route.model(), endpoint, key);
    }

    private static void validateEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
            || uri.getRawFragment() != null
            || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Invalid profile model endpoint");
        }
    }

    private static String first(String value, String alternative) {
        if (value != null) {
            return value;
        }
        return alternative == null || alternative.isBlank() ? null : alternative.trim();
    }

    public static final class ProfileModelUnavailable extends IllegalArgumentException {
        public ProfileModelUnavailable() {
            super("Profile model configuration is unavailable or incomplete");
        }
    }
}
