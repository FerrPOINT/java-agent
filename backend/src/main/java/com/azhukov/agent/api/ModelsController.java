package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible GET /v1/models endpoint.
 *
 * Lists the primary configured model plus any fallback-chain models so that
 * OpenAI-compatible frontends (Open WebUI, LobeChat, etc.) can discover what
 * the agent exposes. Mirrors Hermes' GET /v1/models.
 */
@RestController
@RequestMapping({"/v1/models", "/p/{profile}/v1/models"})
@Slf4j
@Tag(name = "OpenAI-compatible", description = "OpenAI-compatible model listing")
public class ModelsController {

    private static final String DEFAULT_ADVERTISED_MODEL = "hermes-agent";

    private final AgentProperties properties;
    private final ProfileService profileService;

    @Autowired
    public ModelsController(AgentProperties properties, ProfileService profileService) {
        this.properties = properties;
        this.profileService = profileService;
    }

    ModelsController(AgentProperties properties) {
        this(properties, null);
    }

    @GetMapping
    @Operation(summary = "List available models in OpenAI-compatible format")
    public Map<String, Object> listModels(
        @PathVariable(name = "profile", required = false) String pathProfile
    ) {
        String profile = resolveProfile(pathProfile);
        String advertisedModel = advertisedModel(profile);
        long created = Instant.now().getEpochSecond();
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(modelEntry(advertisedModel, created, "hermes", advertisedModel, null));

        AgentProperties.ApiProperties api = properties.getApi();
        if (isDefaultProfile(profile) && api != null && api.getModelRoutes() != null) {
            for (Map.Entry<String, AgentProperties.ApiProperties.ModelRouteProperties> entry : api.getModelRoutes().entrySet()) {
                String alias = entry.getKey() != null ? entry.getKey().trim() : "";
                String routedModel = OpenAiModelRouting.routedModel(api, alias);
                if (alias.isBlank() || routedModel == null || alias.equals(advertisedModel)) {
                    continue;
                }
                data.add(modelEntry(alias, created, "hermes", routedModel, advertisedModel));
            }
        }

        return Map.of(
            "object", "list",
            "data", data
        );
    }

    public Map<String, Object> listModels() {
        return listModels(null);
    }

    private String advertisedModel(String profile) {
        AgentProperties.ApiProperties api = properties.getApi();
        if (!isDefaultProfile(profile)) {
            String profileConfigured = profileApiModelName(profile);
            if (OpenAiModelRouting.hasText(profileConfigured)) {
                return profileConfigured;
            }
            if (api != null
                && OpenAiModelRouting.hasText(api.getModelName())
                && !DEFAULT_ADVERTISED_MODEL.equals(api.getModelName().trim())) {
                return api.getModelName().trim();
            }
            return profile;
        }
        if (api != null && OpenAiModelRouting.hasText(api.getModelName())) {
            return api.getModelName().trim();
        }
        return OpenAiModelRouting.advertisedModel(properties);
    }

    private String profileApiModelName(String profile) {
        if (profileService == null || isDefaultProfile(profile)) {
            return "";
        }
        try {
            Map<String, Object> config = profileService.readConfig(profile);
            String direct = stringValue(config.get("api_server_model_name"));
            if (OpenAiModelRouting.hasText(direct)) {
                return direct;
            }
            Map<String, Object> api = mapValue(config.get("api"));
            String apiModel = firstText(api.get("model_name"), api.get("model-name"));
            if (OpenAiModelRouting.hasText(apiModel)) {
                return apiModel;
            }
            Map<String, Object> platforms = mapValue(config.get("platforms"));
            Map<String, Object> apiServer = mapValue(platforms.get("api_server"));
            Map<String, Object> extra = mapValue(apiServer.get("extra"));
            return firstText(extra.get("model_name"), apiServer.get("model_name"));
        } catch (FileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read profile config", e);
        }
    }

    private String resolveProfile(String rawProfile) {
        if (!OpenAiModelRouting.hasText(rawProfile)) {
            return "default";
        }
        if (profileService == null) {
            String profile = rawProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (isDefaultProfile(profile)) {
                return "default";
            }
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "profile-scoped model listing is not available in this Java agent configuration");
        }
        try {
            String profile = profileService.normalizeProfileName(rawProfile);
            profileService.validateProfileName(profile);
            if ("all".equals(profile)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "profile=all is not supported for models");
            }
            if (!profileService.knownProfile(profile)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown profile: " + profile);
            }
            return profile;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private static boolean isDefaultProfile(String profile) {
        return profile == null || "default".equals(profile);
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String text = stringValue(value);
            if (OpenAiModelRouting.hasText(text)) {
                return text;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private static Map<String, Object> modelEntry(String id, long created, String ownedBy, String root, String parent) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("object", "model");
        entry.put("created", created);
        entry.put("owned_by", ownedBy);
        entry.put("permission", List.of());
        entry.put("root", root);
        entry.put("parent", parent);
        return entry;
    }
}
