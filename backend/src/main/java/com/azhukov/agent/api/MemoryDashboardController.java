package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryProvider;
import com.azhukov.agent.core.memory.MemoryScope;
import com.azhukov.agent.service.ProfileService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"/api/memory", "/p/{profile}/api/memory"})
@Tag(name = "Hermes-compatible", description = "Dashboard memory compatibility")
public class MemoryDashboardController {

    private static final Pattern PROVIDER_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern FALLBACK_PROFILE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final MemoryProvider memoryProvider;
    private final ProfileService profileService;

    @Autowired
    public MemoryDashboardController(MemoryProvider memoryProvider, ProfileService profileService) {
        this.memoryProvider = memoryProvider;
        this.profileService = profileService;
    }

    MemoryDashboardController(MemoryProvider memoryProvider) {
        this(memoryProvider, null);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> status(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        String userId = memoryUserId(profile.profile());
        return ResponseEntity.ok(Map.of(
            "active", normalizedActiveProvider(),
            "providers", List.of(providerStatus()),
            "builtin_files", Map.of(
                "memory", memoryProvider.getCharCount(userId, "memory"),
                "user", memoryProvider.getCharCount(userId, "user")
            )
        ));
    }

    @PutMapping("/provider")
    public ResponseEntity<Map<String, Object>> selectProvider(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) MemoryProviderSelect body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        String requested = normalizeProviderName(body != null ? body.provider() : "");
        if (!requested.equals(normalizedActiveProvider())) {
            return badRequest("Unknown memory provider '" + (body != null ? body.provider() : "") + "'.");
        }
        return ResponseEntity.ok(Map.of("ok", true, "active", normalizedActiveProvider()));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) MemoryReset body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        String userId = memoryUserId(profile.profile());
        String target = body == null || body.target() == null || body.target().isBlank()
            ? "all"
            : body.target().trim().toLowerCase(Locale.ROOT);
        if (!List.of("all", "memory", "user").contains(target)) {
            return badRequest("target must be all, memory, or user");
        }

