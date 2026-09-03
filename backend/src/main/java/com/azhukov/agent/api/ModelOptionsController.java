package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.FallbackConfig;
import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping({"/api/model", "/p/{profile}/api/model"})
@Tag(name = "Hermes-compatible", description = "Hermes-compatible model picker inventory")
public class ModelOptionsController {

    private final AgentProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final ProfileService profileService;
    private final CronJobService cronJobService;

    @Autowired
    public ModelOptionsController(AgentProperties properties,
                                  RuntimeConfigService runtimeConfigService,
                                  ProfileService profileService,
                                  CronJobService cronJobService) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.profileService = profileService;
        this.cronJobService = cronJobService;
    }

    ModelOptionsController(AgentProperties properties,
                           RuntimeConfigService runtimeConfigService,
                           ProfileService profileService) {
        this(properties, runtimeConfigService, profileService, null);
    }

    ModelOptionsController(AgentProperties properties, RuntimeConfigService runtimeConfigService) {
        this(properties, runtimeConfigService, null, null);
    }

    @GetMapping("/options")
    @Operation(summary = "List configured model providers in Hermes picker format")
    public Map<String, Object> options(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", defaultValue = "") String queryProfile,
        @RequestParam(name = "include_unconfigured", defaultValue = "true") boolean includeUnconfigured,
        @RequestParam(name = "explicit_only", defaultValue = "false") boolean explicitOnly,
        @RequestParam(name = "refresh", defaultValue = "false") boolean refresh
    ) {
        String profile = resolveProfileScope(pathProfile, queryProfile, null, "model options");
        CurrentModelSelection current = currentModelSelection(profile);

        LinkedHashMap<String, ProviderAccumulator> providers = new LinkedHashMap<>();
        addProvider(providers, current.provider(), current.model(), current.baseUrl(),
            isConfigured(current.baseUrl(), current.apiKey()));
        if (isDefaultProfile(profile)) {
            List<FallbackConfig> fallbackChain = properties.getFallbackChain();
            if (fallbackChain == null) {
                fallbackChain = List.of();
            }
            for (FallbackConfig fallback : fallbackChain) {
                if (fallback == null || isBlank(fallback.getModel())) {
                    continue;
                }
                addProvider(
                    providers,
                    defaultIfBlank(fallback.getProvider(), current.provider()),
                    fallback.getModel(),
                    fallback.getBaseUrl(),
                    isConfigured(fallback.getBaseUrl(), fallback.getApiKey()));
            }
        } else {
            addProfileProviderRows(providers, profile, current);
        }

        List<Map<String, Object>> rows = new ArrayList<>(providers.values().stream()
            .map(provider -> provider.toPayload(current.provider(), current.model()))
            .toList());
        if (includeUnconfigured) {
            rows.addAll(canonicalSkeletonRows(rows, current.provider()));
        }

        return Map.of(
            "providers", rows,
            "model", current.model(),
            "provider", current.provider()
        );
    }

    public Map<String, Object> options() {
        return options(null, "", false, false, false);
    }

    @GetMapping("/info")
    @Operation(summary = "Return resolved metadata for the configured model")
    public Map<String, Object> info(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", defaultValue = "") String queryProfile
    ) {
        String profile = resolveProfileScope(pathProfile, queryProfile, null, "model info");
        AgentProperties.ModelProperties model = properties.getModel();
        CurrentModelSelection current = currentModelSelection(profile);
        int configuredContext = configuredContextLength(profile);
        int autoContext = 0;
        int effectiveContext = configuredContext;

        Map<String, Object> capabilities = new LinkedHashMap<>();
        if (!isBlank(current.model())) {
            capabilities.put("supports_tools", true);
            capabilities.put("supports_vision", false);
            capabilities.put("supports_reasoning", true);
            capabilities.put("context_window", effectiveContext);
            capabilities.put("max_output_tokens", maxOutputTokens(profile, model));
            capabilities.put("model_family", current.provider());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", current.model());
        response.put("provider", current.provider());
        response.put("auto_context_length", autoContext);
        response.put("config_context_length", configuredContext);
        response.put("effective_context_length", effectiveContext);
        response.put("capabilities", capabilities);
        return response;
    }

    @GetMapping("/auxiliary")
    @Operation(summary = "Return configured auxiliary model task assignments")
    public Map<String, Object> auxiliary(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", defaultValue = "") String queryProfile
    ) {
        String profile = resolveProfileScope(pathProfile, queryProfile, null, "model auxiliary");
        CurrentModelSelection current = currentModelSelection(profile);

        List<Map<String, Object>> tasks = new ArrayList<>();
        for (String slot : AUXILIARY_TASK_SLOTS) {
            tasks.add(auxiliaryTask(profile, slot));
        }

        Map<String, Object> main = new LinkedHashMap<>();
        main.put("provider", current.provider());
        main.put("model", current.model());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tasks", tasks);
        response.put("main", main);
        return response;
    }

    @GetMapping("/recommended-default")
    @Operation(summary = "Resolve the recommended default model for a provider")
    public Map<String, Object> recommendedDefault(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", defaultValue = "") String queryProfile,
        @RequestParam(name = "provider", defaultValue = "") String provider
    ) {
        Map<String, Object> payload = options(pathProfile, queryProfile, false, false, false);
        String requested = defaultIfBlank(provider, String.valueOf(payload.get("provider")));
        String slug = requested.toLowerCase(Locale.ROOT);
        String model = "";
        Object rawProviders = payload.get("providers");
        if (rawProviders instanceof List<?> providers) {
            for (Object rawProvider : providers) {
                if (!(rawProvider instanceof Map<?, ?> row)) {
                    continue;
                }
                Object rawSlug = row.get("slug");
                if (rawSlug == null || !slug.equals(String.valueOf(rawSlug).toLowerCase(Locale.ROOT))) {
                    continue;
                }
                model = firstModel(row.get("models"));
                break;
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", requested);
        response.put("model", model);
        response.put("free_tier", null);
        return response;
    }

    @GetMapping("/moa")
    @Operation(summary = "Return disabled MoA config shape for the Java port")
    public Map<String, Object> moa(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", defaultValue = "") String queryProfile
    ) {
        resolveProfileScope(pathProfile, queryProfile, null, "MoA");
        return disabledMoaPayload();
    }

    @PutMapping("/moa")
    @Operation(summary = "Reject MoA writes because the Java port has no MoA runtime")
    public Map<String, Object> setMoa(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestBody(required = false) Map<String, Object> body,
        @RequestParam(name = "profile", defaultValue = "") String queryProfile
    ) {
        resolveProfileScope(pathProfile, queryProfile, bodyString(body, "profile"), "MoA");
        throw unsupported("MoA model assignments are not implemented in the Java port");
    }

    @PostMapping("/set")
    @Operation(summary = "Assign a runtime model in Hermes dashboard format")
    public Map<String, Object> setModelAssignment(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestBody(required = false) ModelAssignmentRequest body,
        @RequestParam(name = "profile", defaultValue = "") String queryProfile
    ) {
        if (body == null) {
            throw badRequest("request body required");
        }
        String profile = resolveProfileScope(pathProfile, queryProfile, body.profile(), "model assignment");
        String scope = clean(body.scope());
        if (!"main".equals(scope) && !"auxiliary".equals(scope)) {
            throw badRequest("scope must be 'main' or 'auxiliary'");
        }
        if ("auxiliary".equals(scope)) {
            String task = clean(body.task());
            if (!"__reset__".equals(task)) {
                String provider = clean(body.provider());
                if (provider == null) {
                    throw badRequest("provider required for auxiliary");
                }
                if (task != null && !AUXILIARY_TASK_SLOTS.contains(task)) {
                    throw badRequest("unknown auxiliary task: " + task);
                }
            }
            if (!isDefaultProfile(profile)) {
                return setProfileAuxiliaryAssignment(profile, task, clean(body.provider()), clean(body.model()),
                    body.baseUrlValue(), body.apiKeyValue());
            }
            throw unsupported("auxiliary model assignments are not implemented in the Java port");
        }

        String provider = clean(body.provider());
        String model = clean(body.model());
        if (provider == null || model == null) {
            throw badRequest("provider and model required for main");
        }
        if ("moa".equals(provider.toLowerCase(Locale.ROOT))) {
            throw unsupported("MoA model assignments are not implemented in the Java port");
        }
        if (!isDefaultProfile(profile)) {
            return setProfileMainAssignment(profile, provider, model, body.baseUrlValue(), body.apiKeyValue());
        }

        RuntimeConfigService.RuntimeModelSelection selection = runtimeConfigService.setModelSelection(
            provider,
            model,
            body.baseUrlValue(),
            body.apiKeyValue());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("scope", "main");
        String effectiveProvider = selection.provider() != null ? selection.provider() : provider;
        response.put("provider", effectiveProvider);
        response.put("model", selection.model());
        response.put("base_url", selection.baseUrl() != null ? selection.baseUrl() : "");
        response.put("gateway_tools", List.of());
        response.put("stale_aux", staleAuxiliary(effectiveProvider));
        response.put("cron_model_impact", cronModelImpact(profile, effectiveProvider, selection.model()));
        return response;
    }

    private String apiModelName() {
        AgentProperties.ApiProperties api = properties.getApi();
        if (api != null && !isBlank(api.getModelName())) {
            return api.getModelName();
        }
        return "hermes-agent";
    }

    private CurrentModelSelection currentModelSelection(String profile) {
        if (isDefaultProfile(profile)) {
            return currentModelSelection(properties.getModel());
        }
        Map<String, Object> config = readProfileConfig(profile);
        Object modelConfig = config.get("model");
        if (modelConfig instanceof Map<?, ?> modelMap) {
            Map<String, Object> model = toStringKeyMap(modelMap);
            String provider = clean(stringValue(model.get("provider")));
            String modelName = clean(stringValue(model.get("default")));
            if (modelName == null) {
                modelName = clean(stringValue(model.get("model")));
            }
            if (modelName == null) {
                modelName = clean(stringValue(model.get("name")));
            }
            Object nestedDefault = model.get("default");
            if (nestedDefault instanceof Map<?, ?> defaultMap) {
                Map<String, Object> defaultData = toStringKeyMap(defaultMap);
                if (provider == null) {
                    provider = clean(stringValue(defaultData.get("provider")));
                }
                modelName = firstNonBlank(
                    stringValue(defaultData.get("model")),
                    stringValue(defaultData.get("name")),
                    stringValue(defaultData.get("id")),
                    modelName);
            }
            return new CurrentModelSelection(
                defaultIfBlank(provider, ""),
                defaultIfBlank(modelName, ""),
                defaultIfBlank(stringValue(model.get("base_url")), ""),
                defaultIfBlank(stringValue(model.get("api_key")), ""));
        }
        return new CurrentModelSelection("", defaultIfBlank(stringValue(modelConfig), ""), "", "");
    }

    private CurrentModelSelection currentModelSelection(AgentProperties.ModelProperties model) {
        RuntimeConfigService.RuntimeModelSelection selection =
            runtimeConfigService != null ? runtimeConfigService.getModelSelection() : null;
        String configuredProvider = defaultIfBlank(model.getProvider(), "openai-compatible");
        String configuredModel = defaultIfBlank(model.getModelName(), apiModelName());
        if (selection == null || isBlank(selection.model())) {
            return new CurrentModelSelection(
                configuredProvider,
                configuredModel,
                defaultIfBlank(model.getBaseUrl(), ""),
                defaultIfBlank(model.getApiKey(), ""));
        }
        String provider = defaultIfBlank(selection.provider(), configuredProvider);
        boolean sameProviderAsConfig = provider.equalsIgnoreCase(configuredProvider);
        String baseUrl = !isBlank(selection.baseUrl())
            ? selection.baseUrl().trim()
            : sameProviderAsConfig ? defaultIfBlank(model.getBaseUrl(), "") : "";
        String apiKey = !isBlank(selection.apiKey())
            ? selection.apiKey().trim()
            : sameProviderAsConfig ? defaultIfBlank(model.getApiKey(), "") : "";
        return new CurrentModelSelection(provider, selection.model().trim(), baseUrl, apiKey);
    }

    private Map<String, Object> setProfileMainAssignment(String profile,
                                                         String provider,
                                                         String model,
                                                         String baseUrl,
                                                         String apiKey) {
        try {
            Map<String, Object> saved = profileService.writeModel(profile, provider, model, baseUrl, apiKey);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("scope", "main");
            response.put("provider", saved.get("provider"));
            response.put("model", saved.get("model"));
            response.put("base_url", saved.getOrDefault("base_url", ""));
            response.put("gateway_tools", List.of());
            response.put("stale_aux", staleAuxiliary(profile, provider));
            response.put("cron_model_impact", cronModelImpact(profile, provider, model));
            return response;
        } catch (IOException e) {
            throw serverError("Failed to save profile model assignment", e);
        }
    }

    private Map<String, Object> setProfileAuxiliaryAssignment(String profile,
                                                              String task,
                                                              String provider,
                                                              String model,
                                                              String baseUrl,
                                                              String apiKey) {
        Map<String, Object> config = readProfileConfig(profile);
        Map<String, Object> auxiliary = configMap(config.get("auxiliary"));
        if ("__reset__".equals(task)) {
            for (String slot : AUXILIARY_TASK_SLOTS) {
                Map<String, Object> slotConfig = configMap(auxiliary.get(slot));
                slotConfig.put("provider", "auto");
                slotConfig.put("model", "");
                slotConfig.remove("base_url");
                slotConfig.remove("api_key");
                auxiliary.put(slot, slotConfig);
            }
            config.put("auxiliary", auxiliary);
            writeProfileConfig(profile, config);
            return Map.of("ok", true, "scope", "auxiliary", "reset", true);
        }

        List<String> targets = task == null ? AUXILIARY_TASK_SLOTS : List.of(task);
        for (String slot : targets) {
            Map<String, Object> slotConfig = configMap(auxiliary.get(slot));
            slotConfig.put("provider", provider);
            slotConfig.put("model", defaultIfBlank(model, ""));
            if (!isBlank(baseUrl)) {
                slotConfig.put("base_url", baseUrl);
                if (!isBlank(apiKey)) {
                    slotConfig.put("api_key", apiKey);
                }
            } else {
                slotConfig.remove("base_url");
                if (!isBlank(apiKey)) {
                    slotConfig.put("api_key", apiKey);
                }
            }
            auxiliary.put(slot, slotConfig);
        }
        config.put("auxiliary", auxiliary);
        writeProfileConfig(profile, config);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("scope", "auxiliary");
        response.put("tasks", targets);
        response.put("provider", provider);
        response.put("model", defaultIfBlank(model, ""));
        return response;
    }

    private static void addProvider(LinkedHashMap<String, ProviderAccumulator> providers,
                                    String provider,
                                    String model,
                                    String baseUrl,
                                    boolean configured) {
        if (isBlank(provider) || isBlank(model)) {
            return;
        }
        String slug = provider.trim();
        providers.computeIfAbsent(slug.toLowerCase(Locale.ROOT),
                ignored -> new ProviderAccumulator(slug, displayName(slug), baseUrl, configured))
            .addModel(model.trim(), configured);
    }

    private void addProfileProviderRows(LinkedHashMap<String, ProviderAccumulator> providers,
                                        String profile,
                                        CurrentModelSelection current) {
        Object rawProviders = readProfileConfig(profile).get("providers");
        if (!(rawProviders instanceof Map<?, ?> providerMap)) {
            return;
        }
        for (Map.Entry<?, ?> entry : providerMap.entrySet()) {
            String provider = clean(stringValue(entry.getKey()));
            if (provider == null) {
                continue;
            }
            Map<String, Object> row = configMap(entry.getValue());
            String baseUrl = defaultIfBlank(stringValue(row.get("base_url")), "");
            String apiKey = defaultIfBlank(stringValue(row.get("api_key")), "");
            List<String> models = providerModels(row);
            if (models.isEmpty() && provider.equalsIgnoreCase(current.provider()) && !isBlank(current.model())) {
                models = List.of(current.model());
            }
            for (String model : models) {
                addProvider(providers, provider, model, baseUrl, isConfigured(baseUrl, apiKey));
            }
        }
    }

    private static List<String> providerModels(Map<String, Object> providerConfig) {
        Object rawModels = providerConfig.get("models");
        List<String> models = new ArrayList<>();
        if (rawModels instanceof List<?> list) {
            for (Object raw : list) {
                String model = clean(stringValue(raw));
                if (model != null && !models.contains(model)) {
                    models.add(model);
                }
            }
        }
        for (String key : List.of("default", "model", "model_name", "name")) {
            String model = clean(stringValue(providerConfig.get(key)));
            if (model != null && !models.contains(model)) {
                models.add(model);
            }
        }
        return models;
    }

    private static boolean isConfigured(String baseUrl, String apiKey) {
        return !isBlank(baseUrl) || !isBlank(apiKey);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static String bodyString(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        return value instanceof String text ? text.trim() : "";
    }

    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private String resolveProfileScope(String pathProfile, String queryProfile, String bodyProfile, String feature) {
        List<String> profiles = new ArrayList<>();
        for (String raw : new String[] {pathProfile, queryProfile, bodyProfile}) {
            if (isBlank(raw)) {
                continue;
            }
            profiles.add(normalizeProfile(raw, feature));
        }
        String profile = profiles.isEmpty() ? "default" : profiles.get(0);
        for (String candidate : profiles) {
            if (!profile.equals(candidate)) {
                throw badRequest("profile values do not match");
            }
        }
        if (!isDefaultProfile(profile) && profileService == null) {
            throw unsupported("profile-scoped " + feature + " is not available in this Java agent configuration");
        }
        if (profileService != null && !profileService.knownProfile(profile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown profile: " + profile);
        }
        return profile;
    }

    private String normalizeProfile(String rawProfile, String feature) {
        try {
            String profile = profileService != null
                ? profileService.normalizeProfileName(rawProfile)
                : rawProfile.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(profile)) {
                throw badRequest("profile=all is not supported for " + feature);
            }
            if (profileService != null) {
                profileService.validateProfileName(profile);
            } else if (!isDefaultProfile(profile)) {
                throw unsupported("profile-scoped " + feature + " is not available in this Java agent configuration");
            }
            return profile;
        } catch (IllegalArgumentException e) {
            throw badRequest(e.getMessage());
        }
    }

    private boolean isDefaultProfile(String profile) {
        return profile == null || "default".equals(profile);
    }

    private Map<String, Object> readProfileConfig(String profile) {
        try {
            return profileService.readConfig(profile);
        } catch (FileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IOException e) {
            throw serverError("Failed to read profile config", e);
        }
    }

    private void writeProfileConfig(String profile, Map<String, Object> config) {
        try {
            profileService.writeConfig(profile, config);
        } catch (FileNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IOException e) {
            throw serverError("Failed to write profile config", e);
        }
    }

    private static Map<String, Object> configMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), yamlValue(value));
            }
        });
        return result;
    }

    private static Object yamlValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return configMap(map);
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(ModelOptionsController::yamlValue).toList();
        }
        return raw;
    }

    private static Map<String, Object> toStringKeyMap(Map<?, ?> map) {
        return configMap(map);
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String cleaned = clean(value);
            if (cleaned != null) {
                return cleaned;
            }
        }
        return null;
    }

    private static String firstModel(Object rawModels) {
        if (!(rawModels instanceof List<?> models)) {
            return "";
        }
        for (Object model : models) {
            if (model != null && !String.valueOf(model).isBlank()) {
                return String.valueOf(model).trim();
            }
        }
        return "";
    }

    private int configuredContextLength(String profile) {
        if (isDefaultProfile(profile)) {
            return Math.max(0, properties.getContext() != null
                ? properties.getContext().getMaxTokens() : 0);
        }
        Object modelConfig = readProfileConfig(profile).get("model");
        if (modelConfig instanceof Map<?, ?> modelMap) {
            return positiveInt(configMap(modelMap).get("context_length"));
        }
        return 0;
    }

    private int maxOutputTokens(String profile, AgentProperties.ModelProperties model) {
        if (isDefaultProfile(profile)) {
            return Math.max(0, model != null ? model.getMaxTokens() : 0);
        }
        Object modelConfig = readProfileConfig(profile).get("model");
        if (modelConfig instanceof Map<?, ?> modelMap) {
            Map<String, Object> modelData = configMap(modelMap);
            int value = positiveInt(modelData.get("max_output_tokens"));
            return value > 0 ? value : positiveInt(modelData.get("max_tokens"));
        }
        return 0;
    }

    private static int positiveInt(Object raw) {
        if (raw instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            String text = clean(stringValue(raw));
            return text != null ? Math.max(0, Integer.parseInt(text)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, Object> auxiliaryTask(String profile, String slot) {
        if (!isDefaultProfile(profile)) {
            return profileAuxiliaryTask(profile, slot);
        }
        return auxiliaryTask(slot);
    }

    private Map<String, Object> profileAuxiliaryTask(String profile, String slot) {
        Map<String, Object> config = readProfileConfig(profile);
        Map<String, Object> auxiliary = configMap(config.get("auxiliary"));
        Map<String, Object> slotConfig = configMap(auxiliary.get(slot));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task", slot);
        row.put("provider", defaultIfBlank(stringValue(slotConfig.get("provider")), "auto"));
        row.put("model", defaultIfBlank(stringValue(slotConfig.get("model")), ""));
        row.put("base_url", defaultIfBlank(stringValue(slotConfig.get("base_url")), ""));
        return row;
    }

    private Map<String, Object> auxiliaryTask(String slot) {
        String provider = "auto";
        String model = "";
        String baseUrl = "";

        if ("vision".equals(slot) && properties.getVision() != null) {
            AgentProperties.VisionProperties vision = properties.getVision();
            provider = defaultIfBlank(vision.getProvider(), "auto");
            model = defaultIfBlank(vision.getModelName(), "");
            baseUrl = defaultIfBlank(vision.getBaseUrl(), "");
        } else if (properties.getAuxiliary() != null && properties.getAuxiliary().isEnabled()) {
            AgentProperties.AuxiliaryProperties auxiliary = properties.getAuxiliary();
            provider = defaultIfBlank(auxiliary.getProvider(), "auto");
            model = defaultIfBlank(auxiliary.getModelName(), "");
            baseUrl = defaultIfBlank(auxiliary.getBaseUrl(), "");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task", slot);
        row.put("provider", provider);
        row.put("model", model);
        row.put("base_url", baseUrl);
        return row;
    }

    private List<Map<String, Object>> staleAuxiliary(String profile, String newProvider) {
        if (isDefaultProfile(profile)) {
            return staleAuxiliary(newProvider);
        }
        String normalized = defaultIfBlank(newProvider, "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> stale = new ArrayList<>();
        for (String slot : AUXILIARY_TASK_SLOTS) {
            Map<String, Object> row = profileAuxiliaryTask(profile, slot);
            String provider = defaultIfBlank(String.valueOf(row.get("provider")), "");
            if (!provider.isBlank()
                && !"auto".equalsIgnoreCase(provider)
                && !provider.equalsIgnoreCase(normalized)) {
                stale.add(Map.of(
                    "task", slot,
                    "provider", provider,
                    "model", defaultIfBlank(String.valueOf(row.get("model")), "")));
            }
        }
        return stale;
    }

    private List<Map<String, Object>> staleAuxiliary(String newProvider) {
        String normalized = defaultIfBlank(newProvider, "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> stale = new ArrayList<>();
        for (String slot : AUXILIARY_TASK_SLOTS) {
            Map<String, Object> row = auxiliaryTask(slot);
            String provider = defaultIfBlank(String.valueOf(row.get("provider")), "");
            if (!provider.isBlank()
                && !"auto".equalsIgnoreCase(provider)
                && !provider.equalsIgnoreCase(normalized)) {
                stale.add(Map.of(
                    "task", slot,
                    "provider", provider,
                    "model", defaultIfBlank(String.valueOf(row.get("model")), "")));
            }
        }
        return stale;
    }

    private Map<String, Object> cronModelImpact(String profile, String currentProvider, String currentModel) {
        boolean guardEnabled = cronModelDriftGuardEnabled(profile);
        if (cronJobService == null) {
            return unavailableCronModelImpact(guardEnabled);
        }
        List<CronJobEntity> jobs;
        try {
            jobs = cronJobService.listForProfile(profile, true);
        } catch (RuntimeException e) {
            return unavailableCronModelImpact(guardEnabled);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("guard_enabled", guardEnabled);
        result.put("affected_count", 0);
        result.put("truncated", false);
        List<Map<String, Object>> affectedJobs = new ArrayList<>();
        result.put("jobs", affectedJobs);
        if (!guardEnabled) {
            return result;
        }

        Map<String, Object> config = impactConfig(profile);
        Set<String> seenIds = new HashSet<>();
        int affectedCount = 0;
        for (CronJobEntity job : jobs) {
            if (job == null || !job.isEnabled() || job.isNoAgent() || job.getId() == null) {
                continue;
            }
            String id = compactJobId(job);
            if (id == null || !seenIds.add(id)) {
                continue;
            }
            List<String> axes = cronModelDriftAxes(job, currentProvider, currentModel, config);
            if (axes.isEmpty()) {
                continue;
            }
            affectedCount++;
            if (affectedJobs.size() < 50) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", id);
                entry.put("name", cronImpactJobName(job.getName(), id));
                entry.put("drifted_axes", axes);
                affectedJobs.add(entry);
            }
        }
        result.put("affected_count", affectedCount);
        result.put("truncated", affectedCount > affectedJobs.size());
        return result;
    }

    private Map<String, Object> impactConfig(String profile) {
        if (profileService == null) {
            return Map.of();
        }
        try {
            return profileService.readConfig(profile);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private boolean cronModelDriftGuardEnabled(String profile) {
        Map<String, Object> config = impactConfig(profile);
        Map<String, Object> cron = configMap(config.get("cron"));
        return cron.get("model_drift_guard") != Boolean.FALSE;
    }

    private static List<String> cronModelDriftAxes(CronJobEntity job,
                                                   String currentProvider,
                                                   String currentModel,
                                                   Map<String, Object> config) {
        List<String> axes = new ArrayList<>();
        if (cronAxisDrifted(
            "provider",
            job.getModelProvider(),
            job.getProviderSnapshot(),
            currentProvider,
            config)) {
            axes.add("provider");
        }
        if (cronAxisDrifted(
            "model",
            job.getModelName(),
            job.getModelSnapshot(),
            currentModel,
            config)) {
            axes.add("model");
        }
        return axes;
    }

    private static boolean cronAxisDrifted(String axis,
                                           String pinnedValue,
                                           String snapshotValue,
                                           String currentValue,
                                           Map<String, Object> config) {
        if (cronFleetDefaultCoversAxis(axis, config) || !isBlank(pinnedValue)) {
            return false;
        }
        String snapshot = defaultIfBlank(snapshotValue, "").toLowerCase(Locale.ROOT);
        String current = defaultIfBlank(currentValue, "").toLowerCase(Locale.ROOT);
        return !snapshot.isBlank() && !current.isBlank() && !snapshot.equals(current);
    }

    private static boolean cronFleetDefaultCoversAxis(String axis, Map<String, Object> config) {
        Map<String, Object> cron = configMap(config.get("cron"));
        String key = "model".equals(axis) ? "model" : "model_provider";
        return !isBlank(stringValue(cron.get(key)));
    }

    private static String compactJobId(CronJobEntity job) {
        return job.getId().toString().replace("-", "").substring(0, 12);
    }

    private static String cronImpactJobName(String rawName, String jobId) {
        String name = rawName == null ? "" : rawName.replaceAll("\\p{Cntrl}", " ");
        name = String.join(" ", name.trim().split("\\s+"));
        if (name.isBlank()) {
            name = "Job " + jobId;
        }
        return name.length() > 120 ? name.substring(0, 120).stripTrailing() : name;
    }

    private static Map<String, Object> unavailableCronModelImpact(boolean guardEnabled) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("available", false);
        impact.put("guard_enabled", guardEnabled);
        impact.put("affected_count", 0);
        impact.put("truncated", false);
        impact.put("jobs", List.of());
        return impact;
    }

    private static Map<String, Object> emptyCronModelImpact() {
        return unavailableCronModelImpact(false);
    }

    private static Map<String, Object> disabledMoaPayload() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("provider", "");
        slot.put("model", "");
        slot.put("enabled", false);

        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("reference_models", List.of());
        preset.put("aggregator", slot);
        preset.put("reference_temperature", 0.2);
        preset.put("aggregator_temperature", 0.2);
        preset.put("reference_timeout", null);
        preset.put("degraded_reference_policy", "loud");
        preset.put("max_tokens", 0);
        preset.put("reference_max_tokens", null);
        preset.put("fanout", "user_turn");
        preset.put("enabled", false);

        Map<String, Object> presets = new LinkedHashMap<>();
        presets.put("default", preset);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("default_preset", "default");
        response.put("active_preset", "default");
        response.put("presets", presets);
        response.put("reference_models", List.of());
        response.put("aggregator", slot);
        response.put("reference_temperature", 0.2);
        response.put("aggregator_temperature", 0.2);
        response.put("reference_timeout", null);
        response.put("degraded_reference_policy", "loud");
        response.put("max_tokens", 0);
        response.put("enabled", false);
        response.put("available", false);
        response.put("detail", "MoA is not implemented in the Java port");
        return response;
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    private static ResponseStatusException unsupported(String detail) {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, detail);
    }

    private static ResponseStatusException serverError(String detail, Throwable cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, detail, cause);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String displayName(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "openai-compatible" -> "OpenAI Compatible";
            case "openai" -> "OpenAI";
            case "anthropic" -> "Anthropic";
            case "google", "gemini" -> "Google";
            case "ollama" -> "Ollama";
            default -> provider;
        };
    }

    private static final class ProviderAccumulator {
        private final String slug;
        private final String name;
        private final String baseUrl;
        private final List<String> models = new ArrayList<>();
        private boolean configured;

        private ProviderAccumulator(String slug, String name, String baseUrl, boolean configured) {
            this.slug = slug;
            this.name = name;
            this.baseUrl = baseUrl;
            this.configured = configured;
        }

        private ProviderAccumulator addModel(String model, boolean modelConfigured) {
            if (!models.contains(model)) {
                models.add(model);
            }
            configured = configured || modelConfigured;
            return this;
        }

        private Map<String, Object> toPayload(String currentProvider, String currentModel) {
            Map<String, Object> row = new LinkedHashMap<>();
            boolean isCurrent = slug.equalsIgnoreCase(currentProvider) && models.contains(currentModel);
            row.put("slug", slug);
            row.put("name", name);
            row.put("label", name);
            row.put("configured", configured);
            row.put("is_current", isCurrent);
            row.put("current", isCurrent);
            row.put("is_user_defined", true);
            row.put("source", "user-config");
            row.put("authenticated", configured);
            row.put("models", models);
            row.put("total_models", models.size());
            row.put("capabilities", capabilities(models));
            row.put("featured_models", List.of());
            if (!isBlank(baseUrl)) {
                row.put("base_url", baseUrl);
                row.put("api_url", baseUrl);
            }
            return row;
        }
    }

    private static List<Map<String, Object>> canonicalSkeletonRows(List<Map<String, Object>> rows,
                                                                    String currentProvider) {
        List<String> seen = rows.stream()
            .map(row -> String.valueOf(row.getOrDefault("slug", "")).toLowerCase(Locale.ROOT))
            .toList();
        String current = defaultIfBlank(currentProvider, "").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> skeletons = new ArrayList<>();
        for (CanonicalProvider provider : CANONICAL_PROVIDERS) {
            if (seen.contains(provider.slug().toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean isCurrent = provider.slug().equalsIgnoreCase(current);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", provider.slug());
            row.put("name", provider.label());
            row.put("label", provider.label());
            row.put("configured", false);
            row.put("is_current", isCurrent);
            row.put("current", isCurrent);
            row.put("is_user_defined", false);
            row.put("source", "canonical");
            row.put("authenticated", false);
            row.put("auth_type", provider.authType());
            row.put("key_env", provider.keyEnv());
            row.put("warning", provider.keyEnv().isBlank()
                ? "run `hermes model` to configure (" + provider.authType() + ")"
                : "paste " + provider.keyEnv() + " to activate");
            row.put("models", List.of());
            row.put("total_models", 0);
            row.put("capabilities", Map.of());
            row.put("featured_models", List.of());
            skeletons.add(row);
        }
        return skeletons;
    }

    private static Map<String, Object> capabilities(List<String> models) {
        LinkedHashMap<String, Object> capabilities = new LinkedHashMap<>();
        for (String model : models) {
            capabilities.put(model, Map.of(
                "fast", modelSupportsFastMode(model),
                "reasoning", true
            ));
        }
        return capabilities;
    }

    private static boolean modelSupportsFastMode(String model) {
        if (isBlank(model)) {
            return false;
        }
        String normalized = model.toLowerCase(Locale.ROOT);
        return normalized.contains("gpt-5")
            || normalized.contains("codex")
            || normalized.contains("kimi")
            || normalized.contains("glm")
            || normalized.contains("qwen")
            || normalized.contains("claude")
            || normalized.contains("gemini")
            || normalized.contains("deepseek");
    }

    private record CanonicalProvider(String slug, String label, String authType, String keyEnv) {
    }

    private record CurrentModelSelection(String provider, String model, String baseUrl, String apiKey) {
    }

    private record ModelAssignmentRequest(
        @JsonProperty("scope") String scope,
        @JsonProperty("provider") String provider,
        @JsonProperty("model") String model,
        @JsonProperty("task") String task,
        @JsonProperty("base_url") @JsonAlias("baseUrl") String baseUrl,
        @JsonProperty("api_key") @JsonAlias("apiKey") String apiKey,
        @JsonProperty("confirm_expensive_model") @JsonAlias("confirmExpensiveModel") Boolean confirmExpensiveModel,
        @JsonProperty("profile") String profile
    ) {
        private String baseUrlValue() {
            return clean(baseUrl);
        }

        private String apiKeyValue() {
            return clean(apiKey);
        }
    }

    private static final List<CanonicalProvider> CANONICAL_PROVIDERS = List.of(
        new CanonicalProvider("nous", "Nous Portal", "api_key", ""),
        new CanonicalProvider("fireworks", "Fireworks AI", "api_key", "FIREWORKS_API_KEY"),
        new CanonicalProvider("openrouter", "OpenRouter", "api_key", "OPENROUTER_API_KEY"),
        new CanonicalProvider("moa", "Mixture of Agents", "virtual", ""),
        new CanonicalProvider("novita", "NovitaAI", "api_key", "NOVITA_API_KEY"),
        new CanonicalProvider("lmstudio", "LM Studio", "api_key", "LM_API_KEY"),
        new CanonicalProvider("anthropic", "Anthropic", "api_key", "ANTHROPIC_API_KEY"),
        new CanonicalProvider("openai-codex", "ChatGPT or Codex Subscription", "oauth_external", ""),
        new CanonicalProvider("openai-api", "OpenAI API", "api_key", "OPENAI_API_KEY"),
        new CanonicalProvider("alibaba", "Qwen Cloud", "api_key", "DASHSCOPE_API_KEY"),
        new CanonicalProvider("xai-oauth", "xAI Grok OAuth (SuperGrok / Premium+)", "oauth_external", ""),
        new CanonicalProvider("xiaomi", "Xiaomi MiMo", "api_key", "XIAOMI_API_KEY"),
        new CanonicalProvider("tencent-tokenhub", "Tencent TokenHub", "api_key", "TOKENHUB_API_KEY"),
        new CanonicalProvider("nvidia", "NVIDIA NIM", "api_key", "NVIDIA_API_KEY"),
        new CanonicalProvider("copilot", "GitHub Copilot", "api_key", "COPILOT_GITHUB_TOKEN"),
        new CanonicalProvider("copilot-acp", "GitHub Copilot ACP", "external_process", ""),
        new CanonicalProvider("huggingface", "Hugging Face", "api_key", "HF_TOKEN"),
        new CanonicalProvider("gemini", "Google AI Studio", "api_key", "GOOGLE_API_KEY"),
        new CanonicalProvider("vertex", "Google Vertex AI", "vertex", ""),
        new CanonicalProvider("deepseek", "DeepSeek", "api_key", "DEEPSEEK_API_KEY"),
        new CanonicalProvider("xai", "xAI", "api_key", "XAI_API_KEY"),
        new CanonicalProvider("zai", "Z.AI / GLM", "api_key", "GLM_API_KEY"),
        new CanonicalProvider("kimi-coding", "Kimi / Kimi Coding Plan", "api_key", "KIMI_API_KEY"),
        new CanonicalProvider("kimi-coding-cn", "Kimi / Moonshot (China)", "api_key", "KIMI_CN_API_KEY"),
        new CanonicalProvider("stepfun", "StepFun Step Plan", "api_key", "STEPFUN_API_KEY"),
        new CanonicalProvider("minimax", "MiniMax", "api_key", "MINIMAX_API_KEY"),
        new CanonicalProvider("minimax-oauth", "MiniMax (OAuth)", "oauth_minimax", ""),
        new CanonicalProvider("minimax-cn", "MiniMax (China)", "api_key", "MINIMAX_CN_API_KEY"),
        new CanonicalProvider("ollama-cloud", "Ollama Cloud", "api_key", "OLLAMA_API_KEY"),
        new CanonicalProvider("arcee", "Arcee AI", "api_key", "ARCEEAI_API_KEY"),
        new CanonicalProvider("gmi", "GMI Cloud", "api_key", "GMI_API_KEY"),
        new CanonicalProvider("kilocode", "Kilo Code", "api_key", "KILOCODE_API_KEY"),
        new CanonicalProvider("opencode-zen", "OpenCode Zen", "api_key", "OPENCODE_ZEN_API_KEY"),
        new CanonicalProvider("opencode-go", "OpenCode Go", "api_key", "OPENCODE_GO_API_KEY"),
        new CanonicalProvider("bedrock", "AWS Bedrock", "aws_sdk", ""),
        new CanonicalProvider("azure-foundry", "Azure Foundry", "api_key", "AZURE_FOUNDRY_API_KEY"),
        new CanonicalProvider("ai-gateway", "Vercel AI Gateway", "api_key", "AI_GATEWAY_API_KEY"),
        new CanonicalProvider("qwen-oauth", "Qwen OAuth (Portal)", "oauth_external", "")
    );

    private static final List<String> AUXILIARY_TASK_SLOTS = List.of(
        "vision",
        "compression",
        "skills_hub",
        "approval",
        "mcp",
        "title_generation",
        "review",
        "triage_specifier",
        "kanban_decomposer",
        "profile_describer",
        "curator"
    );
}
