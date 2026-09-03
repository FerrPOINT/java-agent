package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.core.skill.SkillUtils;
import com.azhukov.agent.core.skill.SkillsHubService;
import com.azhukov.agent.core.skill.WriteOrigin;
import com.azhukov.agent.service.ProfileService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@RestController
@RequestMapping({"/api/skills", "/p/{profile}/api/skills"})
@Slf4j
@Tag(name = "Hermes-compatible", description = "Dashboard skills compatibility")
public class SkillsDashboardController {

    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private final SkillManager skillManager;
    private final AgentProperties properties;
    private final SkillsHubService skillsHubService;
    private final ProfileService profileService;

    @Autowired
    public SkillsDashboardController(
        SkillManager skillManager,
        AgentProperties properties,
        SkillsHubService skillsHubService,
        ProfileService profileService
    ) {
        this.skillManager = skillManager;
        this.properties = properties;
        this.skillsHubService = skillsHubService;
        this.profileService = profileService;
    }

    SkillsDashboardController(SkillManager skillManager, AgentProperties properties, SkillsHubService skillsHubService) {
        this(skillManager, properties, skillsHubService, null);
    }

    @GetMapping
    public ResponseEntity<?> listSkills(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        try {
            Set<String> disabled = disabledSkillNames(profile.profile());
            List<SkillManager.SkillInfo> source = isDefaultProfile(profile.profile())
                ? skillManager.listSkills()
                : listProfileSkillInfos(profile.profile());
            List<Map<String, Object>> skills = source.stream()
                .filter(skill -> !skill.archived())
                .sorted(Comparator.comparing(SkillsDashboardController::skillSortCategory)
                    .thenComparing(SkillManager.SkillInfo::name))
                .map(skill -> skillPayload(skill, disabled))
                .toList();
            return ResponseEntity.ok(skills);
        } catch (IOException | RuntimeException e) {
            log.warn("GET /api/skills failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to enumerate skills"));
        }
    }

    @PutMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleSkill(
        @RequestBody(required = false) SkillToggle body,
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        if (body == null || blank(body.name())) {
            return badRequest("name is required");
        }
        if (body.enabled() == null) {
            return badRequest("enabled is required");
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body.profile());
        if (profile.error() != null) {
            return profile.error();
        }
        try {
            if (isDefaultProfile(profile.profile())) {
                List<String> disabled = properties.getSkills().getDisabled();
                if (body.enabled()) {
                    disabled.removeIf(name -> body.name().equals(name));
                } else if (!disabled.contains(body.name())) {
                    disabled.add(body.name());
                }
            } else {
                updateProfileDisabledSkill(profile.profile(), body.name(), body.enabled());
            }
        } catch (IOException e) {
            log.warn("PUT /api/skills/toggle failed for profile {}", profile.profile(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to update profile skills config"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "name", body.name(), "enabled", body.enabled()));
    }

    @GetMapping("/content")
    public ResponseEntity<Map<String, Object>> skillContent(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam String name,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        if (blank(name)) {
            return badRequest("name is required");
        }
        ResponseEntity<Map<String, Object>> invalidName = validateSkillName(name);
        if (invalidName != null) {
            return invalidName;
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        if (!isDefaultProfile(profile.profile())) {
            try {
                ProfileSkillLookup lookup = findProfileSkill(profile.profile(), name);
                if (lookup.error() != null) {
                    return badRequest(lookup.error());
                }
                if (lookup.info() == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "Skill '" + name + "' not found."));
                }
                return ResponseEntity.ok(Map.of(
                    "name", name,
                    "content", lookup.info().content() != null ? lookup.info().content() : "",
                    "path", lookup.path().toString()
                ));
            } catch (IOException e) {
                log.warn("GET /api/skills/content failed for profile {}", profile.profile(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("detail", "Failed to read profile skill"));
            }
        }
        SkillManager.SkillLookupResult lookup = skillManager.getSkillInfoMultiStrategy(name);
        if (lookup.error() != null) {
            return ResponseEntity.badRequest().body(Map.of("detail", lookup.error()));
        }
        SkillManager.SkillInfo info = lookup.info();
        if (info == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Skill '" + name + "' not found."));
        }
        return ResponseEntity.ok(Map.of(
            "name", name,
            "content", info.content() != null ? info.content() : "",
            "path", skillPath(name)
        ));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createSkill(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) SkillContentBody body
    ) {
        if (body == null || blank(body.name())) {
            return badRequest("name is required");
        }
        if (body.content() == null) {
            return badRequest("content is required");
        }
        ResponseEntity<Map<String, Object>> invalidName = validateSkillName(body.name());
        if (invalidName != null) {
            return invalidName;
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body.profile());
        if (profile.error() != null) {
            return profile.error();
        }
        if (!isDefaultProfile(profile.profile())) {
            return createProfileSkill(profile.profile(), body);
        }
        SkillManager.SkillLookupResult lookup = skillManager.getSkillInfoMultiStrategy(body.name());
        if (lookup.error() != null) {
            return badRequest(lookup.error());
        }
        if (lookup.info() != null) {
            return badRequest("Skill '" + body.name() + "' already exists.");
        }
        try {
            skillManager.saveSkill(body.name(), body.content(), WriteOrigin.USER);
            String stored = skillManager.getSkill(body.name());
            if (stored == null) {
                return notImplemented("skill writes are not available in this Java agent configuration");
            }
            return ResponseEntity.ok(editorSuccessPayload(body.name(), body.content(), body.category(), true));
        } catch (IllegalArgumentException | SecurityException e) {
            return badRequest(e.getMessage());
        } catch (UnsupportedOperationException e) {
            return notImplemented("skill writes are not available in this Java agent configuration");
        }
    }

    @PutMapping("/content")
    public ResponseEntity<Map<String, Object>> updateSkillContent(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) SkillContentBody body
    ) {
        if (body == null || blank(body.name())) {
            return badRequest("name is required");
        }
        if (body.content() == null) {
            return badRequest("content is required");
        }
        ResponseEntity<Map<String, Object>> invalidName = validateSkillName(body.name());
        if (invalidName != null) {
            return invalidName;
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body.profile());
        if (profile.error() != null) {
            return profile.error();
        }
        if (!isDefaultProfile(profile.profile())) {
            return updateProfileSkill(profile.profile(), body);
        }
        SkillManager.SkillLookupResult lookup = skillManager.getSkillInfoMultiStrategy(body.name());
        if (lookup.error() != null) {
            return badRequest(lookup.error());
        }
        if (lookup.info() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Skill '" + body.name() + "' not found."));
        }
        try {
            skillManager.saveSkill(body.name(), body.content(), WriteOrigin.USER);
            return ResponseEntity.ok(editorSuccessPayload(body.name(), body.content(), null, false));
        } catch (IllegalArgumentException | SecurityException e) {
            return badRequest(e.getMessage());
        } catch (UnsupportedOperationException e) {
            return notImplemented("skill writes are not available in this Java agent configuration");
        }
    }

    @GetMapping("/hub/sources")
    public ResponseEntity<Map<String, Object>> hubSources(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", "github");
        source.put("label", "Configured GitHub repo");
        source.put("rate_limited", false);
        source.put("searchable", true);
        return ResponseEntity.ok(Map.of(
            "sources", List.of(source),
            "index_available", false,
            "featured", List.of(),
            "installed", Map.of()
        ));
    }

    @GetMapping("/hub/search")
    public ResponseEntity<Map<String, Object>> searchHub(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "q", defaultValue = "") String query,
        @RequestParam(name = "source", defaultValue = "all") String source,
        @RequestParam(name = "limit", defaultValue = "20") int limit,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        if (query == null || query.isBlank()) {
            return ResponseEntity.ok(Map.of("results", List.of(), "source_counts", Map.of(), "timed_out", List.of(), "installed", Map.of()));
        }
        int capped = Math.min(Math.max(limit, 1), 50);
        List<Map<String, Object>> results = skillsHubService.searchRemoteSkills(query).stream()
            .limit(capped)
            .map(this::hubResult)
            .toList();
        return ResponseEntity.ok(Map.of(
            "results", results,
            "source_counts", Map.of("github", results.size()),
            "timed_out", List.of(),
            "installed", Map.of()
        ));
    }

    @GetMapping("/hub/preview")
    public ResponseEntity<Map<String, Object>> previewHub(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "identifier", defaultValue = "") String identifier,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        if (identifier == null || identifier.isBlank()) {
            return badRequest("identifier is required");
        }
        String ident = identifier.trim();
        try {
            SkillsHubService.HubPreview preview = skillsHubService.preview(ident);
            if (preview == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "Skill not found: " + ident));
            }
            return ResponseEntity.ok(hubPreviewPayload(preview));
        } catch (RuntimeException e) {
            log.warn("GET /api/skills/hub/preview failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("detail", "Hub preview failed: " + e.getMessage()));
        }
    }

    @GetMapping("/hub/scan")
    public ResponseEntity<Map<String, Object>> scanHub(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "identifier", defaultValue = "") String identifier,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, null);
        if (profile.error() != null) {
            return profile.error();
        }
        if (identifier == null || identifier.isBlank()) {
            return badRequest("identifier is required");
        }
        String ident = identifier.trim();
        try {
            SkillsHubService.HubScan scan = skillsHubService.scan(ident);
            if (scan == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "Skill not found: " + ident));
            }
            return ResponseEntity.ok(hubScanPayload(scan));
        } catch (RuntimeException e) {
            log.warn("GET /api/skills/hub/scan failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("detail", "Hub scan failed: " + e.getMessage()));
        }
    }

    @PostMapping("/hub/install")
    public ResponseEntity<Map<String, Object>> installHub(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) HubInstallBody body
    ) {
        if (body == null || blank(body.identifier())) {
            return badRequest("identifier is required");
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body.profile());
        if (profile.error() != null) {
            return profile.error();
        }
        return notImplemented("background skills hub install is not implemented in the Java port");
    }

    @PostMapping("/hub/uninstall")
    public ResponseEntity<Map<String, Object>> uninstallHub(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) HubUninstallBody body
    ) {
        if (body == null || blank(body.name())) {
            return badRequest("name is required");
        }
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, body.profile());
        if (profile.error() != null) {
            return profile.error();
        }
        return notImplemented("background skills hub uninstall is not implemented in the Java port");
    }

    @PostMapping("/hub/update")
    public ResponseEntity<Map<String, Object>> updateHub(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ProfileResolution profile = resolveProfileScope(pathProfile, queryProfile, stringValue(body != null ? body.get("profile") : null));
        if (profile.error() != null) {
            return profile.error();
        }
        return notImplemented("background skills hub update is not implemented in the Java port");
    }

    private List<SkillManager.SkillInfo> listProfileSkillInfos(String profile) throws IOException {
        Path skillsDir = profileSkillsDir(profile);
        if (!Files.isDirectory(skillsDir)) {
            return List.of();
        }
        Map<String, SkillManager.SkillInfo> found = new LinkedHashMap<>();
        List<Path> skillFiles = SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md").stream()
            .filter(path -> !SkillUtils.isExcludedSkillPath(path))
            .sorted(Comparator.comparing(Path::toString))
            .toList();
        for (Path skillFile : skillFiles) {
            SkillManager.SkillInfo info = readProfileSkill(skillFile);
            if (info != null && !blank(info.name())) {
                found.putIfAbsent(info.name(), info);
            }
        }
        return new ArrayList<>(found.values());
    }

    private ProfileSkillLookup findProfileSkill(String profile, String name) throws IOException {
        Path skillsDir = profileSkillsDir(profile);
        if (!Files.isDirectory(skillsDir)) {
            return ProfileSkillLookup.notFound();
        }
        List<Path> candidates = new ArrayList<>();
        Path direct = profileSkillFile(profile, name);
        if (Files.isRegularFile(direct)) {
            candidates.add(direct);
        }
        for (Path skillFile : SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md")) {
            if (SkillUtils.isExcludedSkillPath(skillFile) || skillFile.equals(direct)) {
                continue;
            }
            SkillManager.SkillInfo info = readProfileSkill(skillFile);
            if (info != null && name.equals(info.name())) {
                candidates.add(skillFile);
            }
        }
        if (candidates.isEmpty()) {
            return ProfileSkillLookup.notFound();
        }
        if (candidates.size() > 1) {
            List<String> paths = candidates.stream().map(Path::toString).toList();
            return ProfileSkillLookup.error("Ambiguous skill name '" + name + "': "
                + candidates.size() + " skills match across the profile filesystem. Refusing to guess: "
                + String.join("; ", paths));
        }
        Path path = candidates.get(0);
        return new ProfileSkillLookup(readProfileSkill(path), path, null);
    }

    private ResponseEntity<Map<String, Object>> createProfileSkill(String profile, SkillContentBody body) {
        try {
            ProfileSkillLookup lookup = findProfileSkill(profile, body.name());
            if (lookup.error() != null) {
                return badRequest(lookup.error());
            }
            if (lookup.info() != null) {
                return badRequest("Skill '" + body.name() + "' already exists.");
            }
            ResponseEntity<Map<String, Object>> invalidContent = validateSkillContent(body.content());
            if (invalidContent != null) {
                return invalidContent;
            }
            Path skillFile = profileSkillFile(profile, body.name());
            Files.createDirectories(skillFile.getParent());
            Files.writeString(skillFile, body.content(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            return ResponseEntity.ok(editorSuccessPayload(
                body.name(), body.content(), body.category(), true, skillFile.toString()));
        } catch (IllegalArgumentException | SecurityException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            log.warn("POST /api/skills failed for profile {}", profile, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to write profile skill"));
        }
    }

    private ResponseEntity<Map<String, Object>> updateProfileSkill(String profile, SkillContentBody body) {
        try {
            ProfileSkillLookup lookup = findProfileSkill(profile, body.name());
            if (lookup.error() != null) {
                return badRequest(lookup.error());
            }
            if (lookup.info() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "Skill '" + body.name() + "' not found."));
            }
            ResponseEntity<Map<String, Object>> invalidContent = validateSkillContent(body.content());
            if (invalidContent != null) {
                return invalidContent;
            }
            Files.writeString(lookup.path(), body.content(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            return ResponseEntity.ok(editorSuccessPayload(body.name(), body.content(), null, false, lookup.path().toString()));
        } catch (IllegalArgumentException | SecurityException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            log.warn("PUT /api/skills/content failed for profile {}", profile, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Failed to write profile skill"));
        }
    }

    private SkillManager.SkillInfo readProfileSkill(Path skillFile) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            SkillUtils.FrontmatterResult frontmatter = SkillUtils.parseFrontmatter(content);
            Map<String, Object> meta = frontmatter.frontmatter();
            String name = firstNonBlank(stringValue(meta.get("name")), skillDirectoryName(skillFile));
            if (name == null) {
                return null;
            }
            boolean disabled = booleanValue(meta.get("disabled"));
            return new SkillManager.SkillInfo(
                name,
                content,
                stringValue(meta.get("description")),
                stringValue(meta.get("category")),
                null,
                0,
                0,
                null,
                false,
                "AGENT_CREATED",
                SkillUtils.parseTags(meta.get("tags")),
                SkillUtils.parseTags(meta.get("related_skills")),
                disabled,
                SkillManager.LinkedFiles.fromFlatList(List.of())
            );
        } catch (IOException e) {
            log.debug("Failed to read profile skill {}", skillFile, e);
            return null;
        }
    }

    private void updateProfileDisabledSkill(String profile, String name, boolean enabled) throws IOException {
        Map<String, Object> config = readProfileConfig(profile);
        Map<String, Object> skills = mapValue(config.get("skills"));
        Set<String> disabled = disabledSkillNamesFromConfig(config);
        if (enabled) {
            disabled.remove(name);
        } else {
            disabled.add(name);
        }
        skills.put("disabled", disabled.stream().sorted().toList());
        config.put("skills", skills);
        writeProfileConfig(profile, config);
    }

    private Set<String> disabledSkillNames(String profile) throws IOException {
        if (!isDefaultProfile(profile)) {
            return disabledSkillNamesFromConfig(readProfileConfig(profile));
        }
        Set<String> disabled = new LinkedHashSet<>();
        if (properties.getSkills() == null) {
            return disabled;
        }
        for (String name : properties.getSkills().getDisabled()) {
            if (name != null && !name.isBlank()) {
                disabled.add(name.trim());
            }
        }
        return disabled;
    }

    private Set<String> disabledSkillNamesFromConfig(Map<String, Object> config) {
        Set<String> disabled = new LinkedHashSet<>();
        Map<String, Object> skills = mapValue(config.get("skills"));
        Object raw = skills.get("disabled");
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String name = stringValue(item);
                if (!blank(name)) {
                    disabled.add(name);
                }
            }
        } else if (raw instanceof String text && !text.isBlank()) {
            for (String item : text.split(",")) {
                if (!item.isBlank()) {
                    disabled.add(item.trim());
                }
            }
        }
        return disabled;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readProfileConfig(String profile) throws IOException {
        Path path = profileService.profilePath(profile).resolve("config.yaml");
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<>();
        }
        Object loaded = new Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
        if (loaded instanceof Map<?, ?> map) {
            return toStringKeyMap(map);
        }
        return new LinkedHashMap<>();
    }

    private void writeProfileConfig(String profile, Map<String, Object> config) throws IOException {
        Path path = profileService.profilePath(profile).resolve("config.yaml");
        Files.createDirectories(path.getParent());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setAllowUnicode(true);
        String dumped = new Yaml(options).dump(config != null ? config : Map.of());
        Files.writeString(path, dumped.endsWith("\n") ? dumped : dumped + "\n",
            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Path profileSkillsDir(String profile) {
        Path skillsDir = profileService.profilePath(profile).resolve("skills").toAbsolutePath().normalize();
        Path profileDir = profileService.profilePath(profile).toAbsolutePath().normalize();
        if (!skillsDir.startsWith(profileDir)) {
            throw new SecurityException("Invalid profile skills path");
        }
        return skillsDir;
    }

    private Path profileSkillFile(String profile, String name) {
        Path skillsDir = profileSkillsDir(profile);
        Path skillFile = skillsDir.resolve(name).resolve("SKILL.md").toAbsolutePath().normalize();
        if (!skillFile.startsWith(skillsDir)) {
            throw new SecurityException("Invalid skill path");
        }
        return skillFile;
    }

    private ProfileResolution resolveProfileScope(String pathProfile, String queryProfile, String bodyProfile) {
        List<ResolvedProfile> resolved = new ArrayList<>();
        for (String raw : new String[] {pathProfile, queryProfile, bodyProfile}) {
            if (!blank(raw)) {
                ProfileResolution profile = normalizeProfile(raw);
                if (profile.error() != null) {
                    return profile;
                }
                resolved.add(new ResolvedProfile(raw, profile.profile()));
            }
        }
        String profile = resolved.isEmpty() ? "default" : resolved.get(0).profile();
        for (ResolvedProfile item : resolved) {
            if (!profile.equals(item.profile())) {
                return ProfileResolution.error(badRequest("profile values do not match"));
            }
        }
        if (!isDefaultProfile(profile) && profileService == null) {
            return ProfileResolution.error(notImplemented("profile-scoped skills are not available in this Java agent configuration"));
        }
        if (profileService != null && !profileService.knownProfile(profile)) {
            return ProfileResolution.error(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "Unknown profile: " + profile)));
        }
        return ProfileResolution.ok(profile);
    }

    private ProfileResolution normalizeProfile(String rawProfile) {
        try {
            String profile = profileService != null
                ? profileService.normalizeProfileName(rawProfile)
                : rawProfile.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(profile)) {
                return ProfileResolution.error(badRequest("profile=all is not supported for skills"));
            }
            if (profileService != null) {
                profileService.validateProfileName(profile);
            } else if (!isDefaultProfile(profile)) {
                return ProfileResolution.error(notImplemented("profile-scoped skills are not available in this Java agent configuration"));
            }
            return ProfileResolution.ok(profile);
        } catch (IllegalArgumentException e) {
            return ProfileResolution.error(badRequest(e.getMessage()));
        }
    }

    private static ResponseEntity<Map<String, Object>> validateSkillName(String name) {
        if (!SKILL_NAME.matcher(name).matches()) {
            return badRequest("Invalid skill name");
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> validateSkillContent(String content) {
        if (content == null || content.isBlank()) {
            return badRequest("Content cannot be empty.");
        }
        String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content;
        if (!normalized.startsWith("---")) {
            return badRequest("SKILL.md must start with YAML frontmatter (---). See existing skills for format.");
        }
        int endIdx = normalized.indexOf("\n---", 3);
        if (endIdx < 0) {
            return badRequest("SKILL.md frontmatter is not closed. Ensure you have a closing '---' line.");
        }
        String yamlContent = normalized.substring(3, endIdx).trim();
        if (!yamlContent.contains("name:")) {
            return badRequest("Frontmatter must include 'name' field.");
        }
        if (!yamlContent.contains("description:")) {
            return badRequest("Frontmatter must include 'description' field.");
        }
        int bodyStart = endIdx + 4;
        if (bodyStart < normalized.length() && normalized.substring(bodyStart).strip().isEmpty()) {
            return badRequest("SKILL.md must have content after the frontmatter (instructions, procedures, etc.).");
        }
        return null;
    }

    private Map<String, Object> skillPayload(SkillManager.SkillInfo skill, Set<String> disabled) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", skill.name());
        payload.put("description", skill.description() != null ? skill.description() : "");
        payload.put("category", skill.category() != null ? skill.category() : "");
        payload.put("enabled", !skill.disabled() && !disabled.contains(skill.name()));
        payload.put("usage", Math.max(0, skill.viewCount()) + Math.max(0, skill.manageCount()));
        payload.put("provenance", provenance(skill));
        return payload;
    }

    private Map<String, Object> editorSuccessPayload(String name, String content, String category, boolean created) {
        return editorSuccessPayload(name, content, category, created, skillPath(name));
    }

    private Map<String, Object> editorSuccessPayload(String name, String content, String category, boolean created, String path) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("message", created ? "Skill '" + name + "' created." : "Skill '" + name + "' updated (full rewrite).");
        payload.put("name", name);
        payload.put("path", path);
        payload.put("_change", Map.of("description", descriptionPreview(content)));
        if (created && category != null && !category.isBlank()) {
            payload.put("category", category);
        }
        if (created) {
            payload.put("hint", "To add reference files, templates, or scripts, use skill_manage.");
        }
        return payload;
    }

    private Map<String, Object> hubPreviewPayload(SkillsHubService.HubPreview preview) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", preview.name());
        payload.put("description", preview.description());
        payload.put("source", preview.source());
        payload.put("identifier", preview.identifier());
        payload.put("trust_level", preview.trustLevel().name().toLowerCase(Locale.ROOT));
        payload.put("repo", preview.repo());
        payload.put("tags", preview.tags());
        payload.put("skill_md", preview.skillMd());
        payload.put("files", preview.files());
        return payload;
    }

    private Map<String, Object> hubScanPayload(SkillsHubService.HubScan scan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", scan.name());
        payload.put("identifier", scan.identifier());
        payload.put("source", scan.source());
        payload.put("trust_level", scan.trustLevel().name().toLowerCase(Locale.ROOT));
        payload.put("verdict", scan.verdict());
        payload.put("summary", scan.summary());
        payload.put("policy", scan.policy());
        payload.put("policy_reason", scan.policyReason());
        payload.put("findings", scan.findings());
        payload.put("severity_counts", scan.severityCounts());
        payload.put("tier1", null);
        return payload;
    }

    private Map<String, Object> hubResult(SkillsHubService.RemoteSkillInfo info) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", info.name());
        result.put("description", info.description() != null ? info.description() : "");
        result.put("source", "github");
        result.put("identifier", info.name());
        result.put("trust_level", "community");
        result.put("repo", null);
        result.put("tags", List.of());
        return result;
    }

    private Set<String> disabledSkillNames() {
        Set<String> disabled = new LinkedHashSet<>();
        if (properties.getSkills() == null) {
            return disabled;
        }
        for (String name : properties.getSkills().getDisabled()) {
            if (name != null && !name.isBlank()) {
                disabled.add(name.trim());
            }
        }
        return disabled;
    }

    private static boolean isDefaultProfile(String profile) {
        return profile == null || profile.isBlank() || "default".equals(profile);
    }

    private static String skillDirectoryName(Path skillFile) {
        Path parent = skillFile != null ? skillFile.getParent() : null;
        return parent != null ? parent.getFileName().toString() : null;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringKeyMap(map);
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), yamlValue(value));
            }
        });
        return result;
    }

    private static Object yamlValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return toStringKeyMap(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SkillsDashboardController::yamlValue).toList();
        }
        return value;
    }

    private String provenance(SkillManager.SkillInfo skill) {
        String trust = skill.trustLevel();
        if ("BUILTIN".equalsIgnoreCase(trust)) {
            return "bundled";
        }
        if ("TRUSTED".equalsIgnoreCase(trust) || "COMMUNITY".equalsIgnoreCase(trust)) {
            return "hub";
        }
        return "agent";
    }

    private String skillPath(String name) {
        String workingDirectory = properties.getCore() != null ? properties.getCore().getWorkingDirectory() : null;
        Path root = workingDirectory != null && !workingDirectory.isBlank()
            ? Path.of(workingDirectory)
            : Path.of(System.getProperty("user.home", "."), ".java-agent");
        if (workingDirectory != null && workingDirectory.matches("^([A-Za-z]:[\\\\/]).*")) {
            // Windows-style configured root on any host OS: keep the stored
            // separator verbatim — normalizing would rewrite '' to '/' and
            // change the skill's canonical path identity.
            String sep = workingDirectory.contains("\\") ? "\\" : "/";
            String trimmed = workingDirectory.replaceAll("[\\\\/]+$", "");
            return trimmed + sep + "skills" + sep + name + sep + "SKILL.md";
        }
        return root.resolve("skills").resolve(name).resolve("SKILL.md").normalize().toString();
    }

    private static String descriptionPreview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String prefix = "description:";
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                String value = trimmed.substring(prefix.length()).trim();
                if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.length() > 120 ? value.substring(0, 120) : value;
            }
        }
        return "";
    }

    private static String skillSortCategory(SkillManager.SkillInfo skill) {
        return skill.category() != null ? skill.category() : "";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(Map.of("detail", detail));
    }

    private static ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body(Map.of("ok", false, "pid", currentPid(), "name", "java-agent", "detail", detail));
    }

    private static long currentPid() {
        try {
            return ProcessHandle.current().pid();
        } catch (UnsupportedOperationException e) {
            String jvmName = ManagementFactory.getRuntimeMXBean().getName();
            int at = jvmName.indexOf('@');
            if (at > 0) {
                try {
                    return Long.parseLong(jvmName.substring(0, at));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
    }

    private record SkillToggle(String name, Boolean enabled, String profile) {
    }

    private record SkillContentBody(String name, String content, String category, String profile) {
    }

    private record HubInstallBody(String identifier, String profile) {
    }

    private record HubUninstallBody(String name, String profile) {
    }

    private record ProfileSkillLookup(SkillManager.SkillInfo info, Path path, String error) {
        private static ProfileSkillLookup notFound() {
            return new ProfileSkillLookup(null, null, null);
        }

        private static ProfileSkillLookup error(String error) {
            return new ProfileSkillLookup(null, null, error);
        }
    }

    private record ProfileResolution(String profile, ResponseEntity<Map<String, Object>> error) {
        private static ProfileResolution ok(String profile) {
            return new ProfileResolution(profile, null);
        }

        private static ProfileResolution error(ResponseEntity<Map<String, Object>> error) {
            return new ProfileResolution(null, error);
        }
    }

    private record ResolvedProfile(String raw, String profile) {
    }
}