        try {
            List<String> deleted = switch (target) {
                case "memory" -> clearOne(userId, "memory") > 0 ? List.of("MEMORY.md") : List.of();
                case "user" -> clearOne(userId, "user") > 0 ? List.of("USER.md") : List.of();
                default -> {
                    int memoryDeleted = clearOne(userId, "memory");
                    int userDeleted = clearOne(userId, "user");
                    yield deletedFiles(memoryDeleted, userDeleted);
                }
            };
            return ResponseEntity.ok(Map.of("ok", true, "deleted", deleted));
        } catch (UnsupportedOperationException e) {
            return notImplemented("memory reset is not supported by the active provider");
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatusCode.valueOf(500)).body(errorBody(e.getMessage()));
        }
    }

    @GetMapping("/providers/{provider}/config")
    public ResponseEntity<Map<String, Object>> getProviderConfig(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String provider,
        @RequestParam(name = "surface", required = false) String surface,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        ResponseEntity<Map<String, Object>> invalid = validateProviderName(provider);
        if (invalid != null) {
            return invalid;
        }
        return ResponseEntity.ok(providerConfigPayload(provider));
    }

    @PutMapping("/providers/{provider}/config")
    public ResponseEntity<Map<String, Object>> saveProviderConfig(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String provider,
        @RequestBody(required = false) MemoryProviderConfigUpdate body,
        @RequestParam(name = "surface", required = false) String surface,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        ResponseEntity<Map<String, Object>> invalid = validateProvider(provider);
        if (invalid != null) {
            return invalid;
        }
        Map<String, Object> values = body != null && body.values() != null ? body.values() : Map.of();
        if (values.isEmpty() && memoryProvider.getConfigSchema().isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        return notImplemented("memory provider config writes are not implemented in Java agent");
    }

    @PostMapping("/providers/{provider}/setup")
    public ResponseEntity<Map<String, Object>> setupProvider(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String provider,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        ResponseEntity<Map<String, Object>> invalid = validateProvider(provider);
        if (invalid != null) {
            return invalid;
        }

        String name = providerNameForPayload(provider);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "setup");
        result.put("name", name);
        result.put("status", "no_declared_steps");
        result.put("command", "");
        result.put("returncode", null);
        result.put("stdout", "");
        result.put("stderr", "");

        return ResponseEntity.ok(Map.of(
            "ok", true,
            "provider", name,
            "results", List.of(result),
            "status", providerStatus()));
    }

    @PostMapping("/providers/{provider}/oauth/start")
    public ResponseEntity<Map<String, Object>> startProviderOAuth(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String provider,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        ResponseEntity<Map<String, Object>> invalid = validateProvider(provider);
        if (invalid != null) {
            return invalid;
        }
        return notFound(provider + " does not support OAuth connect");
    }

    @GetMapping("/providers/{provider}/oauth/status")
    public ResponseEntity<Map<String, Object>> providerOAuthStatus(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String provider,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile);
        if (profile.error() != null) {
            return profile.error();
        }
        ResponseEntity<Map<String, Object>> invalid = validateProvider(provider);
        if (invalid != null) {
            return invalid;
        }
        return notFound(provider + " does not support OAuth connect");
    }

    private int clearOne(String userId, String target) {
        return memoryProvider.clear(userId, target);
    }

    private String memoryUserId(String profile) {
        return MemoryScope.userId(AgentProperties.DEFAULT_USER_ID, profile);
    }

    private ProfileResolution resolveProfileScope(String pathProfile, String queryProfile) {
        ProfileResolution pathScope = normalizeProfileOrError(pathProfile);
        if (pathScope != null && pathScope.error() != null) {
            return pathScope;
        }
        ProfileResolution queryScope = normalizeProfileOrError(queryProfile);
        if (queryScope != null && queryScope.error() != null) {
            return queryScope;
        }
        String path = pathScope != null ? pathScope.profile() : null;
        String query = queryScope != null ? queryScope.profile() : null;
        if (path != null && query != null && !path.equals(query)) {
            return ProfileResolution.error(badRequest("profile query does not match route profile"));
        }
        String profile = firstNonBlank(path, query, "default");
        if (profileService != null && !profileService.knownProfile(profile)) {
            return ProfileResolution.error(notFound("Unknown profile: " + profile));
        }
        return ProfileResolution.ok(profile);
    }

    private ProfileResolution normalizeProfileOrError(String rawProfile) {
        if (!hasText(rawProfile)) {
            return null;
        }
        String raw = rawProfile.trim();
        if ("all".equalsIgnoreCase(raw)) {
            return ProfileResolution.error(badRequest("profile=all is not supported for memory"));
        }
        try {
            if (profileService != null) {
                String profile = profileService.normalizeProfileName(raw);
                profileService.validateProfileName(profile);
                return ProfileResolution.ok(profile);
            }
            String normalized = raw.toLowerCase(Locale.ROOT);
            if ("default".equals(normalized) || FALLBACK_PROFILE_NAME.matcher(normalized).matches()) {
                return ProfileResolution.ok(normalized);
            }
            return ProfileResolution.error(badRequest(
                "Invalid profile name '" + normalized + "'. Must match [a-z0-9][a-z0-9_-]{0,63}"));
        } catch (IllegalArgumentException e) {
            return ProfileResolution.error(badRequest(e.getMessage()));
        }
    }

    private Map<String, Object> providerStatus() {
        String name = providerName();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("description", "Java agent built-in memory provider");
        row.put("available", memoryProvider.isAvailable());
        row.put("configured", memoryProvider.isAvailable());
        row.put("status", memoryProvider.isAvailable() ? "ready" : "unavailable");
        row.put("setup", Map.of(
            "dependencies_installed", true,
            "pip_dependencies", List.of(),
            "external_dependencies", List.of()
        ));
        return row;
    }

    private Map<String, Object> providerConfigPayload(String requestedProvider) {
        String name = providerNameForPayload(requestedProvider);
        boolean active = normalizeProviderName(requestedProvider).equals(normalizedActiveProvider());
        return Map.of(
            "name", name,
            "label", providerLabel(name),
            "docs_url", "",
            "fields", active ? normalizedConfigFields(memoryProvider.getConfigSchema()) : List.of()
        );
    }

    private List<Map<String, Object>> normalizedConfigFields(List<Map<String, Object>> rawFields) {
        if (rawFields == null || rawFields.isEmpty()) {
            return List.of();
        }
        return rawFields.stream()
            .filter(field -> field != null && hasText(String.valueOf(field.getOrDefault("key", ""))))
            .map(this::normalizedConfigField)
            .toList();
    }

    private Map<String, Object> normalizedConfigField(Map<String, Object> raw) {
        String key = String.valueOf(raw.get("key")).trim();
        String kind = fieldKind(raw);
        Object defaultValue = raw.getOrDefault("default", "");
        String value = "secret".equals(kind) ? "" : String.valueOf(defaultValue != null ? defaultValue : "");
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", key);
        field.put("label", stringOrDefault(raw.get("label"), title(key)));
        field.put("kind", kind);
        field.put("description", stringOrDefault(raw.get("description"), ""));
        field.put("group", stringOrDefault(raw.get("group"), "memory"));
        field.put("info", stringOrDefault(raw.get("info"), ""));
        field.put("inline", booleanOrDefault(raw.get("inline"), false));
        field.put("is_set", "secret".equals(kind) ? false : !value.isBlank());
        field.put("options", normalizedOptions(raw));
        field.put("placeholder", stringOrDefault(raw.get("placeholder"), ""));
        field.put("value", value);
        return field;
    }

    private List<Map<String, Object>> normalizedOptions(Map<String, Object> raw) {
        Object options = raw.containsKey("options") ? raw.get("options") : raw.get("choices");
        if (!(options instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .map(option -> {
                if (option instanceof Map<?, ?> optionMap) {
                    String value = stringOrDefault(optionMap.get("value"), "");
                    return Map.<String, Object>of(
                        "value", value,
                        "label", stringOrDefault(optionMap.get("label"), value),
                        "description", stringOrDefault(optionMap.get("description"), "")
                    );
                }
                String value = String.valueOf(option);
                return Map.<String, Object>of("value", value, "label", value, "description", "");
            })
            .toList();
    }

    private String fieldKind(Map<String, Object> raw) {
        if (booleanOrDefault(raw.get("secret"), false)) {
            return "secret";
        }
        Object options = raw.containsKey("options") ? raw.get("options") : raw.get("choices");
        if (options instanceof List<?> list && !list.isEmpty()) {
            return "select";
        }
        String explicit = stringOrDefault(raw.get("kind"), stringOrDefault(raw.get("type"), ""))
            .toLowerCase(Locale.ROOT);
        return switch (explicit) {
            case "bool", "boolean" -> "bool";
            case "int", "integer", "float", "double", "number" -> "number";
            case "json", "secret", "select", "text" -> explicit;
            default -> "text";
        };
    }

    private ResponseEntity<Map<String, Object>> validateProvider(String provider) {
        ResponseEntity<Map<String, Object>> invalidName = validateProviderName(provider);
        if (invalidName != null) {
            return invalidName;
        }
        String requested = normalizeProviderName(provider);
        if (!requested.equals(normalizedActiveProvider())) {
            return notFound("Unknown memory provider: " + provider);
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> validateProviderName(String provider) {
        if (!hasText(provider) || !PROVIDER_NAME.matcher(provider).matches()) {
            return notFound("Unknown memory provider: " + provider);
        }
        return null;
    }

    private String normalizedActiveProvider() {
        return normalizeProviderName(memoryProvider.name());
    }

    private String providerName() {
        String normalized = normalizedActiveProvider();
        return normalized.isBlank() ? "builtin" : normalized;
    }

    private String providerNameForPayload(String requestedProvider) {
        String normalized = normalizeProviderName(requestedProvider);
        if (normalized.isBlank()) {
            return "builtin";
        }
        return normalized;
    }

    private static String normalizeProviderName(String provider) {
        String value = provider == null ? "" : provider.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.isBlank() || "built-in".equals(lower) || "builtin".equals(lower) || "none".equals(lower)) {
            return "";
        }
        return value;
    }

    private static List<String> deletedFiles(int memoryDeleted, int userDeleted) {
        if (memoryDeleted > 0 && userDeleted > 0) {
            return List.of("MEMORY.md", "USER.md");
        }
        if (memoryDeleted > 0) {
            return List.of("MEMORY.md");
        }
        if (userDeleted > 0) {
            return List.of("USER.md");
        }
        return List.of();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(errorBody(detail));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String detail) {
        return ResponseEntity.status(HttpStatusCode.valueOf(404)).body(errorBody(detail));
    }

    private static ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatusCode.valueOf(501)).body(errorBody(detail));
    }

    private static Map<String, Object> errorBody(String detail) {
        return Map.of("detail", detail, "error", detail);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String stringOrDefault(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static boolean booleanOrDefault(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "on").contains(text)) {
            return true;
        }
        if (List.of("false", "0", "no", "off").contains(text)) {
            return false;
        }
        return fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String providerLabel(String name) {
        return "builtin".equals(name) ? "Builtin" : title(name);
    }

    private static String title(String value) {
        String clean = value == null ? "" : value.replace('_', ' ').replace('-', ' ').trim();
        if (clean.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String part : clean.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }

    private record MemoryProviderSelect(String provider) {
    }

    private record MemoryReset(String target) {
    }

    private record MemoryProviderConfigUpdate(Map<String, Object> values) {
    }

    private record ProfileResolution(String profile, ResponseEntity<Map<String, Object>> error) {
        private static ProfileResolution ok(String profile) {
            return new ProfileResolution(profile, null);
        }

        private static ProfileResolution error(ResponseEntity<Map<String, Object>> error) {
            return new ProfileResolution(null, error);
        }
    }
}
