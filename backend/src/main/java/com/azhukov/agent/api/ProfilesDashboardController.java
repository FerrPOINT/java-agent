package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.ProfileService;
import com.azhukov.agent.service.RuntimeConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/profiles")
@Tag(name = "Hermes-compatible", description = "Dashboard profile compatibility")
public class ProfilesDashboardController {

    private static final Pattern PROFILE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> RESERVED_PROFILE_NAMES = Set.of("hermes", "test", "tmp", "root", "sudo");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INVALID_PROFILE = "__invalid_profile__";
    private static final String UNKNOWN_PROFILE = "__unknown_profile__";
    private static final String SOURCE_LIST_SENTINEL = "__java_agent_no_source_filter__";
    private static final String MODEL_CONFIG_PRESENT_KEY = "browserModelConfigPresent";
    private static final String NO_PROJECT_ID = "__no_project__";
    private static final Pattern PR_URL = Pattern.compile("^https://github\\.com/[\\w.-]+/[\\w.-]+/pull/(\\d+)/?$");

    private final AgentProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final ProfileService profileService;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ProfileTerminalLauncher terminalLauncher;

    @Autowired
    public ProfilesDashboardController(
        AgentProperties properties,
        RuntimeConfigService runtimeConfigService,
        ProfileService profileService,
        SessionRepository sessionRepository,
        MessageRepository messageRepository
    ) {
        this(properties, runtimeConfigService, profileService, sessionRepository, messageRepository,
            ProfilesDashboardController::launchProfileTerminal);
    }

