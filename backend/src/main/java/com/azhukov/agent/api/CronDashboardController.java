package com.azhukov.agent.api;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import com.azhukov.agent.service.CronBlueprintService;
import com.azhukov.agent.service.CronJobService;
import com.azhukov.agent.service.CronSuggestionService;
import com.azhukov.agent.service.ProfileService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping({"/api/cron", "/p/{profile}/api/cron"})
@RequiredArgsConstructor
@Tag(name = "Hermes-compatible", description = "Dashboard cron compatibility")
public class CronDashboardController {

    private static final Pattern HERMES_JOB_ID_PATTERN = Pattern.compile("[a-f0-9]{12}");

    private final HermesCronJobsController jobsController;
    private final CronJobService cronJobService;
    private final CronBlueprintService cronBlueprintService;
    private final ProfileService profileService;

    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", defaultValue = "all") String queryProfile
    ) {
        String profile = resolveOptionalProfileScope(pathProfile, queryProfile);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) jobsController.list(profile, "true")
            .getOrDefault("jobs", List.of());
        return jobs;
    }

    @PostMapping("/jobs")
    public Map<String, Object> createJob(
        @RequestBody DashboardCronJobCreateBody body,
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        String profile = resolveMutationProfileScope(pathProfile, queryProfile);
        if (body == null) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Invalid JSON in request body");
        }
        String schedule = requiredText(body.schedule(), "Schedule is required");
        String prompt = optionalCreateText(body.prompt());
        String skills = csv(cronStringList(body.skills()));
        String script = normalizeDashboardCronScript(body.script(), false);
        boolean noAgent = Boolean.TRUE.equals(body.no_agent());
        String monitor = monitorSource(body.monitor(), body.monitor_script(), body.monitor_url());
        UUID attachedSessionId = attachedSessionId(body.attach_to_session(), body.attached_session_id());
        validateDashboardCronEffectiveJob(prompt, skills, script, noAgent);
        String name = dashboardJobName(body.name(), prompt, skills, script, noAgent);
        String contextFrom = contextFromCsv(body.context_from());
        validateDashboardCronContextFrom(contextFrom, profile);
        boolean hasHermesExtras = monitor != null || body.continuity() != null || body.attach_to_session() != null;
        CronJobEntity entity;
        if (hasHermesExtras && profile != null) {
            entity = cronJobService.createInProfile(
                profile,
                name,
                schedule,
                prompt,
                optionalText(body.deliver(), "local"),
                skills,
                contextFrom,
                null,
                script,
                noAgent,
                csv(cronStringList(body.enabled_toolsets())),
                optionalText(body.workdir(), null),
                optionalText(body.provider(), null),
                optionalText(body.model(), null),
                optionalText(body.base_url(), null, true),
                monitor,
                Boolean.TRUE.equals(body.continuity()),
                attachedSessionId);
        } else if (hasHermesExtras) {
            entity = cronJobService.create(
                name,
                schedule,
                prompt,
                optionalText(body.deliver(), "local"),
                skills,
                contextFrom,
                null,
                script,
                noAgent,
                csv(cronStringList(body.enabled_toolsets())),
                optionalText(body.workdir(), null),
                optionalText(body.provider(), null),
                optionalText(body.model(), null),
                optionalText(body.base_url(), null, true),
                monitor,
                Boolean.TRUE.equals(body.continuity()),
                attachedSessionId);
        } else if (profile != null) {
            entity = cronJobService.createInProfile(
                profile,
                name,
                schedule,
                prompt,
                optionalText(body.deliver(), "local"),
                skills,
                contextFrom,
                null,
                script,
                noAgent,
                csv(cronStringList(body.enabled_toolsets())),
                optionalText(body.workdir(), null),
                optionalText(body.provider(), null),
                optionalText(body.model(), null),
                optionalText(body.base_url(), null, true));
        } else {
            entity = cronJobService.create(
                name,
                schedule,
                prompt,
                optionalText(body.deliver(), "local"),
                skills,
                contextFrom,
                null,
                script,
                noAgent,
                csv(cronStringList(body.enabled_toolsets())),
                optionalText(body.workdir(), null),
                optionalText(body.provider(), null),
                optionalText(body.model(), null),
                optionalText(body.base_url(), null, true));
        }
        return HermesCronJobsController.hermesJob(entity);
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> getJob(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String jobId,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        return unwrapJob(jobsController.get(resolveOptionalProfileScope(pathProfile, queryProfile), jobId));
    }

    @GetMapping("/jobs/{jobId}/runs")
    public Map<String, Object> listJobRuns(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String jobId,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestParam(name = "limit", defaultValue = "20") String limit
    ) {
        if (findJob(jobId, resolveOptionalProfileScope(pathProfile, queryProfile)).isEmpty()) {
            throw new AgentException(HttpStatus.NOT_FOUND, "Job not found");
        }
        return Map.of("runs", List.of(), "limit", parseRunsLimit(limit));
    }

    @PutMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> updateJob(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String jobId,
        @RequestBody(required = false) CronJobUpdateBody body,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        String requestedProfile = resolveOptionalProfileScope(pathProfile, queryProfile);
        Map<String, Object> updates = body != null && body.updates() != null ? body.updates() : Map.of();
        Optional<CronJobEntity> existing = findJob(jobId, requestedProfile);
        if (existing.isEmpty()) {
            return notFound();
        }
        String profile = CronJobService.jobProfile(existing.get());
        Map<String, Object> normalized = normalizeDashboardCronUpdates(updates);
        if (normalized.containsKey("context_from")) {
            validateDashboardCronContextFrom((String) normalized.get("context_from"), profile);
        }
        if (executionFieldsChanged(normalized)) {
            CronJobEntity current = existing.get();
            validateDashboardCronEffectiveJob(
                stringUpdate(normalized, "prompt", current.getPrompt()),
                stringUpdate(normalized, "skills", current.getSkills()),
                stringUpdate(normalized, "script", current.getScript()),
                booleanUpdate(normalized, "no_agent", current.isNoAgent()));
        }
        boolean hasHermesExtras = containsAny(normalized,
            "monitor", "monitor_script", "monitor_url", "continuity", "attach_to_session");
        CronJobEntity entity = hasHermesExtras
            ? cronJobService.update(
                existing.get().getId(),
                stringUpdate(normalized, "name"),
                stringUpdate(normalized, "schedule"),
                stringUpdate(normalized, "prompt"),
                stringUpdate(normalized, "deliver"),
                booleanUpdate(normalized, "enabled"),
                stringUpdate(normalized, "skills"),
                stringUpdate(normalized, "context_from"),
                null,
                stringUpdate(normalized, "script"),
                booleanUpdate(normalized, "no_agent"),
                stringUpdate(normalized, "enabled_toolsets"),
                stringUpdate(normalized, "workdir"),
                stringUpdate(normalized, "provider"),
                stringUpdate(normalized, "model"),
                stringUpdate(normalized, "base_url"),
                monitorSource(normalized),
                booleanUpdate(normalized, "continuity"),
                booleanUpdate(normalized, "attach_to_session"),
                attachedSessionIdForUpdate(normalized))
            : cronJobService.update(
                existing.get().getId(),
                stringUpdate(normalized, "name"),
                stringUpdate(normalized, "schedule"),
                stringUpdate(normalized, "prompt"),
                stringUpdate(normalized, "deliver"),
                booleanUpdate(normalized, "enabled"),
                stringUpdate(normalized, "skills"),
                stringUpdate(normalized, "context_from"),
                null,
                stringUpdate(normalized, "script"),
                booleanUpdate(normalized, "no_agent"),
                stringUpdate(normalized, "enabled_toolsets"),
                stringUpdate(normalized, "workdir"),
                stringUpdate(normalized, "provider"),
                stringUpdate(normalized, "model"),
                stringUpdate(normalized, "base_url"));
        return ResponseEntity.ok(HermesCronJobsController.hermesJob(entity));
    }

    @PostMapping("/jobs/{jobId}/pause")
    public ResponseEntity<Map<String, Object>> pauseJob(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String jobId,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        return unwrapJob(jobsController.pause(resolveOptionalProfileScope(pathProfile, queryProfile), jobId));
    }

    @PostMapping("/jobs/{jobId}/resume")
    public ResponseEntity<Map<String, Object>> resumeJob(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String jobId,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        return unwrapJob(jobsController.resume(resolveOptionalProfileScope(pathProfile, queryProfile), jobId));
    }

    @PostMapping("/jobs/{jobId}/trigger")
    public ResponseEntity<Map<String, Object>> triggerJob(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String jobId,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        return unwrapJob(jobsController.run(resolveOptionalProfileScope(pathProfile, queryProfile), jobId));
    }

    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> deleteJob(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @PathVariable String jobId,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        return jobsController.delete(resolveOptionalProfileScope(pathProfile, queryProfile), jobId);
    }

    @GetMapping("/delivery-targets")
    public Map<String, Object> deliveryTargets() {
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("id", "local");
        local.put("name", "Local (save only)");
        local.put("home_target_set", true);
        local.put("home_env_var", null);
        return Map.of("targets", List.of(local));
    }

    @PostMapping("/fire")
    public ResponseEntity<Map<String, Object>> cronFireWebhook(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String token = bearerToken(authorization);
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid fire token"));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid fire token"));
    }

    @GetMapping("/blueprints")
    public Map<String, Object> listBlueprints() {
        return Map.of("blueprints", cronBlueprintService.listBlueprints().stream()
            .map(this::blueprintPayload)
            .toList());
    }

    @PostMapping("/blueprints/instantiate")
    public ResponseEntity<Map<String, Object>> instantiateBlueprint(
        @RequestBody(required = false) AutomationBlueprintInstantiate body,
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile
    ) {
        String profile = resolveMutationProfileScope(pathProfile, queryProfile);
        if (body == null || body.blueprint() == null || body.blueprint().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "blueprint is required"));
        }
        try {
            CronBlueprintService.AutomationBlueprint blueprint = cronBlueprintService.getBlueprint(body.blueprint())
                .orElse(null);
            if (blueprint == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "Unknown blueprint: " + body.blueprint()));
            }
            CronSuggestionService.JobSpec spec = cronBlueprintService.fillBlueprintWithTime(
                blueprint, body.values() != null ? body.values() : Map.of());
            CronJobEntity entity = profile == null
                ? cronJobService.create(
                    spec.name(), spec.schedule(), spec.prompt(), spec.deliverTo(), spec.skills())
                : cronJobService.createInProfile(
                    profile,
                    spec.name(), spec.schedule(), spec.prompt(), spec.deliverTo(), spec.skills());
            return ResponseEntity.ok(HermesCronJobsController.hermesJob(entity));
        } catch (CronBlueprintService.BlueprintFillException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
        }
    }

    private Map<String, Object> blueprintPayload(CronBlueprintService.AutomationBlueprint blueprint) {
        Map<String, Object> payload = new LinkedHashMap<>(cronBlueprintService.formSchema(blueprint));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) payload.remove("fields");
        payload.put("fields", fields != null ? fields : List.of());
        payload.put("command", "cron blueprint " + blueprint.key());
        payload.put("appUrl", "");
        return payload;
    }

    private static Map<String, Object> unwrapJob(Map<String, Object> envelope) {
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) envelope.get("job");
        return job != null ? job : envelope;
    }

    private static ResponseEntity<Map<String, Object>> unwrapJob(ResponseEntity<Map<String, Object>> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("job")) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode()).body(unwrapJob(body));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return "";
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    private String resolveOptionalProfileScope(String pathProfile, String queryProfile) {
        if (pathProfile != null) {
            return resolveProfileScope(pathProfile);
        }
        String query = optionalText(queryProfile, null);
        if (query == null || "all".equalsIgnoreCase(query)) {
            return null;
        }
        return resolveProfileScope(query);
    }

    private String resolveMutationProfileScope(String pathProfile, String queryProfile) {
        if (pathProfile != null) {
            return resolveProfileScope(pathProfile);
        }
        String query = optionalText(queryProfile, null);
        if (query == null || "all".equalsIgnoreCase(query)) {
            return null;
        }
        return resolveProfileScope(query);
    }

    private String resolveProfileScope(String rawProfile) {
        try {
            String profile = profileService.normalizeProfileName(rawProfile);
            profileService.validateProfileName(profile);
            if (!profileService.knownProfile(profile)) {
                throw new AgentException(HttpStatus.NOT_FOUND, "Unknown profile: " + profile);
            }
            return profile;
        } catch (IllegalArgumentException e) {
            throw new AgentException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private Optional<CronJobEntity> findJob(String jobId, String profile) {
        try {
            UUID uuid = UUID.fromString(jobId);
            return profile == null ? cronJobService.findById(uuid) : cronJobService.findById(uuid, profile);
        } catch (RuntimeException ignored) {
            // Dashboard routes also accept Hermes' compact 12-hex job ids.
        }
        if (!HERMES_JOB_ID_PATTERN.matcher(jobId).matches()) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Invalid job ID format");
        }
        List<CronJobEntity> candidates = profile == null
            ? cronJobService.list(true)
            : cronJobService.listForProfile(profile, true);
        List<CronJobEntity> matches = candidates.stream()
            .filter(entity -> jobId.equals(compactJobId(entity.getId())))
            .toList();
        if (matches.size() > 1) {
            throw new AgentException(HttpStatus.CONFLICT, "Ambiguous job ID");
        }
        return matches.stream().findFirst();
    }

    private static String compactJobId(UUID id) {
        if (id == null) {
            return null;
        }
        return id.toString().replace("-", "").substring(0, 12);
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found"));
    }

    private static String requiredText(Object value, String error) {
        String text = optionalText(value, null);
        if (text == null) {
            throw new AgentException(HttpStatus.BAD_REQUEST, error);
        }
        return text;
    }

    private static String optionalCreateText(Object value) {
        String text = optionalText(value, null);
        return text == null ? "" : text;
    }

    private static String optionalText(Object value, String fallback) {
        return optionalText(value, fallback, false);
    }

    private static String optionalText(Object value, String fallback, boolean stripTrailingSlash) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (stripTrailingSlash) {
            text = text.replaceAll("/+$", "");
        }
        return text.isBlank() ? fallback : text;
    }

    private static List<String> cronStringList(Object value) {
        if (value == null) {
            return null;
        }
        List<String> raw = new ArrayList<>();
        if (value instanceof String string) {
            raw.addAll(List.of(string.split("[\\n,]")));
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                raw.add(String.valueOf(item));
            }
        } else {
            return null;
        }
        List<String> normalized = raw.stream()
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .toList();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String csv(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", values);
    }

    private String contextFromCsv(Object value) {
        String refs = csv(cronStringList(value));
        return refs;
    }

    private void validateDashboardCronContextFrom(String refs, String profile) {
        if (refs == null || refs.isBlank()) {
            return;
        }
        for (String ref : refs.split(",")) {
            String trimmed = ref.trim();
            if (trimmed.isEmpty() || "self".equalsIgnoreCase(trimmed)) {
                continue;
            }
            if (!contextJobExists(trimmed, profile)) {
                String profileLabel = profile == null ? "default" : profile;
                throw new AgentException(HttpStatus.BAD_REQUEST,
                    "context_from job '" + trimmed + "' not found in profile '" + profileLabel + "'");
            }
        }
    }

    private boolean contextJobExists(String ref, String profile) {
        try {
            UUID id = UUID.fromString(ref);
            if ((profile == null ? cronJobService.findById(id) : cronJobService.findById(id, profile)).isPresent()) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // Keep context_from validation aligned with Hermes: invalid refs
            // are reported as missing context jobs, not as malformed path ids.
        }
        if (!HERMES_JOB_ID_PATTERN.matcher(ref).matches()) {
            return false;
        }
        List<CronJobEntity> candidates = profile == null
            ? cronJobService.list(true)
            : cronJobService.listForProfile(profile, true);
        return candidates.stream()
            .anyMatch(entity -> ref.equals(compactJobId(entity.getId())));
    }

    private static Map<String, Object> normalizeDashboardCronUpdates(Map<String, Object> updates) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "name", "schedule", "prompt" -> normalized.put(key, updateText(value));
                case "deliver" -> normalized.put(key, optionalText(value, "local"));
                case "model", "provider", "base_url" -> {
                    if (value != null) {
                        normalized.put(key, updateText(value, "base_url".equals(key)));
                    }
                }
                case "workdir" -> normalized.put(key, updateText(value));
                case "script" -> normalized.put(key, normalizeDashboardCronScript(value, true));
                case "monitor", "monitor_script", "monitor_url", "attached_session_id" -> normalized.put(key, updateText(value));
                case "context_from", "enabled_toolsets" -> normalized.put(key, updateCsv(value));
                case "skill" -> normalized.put("skills", updateCsv(value));
                case "skills" -> normalized.put("skills", updateCsv(value));
                case "enabled", "no_agent", "continuity", "attach_to_session" -> normalized.put(key, updateBoolean(value, key));
                default -> {
                    // Hermes dashboard passes unknown update keys through to cron.jobs,
                    // but Java stores a fixed schema; ignore fields this port cannot persist.
                }
            }
        }
        return normalized;
    }

    private static String updateText(Object value) {
        return updateText(value, false);
    }

    private static String updateText(Object value, boolean stripTrailingSlash) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (stripTrailingSlash) {
            text = text.replaceAll("/+$", "");
        }
        return text;
    }

    private static String updateCsv(Object value) {
        String csv = csv(cronStringList(value));
        return csv == null ? "" : csv;
    }

    private static String monitorSource(Object monitor, Object monitorScript, Object monitorUrl) {
        if (monitor != null) {
            return String.valueOf(monitor).trim();
        }
        boolean hasScript = monitorScript != null;
        boolean hasUrl = monitorUrl != null;
        if (!hasScript && !hasUrl) {
            return null;
        }
        String scriptValue = monitorScript == null ? null : String.valueOf(monitorScript).trim();
        String urlValue = monitorUrl == null ? null : String.valueOf(monitorUrl).trim();
        if (hasScript && hasUrl && !isBlank(scriptValue) && !isBlank(urlValue)) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "Use only one of monitor, monitor_script, or monitor_url");
        }
        if (hasUrl) {
            if (!isBlank(urlValue) && !isMonitorUrl(urlValue)) {
                throw new AgentException(HttpStatus.BAD_REQUEST, "monitor_url must use http or https");
            }
            return urlValue == null ? "" : urlValue;
        }
        return scriptValue == null ? "" : scriptValue;
    }

    private static String monitorSource(Map<String, Object> body) {
        if (!containsAny(body, "monitor", "monitor_script", "monitor_url")) {
            return null;
        }
        return monitorSource(body.get("monitor"), body.get("monitor_script"), body.get("monitor_url"));
    }

    private static UUID attachedSessionId(Boolean attachToSession, Object attachedSessionId) {
        if (!Boolean.TRUE.equals(attachToSession)) {
            return null;
        }
        String id = optionalText(attachedSessionId, null);
        if (id == null) {
            throw new AgentException(HttpStatus.BAD_REQUEST,
                "attach_to_session=true requires attached_session_id on REST requests");
        }
        return parseAttachedSessionId(id);
    }

    private static UUID attachedSessionIdForUpdate(Map<String, Object> body) {
        Boolean attach = booleanUpdate(body, "attach_to_session");
        if (!Boolean.TRUE.equals(attach)) {
            return null;
        }
        String id = stringUpdate(body, "attached_session_id");
        if (id == null || id.isBlank()) {
            throw new AgentException(HttpStatus.BAD_REQUEST,
                "attach_to_session=true requires attached_session_id on REST requests");
        }
        return parseAttachedSessionId(id);
    }

    private static UUID parseAttachedSessionId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "attached_session_id must be a UUID");
        }
    }

    private static boolean containsAny(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            if (body.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMonitorUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Boolean updateBoolean(Object value, String field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string && Set.of("true", "false").contains(string.toLowerCase())) {
            return Boolean.parseBoolean(string);
        }
        throw new AgentException(HttpStatus.BAD_REQUEST, field + " must be a boolean");
    }

    private static String normalizeDashboardCronScript(Object value, boolean update) {
        if (value == null) {
            return update ? "" : null;
        }
        String text = optionalText(value, null);
        if (text == null) {
            return update ? "" : null;
        }
        Path scriptsRoot = resolveCronScriptsDir();
        Path rawPath = expandHome(text);
        Path candidate = rawPath.isAbsolute()
            ? rawPath.toAbsolutePath().normalize()
            : scriptsRoot.resolve(rawPath).normalize();
        if (!candidate.startsWith(scriptsRoot)) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "script must be inside " + scriptsRoot);
        }
        if (!Files.exists(candidate)) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "script does not exist: " + candidate);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "script is not a file: " + candidate);
        }
        return scriptsRoot.relativize(candidate).toString().replace('\\', '/');
    }

    private static Path expandHome(String text) {
        try {
            if (text.equals("~") || text.startsWith("~/") || text.startsWith("~\\")) {
                return Path.of(System.getProperty("user.home", "."), text.substring(1));
            }
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "script is not a valid path: " + text);
        }
    }

    private static Path resolveCronScriptsDir() {
        String hermesHome = System.getenv("HERMES_HOME");
        Path home = hermesHome == null || hermesHome.isBlank()
            ? Path.of(System.getProperty("user.home", "."), ".hermes")
            : Path.of(hermesHome);
        return home.resolve("scripts").toAbsolutePath().normalize();
    }

    private static void validateDashboardCronEffectiveJob(
        String prompt,
        String skills,
        String script,
        boolean noAgent
    ) {
        if (noAgent) {
            if (script == null || script.isBlank()) {
                throw new AgentException(HttpStatus.BAD_REQUEST, "no_agent=True requires a script");
            }
            return;
        }
        if ((prompt == null || prompt.isBlank())
            && (skills == null || skills.isBlank())
            && (script == null || script.isBlank())) {
            throw new AgentException(HttpStatus.BAD_REQUEST, "agent cron jobs require a prompt, skill, or script");
        }
    }

    private static boolean executionFieldsChanged(Map<String, Object> updates) {
        return updates.keySet().stream()
            .anyMatch(Set.of("prompt", "skills", "script", "no_agent")::contains);
    }

    private static String stringUpdate(Map<String, Object> updates, String key) {
        return updates.containsKey(key) ? (String) updates.get(key) : null;
    }

    private static String stringUpdate(Map<String, Object> updates, String key, String fallback) {
        return updates.containsKey(key) ? (String) updates.get(key) : fallback;
    }

    private static Boolean booleanUpdate(Map<String, Object> updates, String key) {
        return updates.containsKey(key) ? (Boolean) updates.get(key) : null;
    }

    private static boolean booleanUpdate(Map<String, Object> updates, String key, boolean fallback) {
        return updates.containsKey(key) ? (Boolean) updates.get(key) : fallback;
    }

    private static int parseRunsLimit(String raw) {
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw), 100));
        } catch (RuntimeException e) {
            return 20;
        }
    }

    private static String dashboardJobName(Object rawName, String prompt, String skills, String script, boolean noAgent) {
        String name = optionalText(rawName, null);
        if (name != null) {
            return name;
        }
        String label = optionalText(prompt, null);
        if (label == null && skills != null && !skills.isBlank()) {
            label = skills.split(",")[0].trim();
        }
        if (label == null && noAgent) {
            label = optionalText(script, null);
        }
        if (label == null) {
            label = "cron job";
        }
        return label.length() > 50 ? label.substring(0, 50).trim() : label;
    }

    private record CronJobUpdateBody(Map<String, Object> updates) {
    }

    private record DashboardCronJobCreateBody(
        Object prompt,
        Object schedule,
        Object name,
        Object deliver,
        Object skills,
        Object model,
        Object provider,
        Object base_url,
        Object script,
        Object context_from,
        Object enabled_toolsets,
        Object workdir,
        Boolean no_agent,
        Object monitor,
        Object monitor_script,
        Object monitor_url,
        Boolean continuity,
        Boolean attach_to_session,
        Object attached_session_id
    ) {
    }

    private record AutomationBlueprintInstantiate(String blueprint, Map<String, String> values) {
    }
}