    ProfilesDashboardController(
        AgentProperties properties,
        RuntimeConfigService runtimeConfigService,
        ProfileService profileService,
        SessionRepository sessionRepository,
        MessageRepository messageRepository,
        ProfileTerminalLauncher terminalLauncher
    ) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.profileService = profileService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.terminalLauncher = terminalLauncher;
    }

    @GetMapping
    public Map<String, Object> profiles() {
        return Map.of("profiles", profileService.listProfileRows());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createProfile(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String explicitSource = bodyString(body, "clone_from");
        boolean cloneAll = bodyBoolean(body, "clone_all");
        boolean cloneFromDefault = bodyBoolean(body, "clone_from_default");
        String cloneFrom = explicitSource != null
            ? explicitSource
            : (cloneAll || cloneFromDefault ? "default" : null);
        boolean cloneConfig = explicitSource != null ? !cloneAll : cloneFromDefault;
        try {
            return ResponseEntity.ok(profileService.createProfile(new ProfileService.CreateProfileRequest(
                bodyString(body, "name"),
                cloneFrom,
                cloneConfig,
                cloneAll,
                bodyBoolean(body, "no_skills"),
                bodyString(body, "description"),
                bodyString(body, "provider"),
                bodyString(body, "model"),
                bodyString(body, "base_url"))));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @GetMapping("/active")
    public Map<String, Object> activeProfile() {
        return Map.of("active", profileService.activeProfileName(), "current", profileService.currentProfileName());
    }

    @PostMapping("/active")
    public ResponseEntity<Map<String, Object>> setActiveProfile(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        try {
            return ResponseEntity.ok(profileService.setActiveProfile(bodyString(body, "name")));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @PatchMapping("/{name}")
    public ResponseEntity<Map<String, Object>> renameProfile(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        try {
            return ResponseEntity.ok(profileService.renameProfile(name, bodyString(body, "new_name")));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteProfile(@PathVariable String name) {
        try {
            return ResponseEntity.ok(profileService.deleteProfile(name));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @GetMapping("/{name}/soul")
    public ResponseEntity<Map<String, Object>> profileSoul(@PathVariable String name) {
        try {
            return ResponseEntity.ok(profileService.readSoul(name));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @PutMapping("/{name}/soul")
    public ResponseEntity<Map<String, Object>> updateProfileSoul(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        Object content = body != null ? body.get("content") : null;
        if (!(content instanceof String string)) {
            return badRequest("content is required");
        }
        try {
            return ResponseEntity.ok(profileService.writeSoul(name, string));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @GetMapping("/{name}/setup-command")
    public ResponseEntity<Map<String, Object>> setupCommand(@PathVariable String name) {
        String profile = normalizeProfileName(name);
        if (!isValidProfileName(profile)) {
            return badRequest("Invalid profile name: " + name);
        }
        if (!knownProfile(profile)) {
            return notFound("Unknown profile: " + profile);
        }
        return ResponseEntity.ok(Map.of(
            "command", setupCommandForProfile(profile)));
    }

    @PostMapping("/{name}/open-terminal")
    public ResponseEntity<Map<String, Object>> openTerminal(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ResponseEntity<Map<String, Object>> validation = validateKnownProfile(name);
        if (validation != null) {
            return validation;
        }
        String profile = normalizeProfileName(name);
        String command = setupCommandForProfile(profile);
        try {
            terminalLauncher.launch(command);
            return ResponseEntity.ok(Map.of("ok", true, "command", command));
        } catch (IllegalStateException e) {
            return badRequest(messageOrType(e));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", messageOrType(e), "error", messageOrType(e)));
        }
    }

    @PutMapping("/{name}/description")
    public ResponseEntity<Map<String, Object>> updateProfileDescription(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        try {
            return ResponseEntity.ok(profileService.writeDescription(name, bodyString(body, "description")));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @PutMapping("/{name}/model")
    public ResponseEntity<Map<String, Object>> updateProfileModel(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ResponseEntity<Map<String, Object>> validation = validateKnownProfile(name);
        if (validation != null) {
            return validation;
        }
        String provider = bodyString(body, "provider");
        String model = bodyString(body, "model");
        if (provider == null || model == null) {
            return badRequest("provider and model are required");
        }
        try {
            return ResponseEntity.ok(profileService.writeModel(name, provider, model, bodyString(body, "base_url")));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @PostMapping("/{name}/describe-auto")
    public ResponseEntity<Map<String, Object>> describeProfileAuto(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ResponseEntity<Map<String, Object>> validation = validateKnownProfile(name);
        if (validation != null) {
            return validation;
        }
        return notImplemented("profile auto-description is not implemented in the Java port");
    }

    @PostMapping("/{name}/export")
    public ResponseEntity<Map<String, Object>> exportProfile(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        ResponseEntity<Map<String, Object>> validation = validateKnownProfile(name);
        if (validation != null) {
            return validation;
        }
        try {
            return ResponseEntity.ok(profileService.exportProfile(
                name,
                bodyString(body, "output"),
                bodyStringMap(body, "extra_files")));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importProfile(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        if (bodyString(body, "archive") == null) {
            return badRequest("archive path is required");
        }
        try {
            return ResponseEntity.ok(profileService.importProfile(
                bodyString(body, "archive"),
                bodyString(body, "name")));
        } catch (Exception e) {
            return profileError(e);
        }
    }

    @GetMapping("/{name}/desktop-overlay")
    public ResponseEntity<Map<String, Object>> desktopOverlay(@PathVariable String name) {
        Path overlay;
        try {
            String profile = profileService.normalizeProfileName(name);
            profileService.validateProfileName(profile);
            profileService.requireKnownProfile(profile);
            overlay = profileService.profilePath(profile).resolve("desktop.json");
        } catch (Exception e) {
            return profileError(e);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        if (!Files.isRegularFile(overlay)) {
            response.put("exists", false);
            response.put("desktop", null);
            return ResponseEntity.ok(response);
        }
        try {
            response.put("exists", true);
            response.put("desktop", JSON.readValue(overlay.toFile(), Object.class));
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "Could not read desktop.json"));
        }
    }

    @GetMapping("/sessions")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> profileSessions(
        @RequestParam(name = "limit", required = false) String limit,
        @RequestParam(name = "offset", required = false) String offset,
        @RequestParam(name = "profile", required = false) String profile,
        @RequestParam(name = "userId", required = false) String userId,
        @RequestParam(name = "user_id", required = false) String userIdSnake,
        @RequestParam(name = "min_messages", required = false) String minMessages,
        @RequestParam(name = "archived", required = false) String archived,
        @RequestParam(name = "order", required = false) String order,
        @RequestParam(name = "source", required = false) String source,
        @RequestParam(name = "sources", required = false) String sources,
        @RequestParam(name = "exclude_sources", required = false) String excludeSources,
        @RequestParam(name = "includeArchived", required = false) String includeArchived,
        @RequestParam(name = "include_archived", required = false) String includeArchivedSnake,
        @RequestParam(name = "includeHidden", required = false) String includeHidden,
        @RequestParam(name = "include_hidden", required = false) String includeHiddenSnake,
        @RequestParam(name = "includeChildren", required = false) String includeChildren,
        @RequestParam(name = "include_children", required = false) String includeChildrenSnake,
        @RequestParam(name = "includePinned", required = false) String includePinned,
        @RequestParam(name = "include_pinned", required = false) String includePinnedSnake,
        @RequestParam(name = "full", required = false) String full
    ) {
        int cappedLimit;
        int cappedOffset;
        int minMessageCount;
        try {
            cappedLimit = parseBoundedQueryInt(limit, "limit", 20, 0, 500);
            cappedOffset = parseBoundedQueryInt(offset, "offset", 0, 0, Integer.MAX_VALUE);
            minMessageCount = parseBoundedQueryInt(minMessages, "min_messages", 0, 0, Integer.MAX_VALUE);
        } catch (IllegalArgumentException e) {
            return unprocessable(e.getMessage());
        }

        ArchivedFilter archivedFilter;
        try {
            archivedFilter = parseArchivedFilter(archived, includeArchived, includeArchivedSnake);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        String orderBy;
        try {
            orderBy = parseProfileSessionOrder(order);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        String profileScope = resolveProfileSessionsScope(profile);
        if (INVALID_PROFILE.equals(profileScope)) {
            return badRequest("Invalid profile name: " + profile);
        }
        if (UNKNOWN_PROFILE.equals(profileScope)) {
            return notFound("Unknown profile: " + normalizeProfileName(profile));
        }

        String effectiveUserId = firstNonBlank(userId, userIdSnake, AgentProperties.DEFAULT_USER_ID);
        String sourceFilter = clean(source);
        boolean includeHiddenFlag = anyTruthy(includeHidden, includeHiddenSnake);
        boolean includeChildrenFlag = anyTruthy(includeChildren, includeChildrenSnake);
        boolean includePinnedFlag = !anyFalse(includePinned, includePinnedSnake);
        List<String> sourceList = splitCsv(sources);
        List<String> excludeSourceList = splitCsv(excludeSources);
        boolean sourcesEmpty = sourceList.isEmpty();
        boolean excludeSourcesEmpty = excludeSourceList.isEmpty();
        List<String> sourceParam = sourceListParam(sourceList);
        List<String> excludeSourceParam = sourceListParam(excludeSourceList);

        List<SessionEntity> entities = cappedLimit == 0
            ? List.of()
            : nullToEmpty("created".equals(orderBy)
                ? sessionRepository.findProfileDashboardPageOrderByCreated(
                    effectiveUserId,
                    profileScope,
                    cappedLimit,
                    cappedOffset,
                    archivedFilter.includeArchived(),
                    archivedFilter.archivedOnly(),
                    includeHiddenFlag,
                    sourceFilter,
                    sourcesEmpty,
                    sourceParam,
                    excludeSourcesEmpty,
                    excludeSourceParam,
                    minMessageCount,
                    includeChildrenFlag,
                    includePinnedFlag)
                : sessionRepository.findProfileDashboardPageOrderByRecent(
                    effectiveUserId,
                    profileScope,
                    cappedLimit,
                    cappedOffset,
                    archivedFilter.includeArchived(),
                    archivedFilter.archivedOnly(),
                    includeHiddenFlag,
                    sourceFilter,
                    sourcesEmpty,
                    sourceParam,
                    excludeSourcesEmpty,
                    excludeSourceParam,
                    minMessageCount,
                    includeChildrenFlag,
                    includePinnedFlag));
        List<Map<String, Object>> sessions = entities.stream()
            .map(this::toProfileSessionPayload)
            .toList();
        long total = sessionRepository.countProfileDashboardSessions(
            effectiveUserId,
            profileScope,
            archivedFilter.includeArchived(),
            archivedFilter.archivedOnly(),
            includeHiddenFlag,
            sourceFilter,
            sourcesEmpty,
            sourceParam,
            excludeSourcesEmpty,
            excludeSourceParam,
            minMessageCount,
            includeChildrenFlag,
            includePinnedFlag);
        Map<String, Object> profileTotals = profileTotals(
            effectiveUserId,
            profileScope,
            archivedFilter,
            includeHiddenFlag,
            sourceFilter,
            sourcesEmpty,
            sourceParam,
            excludeSourcesEmpty,
            excludeSourceParam,
            minMessageCount,
            includeChildrenFlag,
            includePinnedFlag,
            total);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", sessions);
        response.put("sessions", sessions);
        response.put("total", total);
        response.put("profile_totals", profileTotals);
        response.put("limit", cappedLimit);
        response.put("offset", cappedOffset);
        response.put("order", orderBy);
        response.put("profile", profileScope != null ? profileScope : "all");
        response.put("has_more", total > (long) cappedOffset + cappedLimit);
        response.put("profiles_truncated", profilesTruncated(profileTotals, cappedOffset + cappedLimit));
        response.put("errors", List.of());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/projects/tree")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> profileProjectsTree(
        @RequestParam(name = "preview_limit", required = false) String previewLimit,
        @RequestParam(name = "session_limit", required = false) String sessionLimit
    ) {
        int cappedPreviewLimit;
        int cappedSessionLimit;
        try {
            cappedPreviewLimit = parseBoundedQueryInt(previewLimit, "preview_limit", 3, 0, 500);
            cappedSessionLimit = parseBoundedQueryInt(sessionLimit, "session_limit", 2000, 0, 5000);
        } catch (IllegalArgumentException e) {
            return unprocessable(e.getMessage());
        }
        List<String> sourceParam = sourceListParam(List.of());
        List<SessionEntity> entities = cappedSessionLimit == 0
            ? List.of()
            : nullToEmpty(sessionRepository.findProfileDashboardPageOrderByRecent(
                AgentProperties.DEFAULT_USER_ID,
                null,
                cappedSessionLimit,
                0,
                false,
                false,
                false,
                null,
                true,
                sourceParam,
                true,
                sourceParam,
                0,
                false,
                true));
        List<Map<String, Object>> sessions = entities.stream()
            .map(this::toProfileSessionPayload)
            .toList();
        long total = sessionRepository.countProfileDashboardSessions(
            AgentProperties.DEFAULT_USER_ID,
            null,
            false,
            false,
            false,
            null,
            true,
            sourceParam,
            true,
            sourceParam,
            0,
            false,
            true);
        Map<String, Object> usage = profileUsageTotals(AgentProperties.DEFAULT_USER_ID, null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projects", List.of(homeProject(sessions, total, usage, cappedPreviewLimit)));
        response.put("active_id", null);
        response.put("scoped_session_ids", sessions.stream().map(row -> row.get("id")).toList());
        response.put("errors", List.of());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sessions/sidebar")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> sidebarSessions(
        @RequestParam(name = "recents_profile", required = false) String recentsProfile,
        @RequestParam(name = "recents_exclude", required = false) String recentsExclude,
        @RequestParam(name = "recents_limit", required = false) String recentsLimit,
        @RequestParam(name = "cron_limit", required = false) String cronLimit,
        @RequestParam(name = "messaging_limit", required = false) String messagingLimit,
        @RequestParam(name = "messaging_exclude", required = false) String messagingExclude,
        @RequestParam(name = "userId", required = false) String userId,
        @RequestParam(name = "user_id", required = false) String userIdSnake
    ) {
        int recentsCap;
        int cronCap;
        int messagingCap;
        try {
            recentsCap = parseClampedQueryInt(recentsLimit, "recents_limit", 20, 1, 500);
            cronCap = parseClampedQueryInt(cronLimit, "cron_limit", 50, 1, 500);
            messagingCap = parseClampedQueryInt(messagingLimit, "messaging_limit", 100, 1, 500);
        } catch (IllegalArgumentException e) {
            return unprocessable(e.getMessage());
        }
        String scope = resolveProfileSessionsScope(recentsProfile);
        if (INVALID_PROFILE.equals(scope)) {
            return badRequest("Invalid profile name: " + recentsProfile);
        }
        if (UNKNOWN_PROFILE.equals(scope)) {
            return ResponseEntity.ok(sidebarResponse(
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                0L));
        }

        String effectiveUserId = firstNonBlank(userId, userIdSnake, AgentProperties.DEFAULT_USER_ID);
        List<String> recentsExcludeList = splitCsv(recentsExclude);
        List<String> messagingExcludeList = splitCsv(messagingExclude);
        List<Map<String, Object>> recents = sidebarSlice(
            effectiveUserId,
            scope,
            recentsCap,
            null,
            recentsExcludeList);
        List<Map<String, Object>> cron = sidebarSlice(
            effectiveUserId,
            scope,
            cronCap,
            "cron",
            List.of());
        List<Map<String, Object>> messaging = sidebarSlice(
            effectiveUserId,
            scope,
            messagingCap,
            null,
            messagingExcludeList);
        long messagingTotal = dashboardSessionCount(
            effectiveUserId,
            scope,
            null,
            messagingExcludeList,
            1,
            true);
        return ResponseEntity.ok(sidebarResponse(
            recents,
            recentsTruncated(effectiveUserId, scope, recentsExcludeList, recentsCap),
            profileUsageTotals(effectiveUserId, scope),
            cron,
            messaging,
            messagingTotal));
    }

    @PostMapping("/sessions/pull-requests")
    public Map<String, Object> scanSessionPullRequests(
        @RequestBody(required = false) PullRequestScanBody body
    ) {
        List<String> ids = scanRequestIds(body != null && body.ids() != null ? body.ids() : List.of());
        if (ids.isEmpty()) {
            return Map.of("pull_requests", Map.of(), "scanned", List.of());
        }
        Map<String, Object> pullRequests = new LinkedHashMap<>();
        for (String id : ids) {
            UUID sessionId = parseUuidOrNull(id);
            if (sessionId == null) {
                continue;
            }
            for (MessageEntity message : nullToEmptyMessages(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId))) {
                Map<String, Object> pr = prFromToolOutput(message.getContent());
                if (pr != null) {
                    pullRequests.put(id, pr);
                }
            }
        }
        return Map.of(
            "pull_requests", pullRequests,
            "scanned", ids);
    }

    private List<Map<String, Object>> profileRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addProfileRow(rows, seen, "default");

        Path root = profilesRoot();
        if (Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.list(root)) {
                stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .map(ProfilesDashboardController::normalizeProfileName)
                    .filter(ProfilesDashboardController::isValidNamedProfileName)
                    .sorted(Comparator.naturalOrder())
                    .forEach(name -> addProfileRow(rows, seen, name));
            } catch (IOException ignored) {
                // Keep the dashboard usable even if one profile directory cannot be listed.
            }
        }

        addProfileRow(rows, seen, activeProfileName());
        return rows;
    }

    private void addProfileRow(List<Map<String, Object>> rows, Set<String> seen, String name) {
        String profile = normalizeProfileName(name);
        if (profile != null && seen.add(profile)) {
            rows.add(profileInfo(profile));
        }
    }

    private Map<String, Object> profileInfo(String name) {
        Path path = profilePath(name);
        RuntimeConfigService.RuntimeModelSelection selection = runtimeConfigService.getModelSelection();
        boolean current = activeProfileName().equals(name);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("display_name", "");
        row.put("path", path.toString());
        row.put("is_default", "default".equalsIgnoreCase(name));
        row.put("has_env", Files.isRegularFile(path.resolve(".env")));
        row.put("skill_count", countSkills(path));
        row.put("gateway_running", false);
        row.put("description", "");
        row.put("description_auto", false);
        row.put("distribution_name", null);
        row.put("distribution_version", null);
        row.put("distribution_source", null);
        row.put("has_alias", false);
        row.put("provider", current && selection != null && selection.provider() != null
            ? selection.provider()
            : current ? clean(properties.getModel().getProvider()) : null);
        row.put("model", current && selection != null && selection.model() != null
            ? selection.model()
            : current ? clean(properties.getModel().getModelName()) : null);
        return row;
    }

    private boolean knownProfile(String name) {
        String normalized = normalizeProfileName(name);
        return isValidProfileName(normalized) && ("default".equals(normalized)
            || activeProfileName().equals(normalized)
            || Files.isDirectory(profilePath(normalized)));
    }

    private ResponseEntity<Map<String, Object>> validateKnownProfile(Object rawName) {
        String name = rawName instanceof String string ? string : null;
        String profile = normalizeProfileName(name);
        if (!isValidProfileName(profile)) {
            return badRequest("Invalid profile name: " + rawName);
        }
        if (!knownProfile(profile)) {
            return notFound("Unknown profile: " + profile);
        }
        return null;
    }

    private String activeProfileName() {
        String name = properties.getProfile() != null ? normalizeProfileName(properties.getProfile().getName()) : null;
        return isValidProfileName(name) ? name : "default";
    }

    private Path profilePath(String name) {
        String normalized = normalizeProfileName(name);
        if (normalized == null || "default".equals(normalized)) {
            return hermesHome();
        }
        return profilesRoot().resolve(normalized).toAbsolutePath().normalize();
    }

    private Path profilesRoot() {
        String baseDir = properties.getProfile() != null ? clean(properties.getProfile().getBaseDir()) : null;
        return baseDir != null
            ? Path.of(baseDir).toAbsolutePath().normalize()
            : hermesHome().resolve("profiles").toAbsolutePath().normalize();
    }

    private Path soulPath(String profile) {
        String configured = properties.getCore() != null ? clean(properties.getCore().getSoulMdPath()) : null;
        if ("default".equals(profile) && configured != null) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return profilePath(profile).resolve("SOUL.md");
    }

    private static String setupCommandForProfile(String profile) {
        return "java -jar java-agent-backend.jar --agent.profile.name=" + profile;
    }

    private static void launchProfileTerminal(String command) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            startDetached(List.of("cmd.exe", "/c", "start", "", command));
            return;
        }
        if (os.contains("mac")) {
            String escaped = command.replace("\\", "\\\\").replace("\"", "\\\"");
            String applescript = "tell application \"Terminal\"\n"
                + "activate\n"
                + "do script \"" + escaped + "\"\n"
                + "end tell";
            startDetached(List.of("osascript", "-e", applescript));
            return;
        }

        List<List<String>> terminalCommands = List.of(
            List.of("x-terminal-emulator", "-e", "sh", "-lc", command),
            List.of("gnome-terminal", "--", "sh", "-lc", command),
            List.of("konsole", "-e", "sh", "-lc", command),
            List.of("xfce4-terminal", "-e", "sh -lc '" + command + "'"),
            List.of("mate-terminal", "-e", "sh -lc '" + command + "'"),
            List.of("lxterminal", "-e", "sh -lc '" + command + "'"),
            List.of("tilix", "-e", "sh", "-lc", command),
            List.of("alacritty", "-e", "sh", "-lc", command),
            List.of("kitty", "sh", "-lc", command),
            List.of("xterm", "-e", "sh", "-lc", command));
        for (List<String> args : terminalCommands) {
            if (isExecutableAvailable(args.get(0))) {
                startDetached(args);
                return;
            }
        }
        throw new IllegalStateException("No supported terminal emulator found");
    }

    private static void startDetached(List<String> args) throws IOException {
        new ProcessBuilder(args).start();
    }

    private static boolean isExecutableAvailable(String executable) {
        try {
            Process process = new ProcessBuilder("which", executable)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Map<String, Object> recentsSlice() {
        return Map.of(
            "sessions", List.of(),
            "profiles_truncated", Map.of(),
            "profiles_usage", Map.of());
    }

    private Map<String, Object> sidebarResponse(
        List<Map<String, Object>> recents,
        Map<String, Object> recentsTruncated,
        Map<String, Object> profileUsage,
        List<Map<String, Object>> cron,
        List<Map<String, Object>> messaging,
        long messagingTotal
    ) {
        Map<String, Object> recentsPayload = new LinkedHashMap<>();
        recentsPayload.put("sessions", recents);
        recentsPayload.put("profiles_truncated", recentsTruncated);
        recentsPayload.put("profiles_usage", profileUsage);

        Map<String, Object> cronPayload = new LinkedHashMap<>();
        cronPayload.put("sessions", cron);

        Map<String, Object> messagingPayload = new LinkedHashMap<>();
        messagingPayload.put("sessions", messaging);
        messagingPayload.put("total", messagingTotal);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("recents", recentsPayload);
        response.put("cron", cronPayload);
        response.put("messaging", messagingPayload);
        response.put("errors", List.of());
        return response;
    }

    private Map<String, Object> homeProject(
        List<Map<String, Object>> sessions,
        long totalSessions,
        Map<String, Object> profileUsage,
        int previewLimit
    ) {
        List<Map<String, Object>> preview = sessions.stream()
            .limit(previewLimit)
            .toList();

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", NO_PROJECT_ID);
        group.put("label", "Home");
        group.put("path", null);
        group.put("isHome", true);
        group.put("sessions", sessions);

        Map<String, Object> repo = new LinkedHashMap<>();
        repo.put("id", NO_PROJECT_ID);
        repo.put("label", "Home");
        repo.put("path", null);
        repo.put("groups", List.of(group));
        repo.put("sessionCount", totalSessions);

        Map<String, Object> project = new LinkedHashMap<>();
        project.put("id", NO_PROJECT_ID);
        project.put("label", "Home");
        project.put("path", null);
        project.put("isNoProject", true);
        project.put("repos", List.of(repo));
        project.put("sessionCount", totalSessions);
        project.put("totalTokens", usageTokens(profileUsage));
        project.put("totalCostUsd", usageCost(profileUsage));
        project.put("lastActive", sessions.stream()
            .map(row -> numericLong(row.get("last_active")))
            .max(Long::compareTo)
            .orElse(0L));
        project.put("previewSessions", preview);
        return project;
    }

    private List<Map<String, Object>> sidebarSlice(
        String userId,
        String profileScope,
        int limit,
        String source,
        List<String> excludeSources
    ) {
        List<String> sourceParam = sourceListParam(List.of());
        boolean excludeSourcesEmpty = excludeSources == null || excludeSources.isEmpty();
        List<String> excludeSourceParam = sourceListParam(excludeSources);
        List<SessionEntity> unpinned = nullToEmpty(sessionRepository.findProfileDashboardPageOrderByRecent(
            userId,
            profileScope,
            limit,
            0,
            false,
            false,
            false,
            source,
            true,
            sourceParam,
            excludeSourcesEmpty,
            excludeSourceParam,
            1,
            false,
            false));
        List<SessionEntity> pinned = nullToEmpty(sessionRepository.findProfileDashboardPinnedOrderByRecent(
            userId,
            profileScope,
            false,
            false,
            false,
            source,
            true,
            sourceParam,
            excludeSourcesEmpty,
            excludeSourceParam,
            1,
            false));

        Map<String, SessionEntity> byId = new LinkedHashMap<>();
        unpinned.forEach(entity -> byId.put(sessionKey(entity), entity));
        pinned.forEach(entity -> byId.putIfAbsent(sessionKey(entity), entity));
        List<SessionEntity> sorted = byId.values().stream()
            .sorted(ProfilesDashboardController::compareRecentDescending)
            .toList();

        List<SessionEntity> window = new ArrayList<>(sorted.subList(0, Math.min(limit, sorted.size())));
        if (sorted.size() > limit) {
            Set<String> seen = new HashSet<>();
            window.forEach(entity -> seen.add(sessionKey(entity)));
            sorted.stream()
                .filter(entity -> Boolean.TRUE.equals(entity.getPinned()))
                .filter(entity -> seen.add(sessionKey(entity)))
                .forEach(window::add);
        }
        return window.stream()
            .map(this::toProfileSessionPayload)
            .toList();
    }

    private Map<String, Object> recentsTruncated(
        String userId,
        String profileScope,
        List<String> recentsExclude,
        int cap
    ) {
        boolean excludeSourcesEmpty = recentsExclude == null || recentsExclude.isEmpty();
        Map<String, Object> totals = profileTotals(
            userId,
            profileScope,
            new ArchivedFilter(false, false),
            false,
            null,
            true,
            sourceListParam(List.of()),
            excludeSourcesEmpty,
            sourceListParam(recentsExclude),
            1,
            false,
            false,
            0L);
        Map<String, Object> truncated = new LinkedHashMap<>();
        totals.forEach((profile, total) -> truncated.put(profile, numericLong(total) >= cap));
        return truncated;
    }

    private Map<String, Object> profileUsageTotals(String userId, String profileScope) {
        Map<String, Object> usage = new LinkedHashMap<>();
        List<Object[]> rows = sessionRepository.countProfileDashboardUsageByProfile(userId, profileScope);
        if (rows != null) {
            for (Object[] row : rows) {
                if (row == null || row.length < 3) {
                    continue;
                }
                String profile = row[0] instanceof String value && !value.isBlank() ? value : "default";
                Map<String, Object> totals = new LinkedHashMap<>();
                totals.put("cost_usd", numericDouble(row[2]));
                totals.put("tokens", numericLong(row[1]));
                usage.put(profile, totals);
            }
        }
        if (profileScope != null) {
            usage.putIfAbsent(profileScope, emptyUsageTotals());
        } else if (usage.isEmpty()) {
            usage.put("default", emptyUsageTotals());
        } else {
            profileRows().forEach(row -> {
                Object name = row.get("name");
                if (name instanceof String profile && !profile.isBlank()) {
                    usage.putIfAbsent(profile, emptyUsageTotals());
                }
            });
        }
        return usage;
    }

    private long dashboardSessionCount(
        String userId,
        String profileScope,
        String source,
        List<String> excludeSources,
        int minMessageCount,
        boolean includePinned
    ) {
        boolean excludeSourcesEmpty = excludeSources == null || excludeSources.isEmpty();
        return sessionRepository.countProfileDashboardSessions(
            userId,
            profileScope,
            false,
            false,
            false,
            source,
            true,
            sourceListParam(List.of()),
            excludeSourcesEmpty,
            sourceListParam(excludeSources),
            minMessageCount,
            false,
            includePinned);
    }

    private String resolveProfileSessionsScope(String rawProfile) {
        String requested = clean(rawProfile);
        if (requested == null || "all".equalsIgnoreCase(requested)) {
            return null;
        }
        String profile = normalizeProfileName(requested);
        if (!isValidProfileName(profile)) {
            return INVALID_PROFILE;
        }
        if (!knownProfile(profile)) {
            return UNKNOWN_PROFILE;
        }
        return profile;
    }

    private Map<String, Object> profileTotals(
        String userId,
        String profileScope,
        ArchivedFilter archivedFilter,
        boolean includeHidden,
        String source,
        boolean sourcesEmpty,
        List<String> sources,
        boolean excludeSourcesEmpty,
        List<String> excludeSources,
        int minMessageCount,
        boolean includeChildren,
        boolean includePinned,
        long total
    ) {
        Map<String, Object> totals = new LinkedHashMap<>();
        List<Object[]> rows = sessionRepository.countProfileDashboardSessionsByProfile(
            userId,
            profileScope,
            archivedFilter.includeArchived(),
            archivedFilter.archivedOnly(),
            includeHidden,
            source,
            sourcesEmpty,
            sources,
            excludeSourcesEmpty,
            excludeSources,
            minMessageCount,
            includeChildren,
            includePinned);
        if (rows != null) {
            for (Object[] row : rows) {
                if (row == null || row.length < 2) {
                    continue;
                }
                String name = row[0] instanceof String value && !value.isBlank() ? value : "default";
                totals.put(name, numericLong(row[1]));
            }
        }
        if (profileScope != null) {
            totals.putIfAbsent(profileScope, total);
        } else if (totals.isEmpty()) {
            totals.put("default", total);
        } else {
            profileRows().forEach(row -> {
                Object name = row.get("name");
                if (name instanceof String profileName && !profileName.isBlank()) {
                    totals.putIfAbsent(profileName, 0L);
                }
            });
        }
        return totals;
    }

    private static Map<String, Object> profilesTruncated(Map<String, Object> totals, int pageEnd) {
        Map<String, Object> truncated = new LinkedHashMap<>();
        totals.forEach((profile, total) -> truncated.put(profile, numericLong(total) > pageEnd));
        return truncated;
    }

    private Map<String, Object> toProfileSessionPayload(SessionEntity entity) {
        Map<String, Object> response = new LinkedHashMap<>();
        String profile = entityProfile(entity);
        Instant startedAt = entity.getCreatedAt();
        Instant lastActive = entity.getLastActive() != null ? entity.getLastActive() : entity.getUpdatedAt();
        Instant endedAt = endedAt(entity);
        response.put("id", entity.getId() != null ? entity.getId().toString() : null);
        response.put("source", clean(entity.getSource()) != null ? entity.getSource().trim() : "api_server");
        response.put("user_id", entity.getUserId());
        response.put("model", clean(entity.getModelName()));
        response.put("title", entity.getTitle());
        response.put("started_at", startedAt != null ? epochSeconds(startedAt) : 0L);
        response.put("ended_at", endedAt != null ? epochSeconds(endedAt) : null);
        response.put("last_active", lastActive != null ? epochSeconds(lastActive) : 0L);
        response.put("is_active", sessionIsActive(lastActive, endedAt));
        response.put("message_count", entity.getMessageCount() != null ? entity.getMessageCount() : 0);
        response.put("tool_call_count", 0);
        response.put("input_tokens", 0);
        response.put("output_tokens", 0);
        response.put("preview", entity.getPreview());
        response.put("pinned", Boolean.TRUE.equals(entity.getPinned()));
        response.put("archived", Boolean.TRUE.equals(entity.getArchived()));
        response.put("hidden", Boolean.TRUE.equals(entity.getHidden()));
        if (entity.getParentSessionId() != null) {
            response.put("parent_session_id", entity.getParentSessionId().toString());
        }
        if (entity.getEndReason() != null && !entity.getEndReason().isBlank()) {
            response.put("end_reason", entity.getEndReason());
        }
        response.put("profile", profile);
        response.put("is_default_profile", "default".equals(profile));
        response.put("has_system_prompt", clean(entity.getSystemPrompt()) != null);
        response.put("has_model_config", Boolean.TRUE.equals(OpenAiRequestBooleans.coerceOptional(
            entity.getCliStateValue(MODEL_CONFIG_PRESENT_KEY))));
        return response;
    }

    private static String entityProfile(SessionEntity entity) {
        String profile = entity != null ? clean(entity.getProfile()) : null;
        return profile != null ? profile : "default";
    }

    private static Instant endedAt(SessionEntity entity) {
        if (!sessionIsEnded(entity)) {
            return null;
        }
        return firstInstant(entity.getUpdatedAt(), entity.getLastActive(), entity.getCreatedAt());
    }

    private static boolean sessionIsEnded(SessionEntity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.getEndReason() != null && !entity.getEndReason().isBlank()) {
            return true;
        }
        String status = entity.getSessionStatus();
        return status != null && !status.isBlank() && !"active".equalsIgnoreCase(status);
    }

    private static boolean sessionIsActive(Instant lastActive, Instant endedAt) {
        return endedAt == null && lastActive != null && lastActive.isAfter(Instant.now().minusSeconds(300));
    }

    private static Instant firstInstant(Instant first, Instant second, Instant third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third;
    }

    private static long epochSeconds(Instant instant) {
        return instant.getEpochSecond();
    }

    private static List<SessionEntity> nullToEmpty(List<SessionEntity> entities) {
        return entities == null ? List.of() : entities;
    }

    private static List<MessageEntity> nullToEmptyMessages(List<MessageEntity> entities) {
        return entities == null ? List.of() : entities;
    }

    private static List<String> scanRequestIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (String rawId : rawIds) {
            String id = clean(rawId);
            if (id != null) {
                seen.add(id);
            }
            if (seen.size() >= 2000) {
                break;
            }
        }
        return List.copyOf(seen);
    }

    private static UUID parseUuidOrNull(String rawId) {
        try {
            return UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> prFromToolOutput(String content) {
        String output = toolOutputString(content);
        if (output == null) {
            return null;
        }
        java.util.regex.Matcher matcher = PR_URL.matcher(output.strip());
        if (!matcher.matches()) {
            return null;
        }
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("number", Integer.parseInt(matcher.group(1)));
        pr.put("url", matcher.group(0));
        return pr;
    }

    private static String toolOutputString(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            Object parsed = JSON.readValue(content, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Object output = map.get("output");
                return output instanceof String string ? string : null;
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static List<String> splitCsv(String raw) {
        String cleaned = clean(raw);
        if (cleaned == null) {
            return List.of();
        }
        return Stream.of(cleaned.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private static List<String> sourceListParam(List<String> values) {
        return values == null || values.isEmpty() ? List.of(SOURCE_LIST_SENTINEL) : values;
    }

    private static long numericLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static double numericDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0.0d;
            }
        }
        return 0.0d;
    }

    private static long usageTokens(Map<String, Object> profileUsage) {
        long total = 0L;
        for (Object value : profileUsage.values()) {
            if (value instanceof Map<?, ?> usage) {
                total += numericLong(usage.get("tokens"));
            }
        }
        return total;
    }

    private static double usageCost(Map<String, Object> profileUsage) {
        double total = 0.0d;
        for (Object value : profileUsage.values()) {
            if (value instanceof Map<?, ?> usage) {
                total += numericDouble(usage.get("cost_usd"));
            }
        }
        return total;
    }

    private static Map<String, Object> emptyUsageTotals() {
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("cost_usd", 0.0d);
        totals.put("tokens", 0L);
        return totals;
    }

    private static String sessionKey(SessionEntity entity) {
        return entity != null && entity.getId() != null ? entity.getId().toString() : "";
    }

    private static int compareRecentDescending(SessionEntity left, SessionEntity right) {
        int byTime = recentInstant(right).compareTo(recentInstant(left));
        if (byTime != 0) {
            return byTime;
        }
        return sessionKey(left).compareTo(sessionKey(right));
    }

    private static Instant recentInstant(SessionEntity entity) {
        Instant instant = entity != null
            ? firstInstant(entity.getLastActive(), entity.getUpdatedAt(), entity.getCreatedAt())
            : null;
        return instant != null ? instant : Instant.EPOCH;
    }

    private static int parseClampedQueryInt(String raw, String field, int defaultValue, int minValue, int maxValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.min(Math.max(parsed, minValue), maxValue);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean anyTruthy(String... values) {
        for (String value : values) {
            if (Boolean.TRUE.equals(OpenAiRequestBooleans.coerceOptional(value))) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyFalse(String... values) {
        for (String value : values) {
            if (Boolean.FALSE.equals(OpenAiRequestBooleans.coerceOptional(value))) {
                return true;
            }
        }
        return false;
    }

    private static ArchivedFilter parseArchivedFilter(
        String archived,
        String includeArchived,
        String includeArchivedSnake
    ) {
        String value = clean(archived);
        if (value == null) {
            return anyTruthy(includeArchived, includeArchivedSnake)
                ? new ArchivedFilter(true, false)
                : new ArchivedFilter(false, false);
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "exclude" -> new ArchivedFilter(false, false);
            case "include" -> new ArchivedFilter(true, false);
            case "only" -> new ArchivedFilter(true, true);
            default -> throw new IllegalArgumentException("archived must be one of: exclude, only, include");
        };
    }

    private static String parseProfileSessionOrder(String order) {
        String value = clean(order);
        if (value == null) {
            return "recent";
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "created", "recent" -> value.toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("order must be one of: created, recent");
        };
    }

    private static ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> unprocessable(String detail) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> profileError(Exception e) {
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (e instanceof FileNotFoundException) {
            return notFound(detail);
        }
        if (e instanceof FileAlreadyExistsException || e instanceof IllegalArgumentException) {
            return badRequest(detail);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("detail", detail, "error", detail));
    }

    private static String messageOrType(Exception e) {
        return e.getMessage() != null && !e.getMessage().isBlank()
            ? e.getMessage()
            : e.getClass().getSimpleName();
    }

    private static int parseBoundedQueryInt(String raw, String field, int defaultValue, int minValue, int maxValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < minValue || parsed > maxValue) {
                throw new IllegalArgumentException(field + " must be between " + minValue + " and " + maxValue);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static Path hermesHome() {
        String env = System.getenv("HERMES_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".hermes").toAbsolutePath().normalize();
    }

    private int countSkills(Path profileDir) {
        Path skillsDir = profileDir.resolve("skills");
        if (!Files.isDirectory(skillsDir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(skillsDir)) {
            return (int) stream
                .filter(path -> Files.isDirectory(path) || path.getFileName().toString().endsWith(".md"))
                .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean isValidProfileName(String value) {
        return "default".equals(value) || isValidNamedProfileName(value);
    }

    private static boolean isValidNamedProfileName(String value) {
        return value != null && PROFILE_ID.matcher(value).matches() && !RESERVED_PROFILE_NAMES.contains(value);
    }

    private static String normalizeProfileName(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        if ("default".equalsIgnoreCase(cleaned)) {
            return "default";
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String bodyString(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        return value instanceof String string ? clean(string) : null;
    }

    private static boolean bodyBoolean(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return "true".equalsIgnoreCase(string.trim()) || "1".equals(string.trim());
        }
        return false;
    }

    private static Map<String, String> bodyStringMap(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((rawKey, rawValue) -> {
            if (rawKey != null && rawValue instanceof String stringValue) {
                result.put(String.valueOf(rawKey), stringValue);
            }
        });
        return result;
    }

    @FunctionalInterface
    interface ProfileTerminalLauncher {
        void launch(String command) throws Exception;
    }

    private record PullRequestScanBody(List<String> ids) {
    }

    private record ArchivedFilter(boolean includeArchived, boolean archivedOnly) {
    }
}
