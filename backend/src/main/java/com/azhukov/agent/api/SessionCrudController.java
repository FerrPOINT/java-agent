package com.azhukov.agent.api;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.api.dto.ChatResponseDto;
import com.azhukov.agent.api.dto.SessionSummaryDto;
import com.azhukov.agent.api.mapper.DomainDtoMapper;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.agent.AgentSessionResolver;
import com.azhukov.agent.core.agent.SessionDeletedEvent;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.persistence.entity.MessageEntity;
import com.azhukov.agent.persistence.entity.SessionEntity;
import com.azhukov.agent.persistence.mapper.SessionEntityMapper;
import com.azhukov.agent.persistence.repository.MessageRepository;
import com.azhukov.agent.persistence.repository.SessionRepository;
import com.azhukov.agent.service.ApiRunAdmissionService;
import com.azhukov.agent.service.AgentRuntimeService;
import com.azhukov.agent.service.HermesSessionStreamingService;
import com.azhukov.agent.service.OpenAiSessionService;
import com.azhukov.agent.service.ProfileService;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Session CRUD and session-scoped chat endpoints.
 *
 * Mirrors Hermes' /api/sessions endpoints:
 * <ul>
 *   <li>GET    /api/sessions — list sessions (paginated)</li>
 *   <li>POST   /api/sessions — create a new session</li>
 *   <li>GET    /api/sessions/{id} — get a session by ID</li>
 *   <li>PATCH  /api/sessions/{id} — update session metadata (title)</li>
 *   <li>DELETE /api/sessions/{id} — delete a session</li>
 *   <li>GET    /api/sessions/{id}/messages — list session messages</li>
 *   <li>POST   /api/sessions/{id}/fork — fork a session</li>
 *   <li>POST   /api/sessions/{id}/chat — chat within a session</li>
 *   <li>POST   /api/sessions/{id}/chat/stream — stream chat within a session</li>
 * </ul>
 */
@RestController
@RequestMapping({"/api/sessions", "/api/v2/sessions", "/p/{profile}/api/sessions", "/p/{profile}/api/v2/sessions"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Session CRUD", description = "Session lifecycle CRUD and session-scoped chat")
public class SessionCrudController {

    private static final Set<String> UPDATE_SESSION_FIELDS = Set.of(
        "title", "end_reason", "endReason", "pinned", "archived", "hidden", "unread");
    private static final Set<String> SESSION_SOURCES = Set.of(
        "api_server", "hermes_browser", "browser", "cli", "telegram",
        "discord", "slack", "desktop", "dashboard");
    private static final int MAX_SESSION_HEADER_LENGTH = 256;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final String MODEL_CONFIG_PRESENT_KEY = "browserModelConfigPresent";
    private static final String MODEL_LOCK_CONFIRMED_KEY = "browserModelLockConfirmed";
    private static final String MODEL_LOCK_REQUESTED_MODEL_KEY = "browserModelLockRequestedModel";
    private static final String MODEL_LOCK_REQUESTED_PROVIDER_KEY = "browserModelLockRequestedProvider";
    private static final String MODEL_LOCK_RUNTIME_MODEL_KEY = "browserModelLockRuntimeModel";
    private static final String MODEL_LOCK_RUNTIME_PROVIDER_KEY = "browserModelLockRuntimeProvider";
    private static final String MODEL_LOCK_ROUTE_SOURCE_KEY = "browserModelLockRouteSource";
    private static final String MODEL_LOCK_MODEL_OPTIONS_KEY = "browserModelLockModelOptions";
    private static final int MAX_LATEST_DESCENDANT_DEPTH = 100;
    private static final int SESSION_IMPORT_MAX_BYTES = 25 * 1024 * 1024;

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AgentRuntimeService agentRuntimeService;
    private final HermesSessionStreamingService streamingService;
    private final AgentSessionResolver sessionResolver;
    private final SessionEntityMapper sessionMapper;
    private final DomainDtoMapper domainDtoMapper;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final ApiRunAdmissionService runAdmissionService;
    private final ProfileService profileService;

    // ── List sessions ──

    @Operation(summary = "List sessions with pagination")
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> listSessions(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String offset,
            @RequestParam(required = false) String userId,
            @RequestParam(name = "user_id", required = false) String userIdSnake,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String archived,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) String includeArchived,
            @RequestParam(name = "include_archived", required = false) String includeArchivedSnake,
            @RequestParam(required = false) String includeHidden,
            @RequestParam(name = "include_hidden", required = false) String includeHiddenSnake,
            @RequestParam(required = false) String includeChildren,
            @RequestParam(name = "include_children", required = false) String includeChildrenSnake,
            @RequestParam(required = false) String includePinned,
            @RequestParam(name = "include_pinned", required = false) String includePinnedSnake
    ) {
        int cappedLimit = parseHermesListQueryInt(limit, 50, 200);
        int cappedOffset = parseHermesListQueryInt(offset, 0, 1_000_000);
        String effectiveUserId = firstNonBlank(userId, userIdSnake, AgentProperties.DEFAULT_USER_ID);
        String sourceFilter = blankToNull(source);
        String titleFilter = blankToNull(title);
        ArchivedFilter archivedFilter;
        try {
            archivedFilter = parseArchivedFilter(archived, includeArchived, includeArchivedSnake);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        String orderBy;
        try {
            orderBy = parseSessionListOrder(order);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        boolean includeHiddenFlag = titleFilter != null && anyTruthy(includeHidden, includeHiddenSnake);
        boolean includeChildrenFlag = anyTruthy(includeChildren, includeChildrenSnake);
        boolean includePinnedFlag = !anyFalse(includePinned, includePinnedSnake);
        String profileScope;
        try {
            profileScope = resolvePathProfileScope(pathProfile);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }

        List<SessionEntity> pinnedEntities = includePinnedFlag
            ? nullToEmpty("recent".equals(orderBy)
                ? (profileScope != null
                    ? sessionRepository.findPinnedByUserIdAndProfileOrderByRecent(
                        effectiveUserId,
                        profileScope,
                        archivedFilter.includeArchived(),
                        archivedFilter.archivedOnly(),
                        includeHiddenFlag,
                        sourceFilter,
                        titleFilter,
                        includeChildrenFlag)
                    : sessionRepository.findPinnedByUserIdOrderByRecent(
                effectiveUserId,
                archivedFilter.includeArchived(),
                archivedFilter.archivedOnly(),
                includeHiddenFlag,
                sourceFilter,
                titleFilter,
                includeChildrenFlag))
                : (profileScope != null
                    ? sessionRepository.findPinnedByUserIdAndProfileOrderByCreated(
                        effectiveUserId,
                        profileScope,
                        archivedFilter.includeArchived(),
                        archivedFilter.archivedOnly(),
                        includeHiddenFlag,
                        sourceFilter,
                        titleFilter,
                        includeChildrenFlag)
                    : sessionRepository.findPinnedByUserIdOrderByCreated(
                    effectiveUserId,
                    archivedFilter.includeArchived(),
                    archivedFilter.archivedOnly(),
                    includeHiddenFlag,
                    sourceFilter,
                    titleFilter,
                    includeChildrenFlag)))
            : List.of();
        List<SessionEntity> windowEntities = nullToEmpty("recent".equals(orderBy)
            ? (profileScope != null
                ? sessionRepository.findPageByUserIdAndProfileOrderByRecent(
                    effectiveUserId,
                    profileScope,
                    cappedLimit,
                    cappedOffset,
                    archivedFilter.includeArchived(),
                    archivedFilter.archivedOnly(),
                    includeHiddenFlag,
                    sourceFilter,
                    titleFilter,
                    includeChildrenFlag,
                    includePinnedFlag)
                : sessionRepository.findPageByUserIdOrderByRecent(
                effectiveUserId,
                cappedLimit,
                cappedOffset,
                archivedFilter.includeArchived(),
                archivedFilter.archivedOnly(),
                includeHiddenFlag,
                sourceFilter,
                titleFilter,
                includeChildrenFlag,
                includePinnedFlag))
            : (profileScope != null
                ? sessionRepository.findPageByUserIdAndProfileOrderByCreated(
                    effectiveUserId,
                    profileScope,
                    cappedLimit,
                    cappedOffset,
                    archivedFilter.includeArchived(),
                    archivedFilter.archivedOnly(),
                    includeHiddenFlag,
                    sourceFilter,
                    titleFilter,
                    includeChildrenFlag,
                    includePinnedFlag)
                : sessionRepository.findPageByUserIdOrderByCreated(
                effectiveUserId,
                cappedLimit,
                cappedOffset,
                archivedFilter.includeArchived(),
                archivedFilter.archivedOnly(),
                includeHiddenFlag,
                sourceFilter,
                titleFilter,
                includeChildrenFlag,
                includePinnedFlag)));

        List<SessionEntity> entities = new ArrayList<>(pinnedEntities);
        Set<UUID> seenPinned = pinnedEntities.stream()
            .map(SessionEntity::getId)
            .collect(java.util.stream.Collectors.toSet());
        windowEntities.stream()
            .filter(entity -> !seenPinned.contains(entity.getId()))
            .forEach(entities::add);

        List<Map<String, Object>> sessions = entities
            .stream()
            .map(this::toSessionPayload)
            .toList();

        // M15: Use count query for reliable has_more instead of size==limit heuristic.
        // The old approach (sessions.size() == cappedLimit) is unreliable because
        // it reports has_more=true even when exactly cappedLimit results exist (the last page).
        long totalCount = profileScope != null
            ? sessionRepository.countVisibleByUserIdAndProfile(
                effectiveUserId,
                profileScope,
                archivedFilter.includeArchived(),
                archivedFilter.archivedOnly(),
                includeHiddenFlag,
                sourceFilter,
                titleFilter,
                includeChildrenFlag,
                includePinnedFlag)
            : sessionRepository.countVisibleByUserId(
                effectiveUserId,
                archivedFilter.includeArchived(),
                archivedFilter.archivedOnly(),
                includeHiddenFlag,
                sourceFilter,
                titleFilter,
                includeChildrenFlag,
                includePinnedFlag);
        boolean hasMore = (cappedOffset + windowEntities.size()) < totalCount;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "list");
        response.put("data", sessions);
        response.put("sessions", sessions);
        response.put("total", totalCount);
        response.put("limit", cappedLimit);
        response.put("offset", cappedOffset);
        response.put("order", orderBy);
        response.put("has_more", hasMore);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Bulk-delete sessions")
    @PostMapping("/bulk-delete")
    @Transactional
    public ResponseEntity<Map<String, Object>> bulkDeleteSessions(
            @RequestBody(required = false) BulkDeleteSessionsBody body) {
        List<String> rawIds = body != null && body.ids() != null ? body.ids() : List.of();
        if (rawIds.size() > 500) {
            return ResponseEntity.badRequest().body(Map.of("detail", "ids must contain at most 500 entries"));
        }
        int deleted = deleteSessionsByIds(parseUuidList(rawIds));
        return ResponseEntity.ok(Map.of("ok", true, "deleted", deleted));
    }

    @Operation(summary = "Count empty ended sessions")
    @GetMapping("/empty/count")
    @Transactional(readOnly = true)
    public Map<String, Object> countEmptySessions() {
        return Map.of("count", sessionRepository.countEmptyEndedUnarchived());
    }

    @Operation(summary = "Delete empty ended sessions")
    @DeleteMapping("/empty")
    @Transactional
    public Map<String, Object> deleteEmptySessions() {
        int deleted = deleteSessionsByIds(sessionRepository.findEmptyEndedUnarchivedIds());
        return Map.of("ok", true, "deleted", deleted);
    }

    @Operation(summary = "Get session-store statistics")
    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public Map<String, Object> getSessionStats() {
        return Map.of(
            "total", sessionRepository.count(),
            "active_store", sessionRepository.countUnarchivedSessions(),
            "archived", sessionRepository.countArchivedSessions(),
            "messages", messageRepository.count(),
            "by_source", sessionCountsBySource()
        );
    }

    public record BulkDeleteSessionsBody(List<String> ids, String profile) {}

    @Operation(summary = "Import dashboard-exported sessions")
    @PostMapping("/import")
    @Transactional
    public ResponseEntity<Map<String, Object>> importSessions(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @RequestBody(required = false) String rawBody) {
        ResponseEntity<Map<String, Object>> validationError = validateSessionImportPayload(rawBody);
        if (validationError != null) {
            return validationError;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            return badRequestDetail("Invalid session import payload");
        }
        String profile;
        try {
            profile = resolveMutationProfileScope(pathProfile, optionalText(root.get("profile")));
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }

        List<String> importedIds = new ArrayList<>();
        List<String> skippedIds = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        JsonNode sessions = root.get("sessions");
        for (int i = 0; i < sessions.size(); i++) {
            JsonNode sessionNode = sessions.get(i);
            ImportSessionRow row = importSessionRow(sessionNode, i, profile);
            if (row.error() != null) {
                errors.add(row.error());
                continue;
            }
            if (sessionRepository.existsById(row.entity().getId())) {
                skippedIds.add(row.externalId());
                continue;
            }
            sessionRepository.save(row.entity());
            for (MessageEntity message : row.messages()) {
                messageRepository.save(message);
            }
            importedIds.add(row.externalId());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", errors.isEmpty());
        result.put("imported", importedIds.size());
        result.put("skipped", skippedIds.size());
        result.put("imported_ids", importedIds);
        result.put("skipped_ids", skippedIds);
        result.put("errors", errors);
        result.put("profile", profile);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("detail", result));
        }
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Backfill owning profile for legacy session rows")
    @PostMapping("/owner-backfill")
    @Transactional
    public ResponseEntity<Map<String, Object>> backfillSessionOwnerProfiles(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @RequestBody(required = false) String rawBody) {
        JsonNode root = objectMapper.createObjectNode();
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                root = objectMapper.readTree(rawBody);
            } catch (JsonProcessingException e) {
                return sessionError(HttpStatus.BAD_REQUEST, "Invalid JSON in request body", null);
            }
            if (root == null || !root.isObject()) {
                return sessionError(HttpStatus.BAD_REQUEST, "Request body must be a JSON object", null);
            }
        }

        String profile;
        try {
            profile = resolveMutationProfileScope(pathProfile, optionalText(root.get("profile")));
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        int stamped = sessionRepository.backfillBlankProfiles(profile);
        return ResponseEntity.ok(Map.of("ok", true, "stamped", stamped, "profile", profile));
    }

    @Operation(summary = "Prune ended sessions matching safe filters")
    @PostMapping("/prune")
    @Transactional
    public ResponseEntity<Map<String, Object>> pruneSessions(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @RequestBody(required = false) String rawBody) {
        ResponseEntity<Map<String, Object>> validationError = validateSessionPrunePayload(rawBody);
        if (validationError != null) {
            return validationError;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            return badRequestDetail("Invalid JSON in request body");
        }
        String unsupported = unsupportedPruneFilter(root);
        if (unsupported != null) {
            return notImplemented("session prune filter '" + unsupported + "' is not implemented in the Java port");
        }

        String profile;
        try {
            profile = resolveMutationProfileScope(pathProfile, optionalText(root.get("profile")));
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        PruneRequest request;
        try {
            request = pruneRequest(root);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }

        List<SessionEntity> candidates = sessionRepository.findPruneCandidates(
            profile,
            request.lastActiveBefore(),
            request.startedBefore(),
            request.startedAfter(),
            request.source(),
            request.titleLike(),
            request.endReason(),
            request.userId(),
            request.minMessages(),
            request.maxMessages(),
            request.modelLike(),
            request.includeArchived());
        long skippedOpen = sessionRepository.countOpenPruneMatches(
            profile,
            request.lastActiveBefore(),
            request.startedBefore(),
            request.startedAfter(),
            request.source(),
            request.titleLike(),
            request.endReason(),
            request.userId(),
            request.minMessages(),
            request.maxMessages(),
            request.modelLike(),
            request.includeArchived());

        if (request.dryRun()) {
            return ResponseEntity.ok(pruneDryRunResponse(candidates, skippedOpen));
        }

        int removed = deleteSessionsByIds(candidates.stream().map(SessionEntity::getId).toList());
        return ResponseEntity.ok(Map.of("ok", true, "removed", removed, "skipped_open", skippedOpen));
    }

    // ── Create session ──

    @Operation(summary = "Create a new session")
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @RequestBody(required = false) String rawBody) {
        ParsedRequestBody<CreateSessionBody> parsedBody = parseJsonObjectBody(rawBody, CreateSessionBody.class);
        if (parsedBody.error() != null) {
            return parsedBody.error();
        }
        CreateSessionBody body = parsedBody.body();
        String userId = body != null && body.userId() != null ? body.userId() : AgentProperties.DEFAULT_USER_ID;
        ModelLockSelection selection;
        try {
            selection = parseModelLockSelection(
                body != null ? body.model() : null,
                body != null ? body.provider() : null);
        } catch (IllegalArgumentException e) {
            return sessionError(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_model");
        }
        boolean requireModelLock = body != null && OpenAiRequestBooleans.coerce(body.requireModelLock(), false);
        ResponseEntity<Map<String, Object>> lockError = validateModelLockSelection(selection, requireModelLock);
        if (lockError != null) {
            return lockError;
        }
        String model = selection.requestedModel() != null ? selection.requestedModel() : "";
        String title;
        try {
            title = sanitizeTitle(body != null ? body.title() : null);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        String source = normalizeSessionSource(body != null ? body.source() : null);
        Object systemPromptValue = body != null ? body.systemPrompt() : null;
        if (systemPromptValue != null && !(systemPromptValue instanceof String)) {
            return sessionError(
                HttpStatus.BAD_REQUEST,
                "system_prompt must be a string",
                "invalid_system_prompt");
        }
        UUID requestedId;
        try {
            requestedId = parseRequestedSessionId(body);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        if (requestedId != null && sessionRepository.existsById(requestedId)) {
            return sessionError(HttpStatus.CONFLICT, "Session already exists: " + requestedId, "session_exists");
        }
        try {
            ensureTitleAvailable(title, requestedId, false);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        String profileScope;
        try {
            profileScope = resolveMutationProfileScope(pathProfile, body != null ? body.profile() : null);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }

        SessionEntity entity = new SessionEntity();
        entity.setId(requestedId);
        entity.setUserId(userId);
        entity.setModelProvider(selection.requestedProvider() != null ? selection.requestedProvider() : "openai-compatible");
        entity.setModelName(model);
        entity.setTitle(title);
        entity.setSource(source);
        entity.setProfile(profileScope);
        entity.setSystemPrompt((String) systemPromptValue);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setLastActive(Instant.now());
        entity.setMessageCount(0);
        Map<String, Object> modelOptions = modelOptionsMap(body != null ? body.modelOptions() : null);
        if (selection.requestedModel() != null || selection.requestedProvider() != null) {
            markBrowserModelConfig(entity, selection, requireModelLock, modelOptions);
        }
        SessionEntity saved = sessionRepository.save(entity);

        return ResponseEntity.created(URI.create("/api/sessions/" + saved.getId()))
            .body(sessionEnvelope(toSessionPayload(saved)));
    }

    public record CreateSessionBody(
        @JsonAlias("session_id") String id,
        @JsonAlias("user_id") String userId,
        @JsonAlias("model_id") Object model,
        @JsonAlias("provider_id") Object provider,
        String title,
        String source,
        @JsonProperty("system_prompt") @JsonAlias("systemPrompt") Object systemPrompt,
        @JsonProperty("model_options") @JsonAlias("modelOptions") Object modelOptions,
        @JsonProperty("require_model_lock") @JsonAlias("requireModelLock") Object requireModelLock,
        Object profile
    ) {}

    // ── Get session ──

    @Operation(summary = "Get a session by ID")
    @GetMapping("/{sessionId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getSession(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return sessionNotFound(sessionId);
        }
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatch(pathProfile, entity, sessionId);
        if (profileError != null) {
            return profileError;
        }
        return ResponseEntity.ok(sessionEnvelope(toSessionPayload(entity)));
    }

    // ── Update session ──

    @Operation(summary = "Update session metadata (title)")
    @PatchMapping("/{sessionId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateSession(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId,
            @RequestBody(required = false) String rawBody) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return sessionNotFound(sessionId);
        }
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatch(pathProfile, entity, sessionId);
        if (profileError != null) {
            return profileError;
        }
        ParsedRequestBody<Map<String, Object>> parsedBody = parseJsonObjectMapBody(rawBody);
        if (parsedBody.error() != null) {
            return parsedBody.error();
        }
        try {
            applySessionUpdate(entity, parsedBody.body());
        } catch (SessionUpdateException e) {
            return sessionError(HttpStatus.BAD_REQUEST, e.getMessage(), e.code());
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        entity.setUpdatedAt(Instant.now());
        sessionRepository.save(entity);

        Map<String, Object> response = sessionEnvelope(toSessionPayload(entity));
        response.put("ok", true);
        response.put("title", entity.getTitle() != null ? entity.getTitle() : "");
        if (parsedBody.body().containsKey("archived")) {
            response.put("archived", Boolean.TRUE.equals(entity.getArchived()));
        }
        if (parsedBody.body().containsKey("hidden")) {
            response.put("hidden", Boolean.TRUE.equals(entity.getHidden()));
        }
        if (parsedBody.body().containsKey("pinned")) {
            response.put("pinned", Boolean.TRUE.equals(entity.getPinned()));
        }
        if (parsedBody.body().containsKey("unread")) {
            response.put("unread", Boolean.TRUE.equals(entity.getUnread()));
        }
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get the latest descendant in a compression lineage")
    @GetMapping("/{sessionId}/latest-descendant")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getSessionLatestDescendant(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId) {
        java.util.Optional<SessionEntity> entity = sessionRepository.findById(sessionId);
        if (entity.isEmpty()) {
            return sessionNotFound(sessionId);
        }
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatch(pathProfile, entity.get(), sessionId);
        if (profileError != null) {
            return profileError;
        }
        LatestDescendant latest = findLatestDescendant(sessionId);
        return ResponseEntity.ok(Map.of(
            "requested_session_id", sessionId.toString(),
            "session_id", latest.sessionId().toString(),
            "path", latest.path().stream().map(UUID::toString).toList(),
            "changed", !latest.sessionId().equals(sessionId)
        ));
    }

    private LatestDescendant findLatestDescendant(UUID sessionId) {
        List<UUID> path = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = sessionId;
        for (int i = 0; i < MAX_LATEST_DESCENDANT_DEPTH; i++) {
            if (current == null || !seen.add(current)) {
                break;
            }
            path.add(current);
            UUID next = sessionRepository.findByParentSessionIdOrderByCreatedAtDesc(current).stream()
                .map(SessionEntity::getId)
                .filter(id -> id != null && !seen.contains(id))
                .findFirst()
                .orElse(null);
            if (next == null) {
                break;
            }
            current = next;
        }
        return new LatestDescendant(path.isEmpty() ? sessionId : path.get(path.size() - 1), path);
    }

    private record LatestDescendant(UUID sessionId, List<UUID> path) {}

    // ── Delete session ──

    @Operation(summary = "Delete a session and all its messages")
    @DeleteMapping("/{sessionId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteSession(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return ResponseEntity.ok(Map.of("ok", true, "already_absent", true));
        }
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatch(pathProfile, entity, sessionId);
        if (profileError != null) {
            return profileError;
        }
        sessionRepository.orphanChildrenOf(List.of(sessionId));
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(entity);
        // Evict per-session in-memory state (runtime maps, context caches, review state)
        // so deleted sessions do not leak memory (audit finding C3).
        eventPublisher.publishEvent(new SessionDeletedEvent(sessionId));
        return ResponseEntity.ok(Map.of(
            "object", "hermes.session.deleted",
            "ok", true,
            "id", sessionId.toString(),
            "deleted", true
        ));
    }

    @Operation(summary = "Export one session with active messages")
    @GetMapping("/{sessionId}/export")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> exportSession(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return sessionNotFound(sessionId);
        }
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatch(pathProfile, entity, sessionId);
        if (profileError != null) {
            return profileError;
        }
        Map<String, Object> payload = new LinkedHashMap<>(toSessionPayload(entity));
        payload.put("messages", messageRepository.findBySessionIdAndActiveTrueOrderByCreatedAtAsc(sessionId)
            .stream()
            .map(this::toMessageResponse)
            .toList());
        return ResponseEntity.ok(payload);
    }

    // ── Get session messages ──

    @Operation(summary = "List messages in a session")
    @GetMapping("/{sessionId}/messages")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getSessionMessages(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId,
            @RequestParam(required = false) String limit,
            @RequestParam(defaultValue = "0") String offset,
            @RequestParam(required = false) String order,
            @RequestParam(name = "include_compacted", required = false) String includeCompacted,
            @RequestParam(name = "includeCompacted", required = false) String includeCompactedCamel) {
        if (order != null && !order.equals("oldest") && !order.equals("latest")) {
            return sessionError(
                HttpStatus.BAD_REQUEST,
                "order must be one of: oldest, latest",
                "invalid_pagination");
        }
        boolean defaultPage = limit == null;
        int requestedLimit;
        int requestedOffset;
        try {
            requestedLimit = defaultPage ? 500 : parseNonNegativeQueryInt(limit, "limit");
            requestedOffset = parseNonNegativeQueryInt(offset, "offset");
        } catch (IllegalArgumentException e) {
            return sessionError(HttpStatus.BAD_REQUEST,
                "limit and offset must be non-negative integers",
                "invalid_pagination");
        }
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatchIfScoped(pathProfile, sessionId);
        if (profileError != null) {
            return profileError;
        }
        if (!sessionRepository.existsById(sessionId)) {
            return sessionNotFound(sessionId);
        }
        UUID resolvedId = sessionResolver.resolveResumeSessionId(sessionId);
        if (resolvedId == null) {
            resolvedId = sessionId;
        }
        int cappedLimit = Math.min(requestedLimit, 500);
        boolean latestPage = "latest".equals(order) || (order == null && defaultPage);
        boolean includeCompactedFlag = anyTruthy(includeCompacted, includeCompactedCamel);

        List<MessageEntity> messages;
        if (includeCompactedFlag) {
            messages = pageDisplayMessages(resolvedId, cappedLimit, requestedOffset, latestPage);
        } else if (latestPage) {
            messages = new ArrayList<>(messageRepository
                .findActivePageBySessionIdOrderByCreatedAtDesc(resolvedId, cappedLimit, requestedOffset));
            Collections.reverse(messages);
        } else {
            messages = messageRepository
                .findActivePageBySessionIdOrderByCreatedAtAsc(resolvedId, cappedLimit, requestedOffset);
        }

        List<Map<String, Object>> data = messages.stream()
            .map(this::toMessageResponse)
            .toList();

        return ResponseEntity.ok(Map.of(
            "object", "list",
            "session_id", resolvedId.toString(),
            "data", data,
            "messages", data,
            "limit", cappedLimit,
            "offset", requestedOffset,
            "pagination", Map.of(
                "limit", cappedLimit,
                "offset", requestedOffset,
                "order", order != null ? order : (defaultPage ? "latest" : "oldest"),
                "include_compacted", includeCompactedFlag,
                "returned", messages.size()
            )
        ));
    }

    // ── Fork session ──

    @Operation(summary = "Fork a session")
    @PostMapping("/{sessionId}/fork")
    public ResponseEntity<Map<String, Object>> forkSession(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId,
            @RequestBody(required = false) String rawBody) {
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatchIfScoped(pathProfile, sessionId);
        if (profileError != null) {
            return profileError;
        }
        if (!sessionRepository.existsById(sessionId)) {
            return sessionNotFound(sessionId);
        }
        ParsedRequestBody<ForkSessionBody> parsedBody = parseJsonObjectBody(rawBody, ForkSessionBody.class);
        if (parsedBody.error() != null) {
            return parsedBody.error();
        }
        ForkSessionBody body = parsedBody.body();
        String name = body != null && body.title() != null ? body.title()
            : body != null ? body.name() : null;
        UUID requestedForkId;
        try {
            requestedForkId = parseOptionalSessionId(body != null ? body.id() : null,
                body != null ? body.sessionId() : null);
        } catch (IllegalArgumentException e) {
            return sessionError(HttpStatus.BAD_REQUEST, "Invalid session ID", "invalid_session_id");
        }
        if (requestedForkId != null && sessionRepository.existsById(requestedForkId)) {
            return sessionError(HttpStatus.CONFLICT,
                "Session already exists: " + requestedForkId,
                "session_exists");
        }
        SessionSummaryDto fork = requestedForkId != null
            ? agentRuntimeService.branchSession(sessionId, requestedForkId, name)
            : agentRuntimeService.branchSession(sessionId, name);
        return ResponseEntity.created(URI.create("/api/sessions/" + fork.id()))
            .body(sessionEnvelope(toSessionPayload(fork)));
    }

    public record ForkSessionBody(String title, String name, String id, @JsonProperty("session_id") String sessionId) {}

    // ── Session-scoped model lock ──

    @Operation(summary = "Lock a model for this session")
    @PostMapping("/{sessionId}/model")
    public ResponseEntity<Map<String, Object>> lockSessionModel(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId,
            @RequestBody(required = false) String rawBody) {
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatchIfScoped(pathProfile, sessionId);
        if (profileError != null) {
            return profileError;
        }
        if (!sessionRepository.existsById(sessionId)) {
            return sessionNotFound(sessionId);
        }
        ParsedRequestBody<SessionModelLockBody> parsedBody =
            parseJsonObjectBody(rawBody, SessionModelLockBody.class);
        if (parsedBody.error() != null) {
            return parsedBody.error();
        }
        SessionModelLockBody body = parsedBody.body();
        ModelLockSelection selection;
        try {
            selection = parseModelLockSelection(
                body != null ? body.model() : null,
                body != null ? body.provider() : null);
        } catch (IllegalArgumentException e) {
            return sessionError(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_model_lock");
        }
        if (selection.requestedModel() == null && selection.requestedProvider() == null) {
            return sessionError(
                HttpStatus.BAD_REQUEST,
                "require_model_lock was set but no model/provider was provided",
                "missing_model");
        }
        if (selection.runtimeModel() == null || "global".equals(selection.routeSource())) {
            return sessionError(
                HttpStatus.CONFLICT,
                "Requested Browser model lock cannot be routed; refusing silent global fallback",
                "model_lock_unavailable");
        }

        Map<String, Object> modelOptions = modelLockOptions(body);
        agentRuntimeService.switchModel(
            sessionId,
            selection.requestedModel(),
            selection.requestedProvider(),
            modelOptions);
        persistBrowserModelConfig(sessionId, selection, true, modelOptions);
        Map<String, Object> runtime = modelLockRuntime(selection, modelOptions);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "hermes.session.model_lock");
        response.put("session_id", sessionId.toString());
        response.put("runtime", runtime);
        return ResponseEntity.ok(response);
    }

    public record SessionModelLockBody(
        @JsonAlias("model_id") Object model,
        @JsonAlias("provider_id") Object provider,
        @JsonProperty("model_options") @JsonAlias("modelOptions") Object modelOptions,
        @JsonProperty("reasoning_effort") @JsonAlias("reasoningEffort") String reasoningEffort,
        @JsonProperty("fast_mode") @JsonAlias("fastMode") Boolean fastMode,
        @JsonProperty("max_completion_tokens") @JsonAlias({"maxCompletionTokens", "max_tokens", "maxTokens"})
            Integer maxCompletionTokens
    ) {}

    // ── Session-scoped chat (synchronous) ──

    @Operation(summary = "Chat within a specific session (synchronous)")
    @PostMapping("/{sessionId}/chat")
    public ResponseEntity<?> sessionChat(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId,
            @RequestHeader(value = OpenAiSessionService.SESSION_KEY_HEADER, required = false) String sessionKey,
            @RequestBody(required = false) String rawBody) {
        String normalizedSessionKey;
        try {
            normalizedSessionKey = parseSessionKeyHeader(sessionKey);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatchIfScoped(pathProfile, sessionId);
        if (profileError != null) {
            return profileError;
        }
        if (!sessionRepository.existsById(sessionId)) {
            return sessionNotFound(sessionId);
        }
        ParsedRequestBody<SessionChatRequest> parsedBody = parseJsonObjectBody(rawBody, SessionChatRequest.class);
        if (parsedBody.error() != null) {
            return parsedBody.error();
        }
        SessionChatRequest body = parsedBody.body();
        var reservation = runAdmissionService.tryAcquire();
        if (reservation.isEmpty()) {
            return concurrencyLimitedSessionResponse();
        }
        try (ApiRunAdmissionService.Reservation ignored = reservation.get()) {
            ResponseEntity<Map<String, Object>> validationError = validateSessionChatRequest(sessionId, body);
            if (validationError != null) {
                return validationError;
            }
            ChatRequest request = sessionChatRequest(sessionId, body);
            ChatResponseDto response;
            response = agentRuntimeService.runTurn(request);
            UUID effectiveSessionId = response.sessionId() != null ? response.sessionId() : sessionId;
            return withSessionHeaders(ResponseEntity.ok(), effectiveSessionId, normalizedSessionKey)
                .body(sessionChatEnvelope(sessionId, effectiveSessionId, body, response));
        }
    }

    // ── Session-scoped chat (streaming) ──

    @Operation(summary = "Stream chat within a specific session via SSE")
    @PostMapping(value = "/{sessionId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> sessionChatStream(
            @PathVariable(name = "profile", required = false) String pathProfile,
            @PathVariable UUID sessionId,
            @RequestHeader(value = OpenAiSessionService.SESSION_KEY_HEADER, required = false) String sessionKey,
            @RequestBody(required = false) String rawBody) {
        String normalizedSessionKey;
        try {
            normalizedSessionKey = parseSessionKeyHeader(sessionKey);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        ResponseEntity<Map<String, Object>> profileError = rejectProfileMismatchIfScoped(pathProfile, sessionId);
        if (profileError != null) {
            return profileError;
        }
        if (!sessionRepository.existsById(sessionId)) {
            return sessionNotFound(sessionId);
        }
        ParsedRequestBody<SessionChatRequest> parsedBody = parseJsonObjectBody(rawBody, SessionChatRequest.class);
        if (parsedBody.error() != null) {
            return parsedBody.error();
        }
        SessionChatRequest body = parsedBody.body();
        var reservation = runAdmissionService.tryAcquire();
        if (reservation.isEmpty()) {
            return concurrencyLimitedSessionResponse();
        }
        ApiRunAdmissionService.Reservation acquiredReservation = reservation.get();
        SseEmitter emitter;
        try {
            ResponseEntity<Map<String, Object>> validationError = validateSessionChatRequest(sessionId, body);
            if (validationError != null) {
                acquiredReservation.close();
                return validationError;
            }
            ChatRequest request = sessionChatRequest(sessionId, body);
            emitter = streamingService.streamTurn(
                request,
                sessionId,
                sessionChatRuntime(sessionId, body, null),
                acquiredReservation::close);
        } catch (RuntimeException e) {
            acquiredReservation.close();
            throw e;
        }
        return withSessionHeaders(ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no"), sessionId, normalizedSessionKey)
            .body(emitter);
    }

    // ── Helper methods ──

    public record SessionChatRequest(
        Object message,
        Object input,
        Long timeoutMs,
        @JsonAlias("model_id") Object model,
        @JsonAlias("provider_id") Object provider,
        @JsonProperty("model_options") @JsonAlias("modelOptions") Object modelOptions,
        @JsonProperty("max_completion_tokens")
        @JsonAlias({"maxCompletionTokens", "max_tokens", "maxTokens"})
            Integer maxCompletionTokens,
        @JsonProperty("require_model_lock") @JsonAlias("requireModelLock") Object requireModelLock,
        @JsonProperty("system_message") @JsonAlias("systemMessage") Object systemMessage,
        Object instructions
    ) {}

    private ResponseEntity<Map<String, Object>> validateSessionChatRequest(UUID sessionId, SessionChatRequest body) {
        if (body == null) {
            return sessionError(HttpStatus.BAD_REQUEST, "Missing 'message' field", "missing_message");
        }
        Object content = sessionChatPayload(body);
        if (!sessionChatHasVisiblePayload(content)) {
            return sessionError(HttpStatus.BAD_REQUEST, "Missing 'message' field", "missing_message");
        }
        try {
            OpenAiContentNormalizer.normalizeConversationText(content);
        } catch (IllegalArgumentException e) {
            return sessionError(HttpStatus.BAD_REQUEST, e.getMessage(), OpenAiContentNormalizer.errorCode(e), "message");
        }
        Object systemPrompt = sessionChatSystemPromptPayload(body);
        if (systemPrompt != null && !(systemPrompt instanceof String)) {
            return sessionError(HttpStatus.BAD_REQUEST, "system_message must be a string", "invalid_system_message");
        }
        boolean requireModelLock = OpenAiRequestBooleans.coerce(body.requireModelLock(), false);
        if (!requireModelLock) {
            String requestedModel;
            String requestedProvider;
            try {
                requestedModel = cleanRuntimeId(body.model(), "model");
                requestedProvider = cleanRuntimeId(body.provider(), "provider");
            } catch (IllegalArgumentException e) {
                return sessionError(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_model_selection");
            }
            if ((requestedModel == null || requestedModel.isBlank()) && requestedProvider != null) {
                requestedModel = storedSessionModel(sessionId);
            }
            String routeConflict = OpenAiRouteSelection.routeProviderConflict(
                properties.getApi(),
                requestedModel,
                requestedProvider
            );
            if (routeConflict != null) {
                return sessionError(HttpStatus.BAD_REQUEST, routeConflict, "invalid_model_selection");
            }
        }
        if (!requireModelLock) {
            return null;
        }
        ModelLockSelection selection;
        try {
            selection = parseModelLockSelection(body.model(), body.provider());
        } catch (IllegalArgumentException e) {
            return sessionError(HttpStatus.BAD_REQUEST, e.getMessage(), "invalid_model_lock");
        }
        if (selection.requestedModel() == null && selection.requestedProvider() == null) {
            return sessionError(
                HttpStatus.BAD_REQUEST,
                "require_model_lock was set but no model/provider was provided",
                "missing_model");
        }
        if (selection.runtimeModel() == null || "global".equals(selection.routeSource())) {
            return sessionError(
                HttpStatus.CONFLICT,
                "Requested Browser model lock cannot be routed; refusing silent global fallback",
                "model_lock_unavailable");
        }
        Map<String, Object> modelOptions = modelLockOptions(body);
        agentRuntimeService.switchModel(
            sessionId,
            selection.requestedModel(),
            selection.requestedProvider(),
            modelOptions);
        persistBrowserModelConfig(sessionId, selection, true, modelOptions);
        return null;
    }

    private String storedSessionModel(UUID sessionId) {
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        String stored = entity != null ? blankToNull(entity.getModelName()) : null;
        if (stored == null || isAdvertisedModelAlias(stored)) {
            return null;
        }
        return stored;
    }

    private String normalizeSessionChatMessage(SessionChatRequest body) {
        Object content = sessionChatPayload(body);
        if (!sessionChatHasVisiblePayload(content)) {
            throw new IllegalArgumentException("Missing 'message' field");
        }
        return OpenAiContentNormalizer.normalizeConversationText(content);
    }

    private static Object sessionChatPayload(SessionChatRequest body) {
        if (body == null) {
            return null;
        }
        Object message = body.message();
        return truthyJsonValue(message) ? message : body.input();
    }

    private static Object sessionChatSystemPromptPayload(SessionChatRequest body) {
        if (body == null) {
            return null;
        }
        Object systemMessage = body.systemMessage();
        return truthyJsonValue(systemMessage) ? systemMessage : body.instructions();
    }

    private static boolean truthyJsonValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0d;
        }
        if (value instanceof String string) {
            return !string.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private static boolean sessionChatHasVisiblePayload(Object content) {
        if (content instanceof String string) {
            return !string.trim().isEmpty();
        }
        if (content instanceof List<?> list) {
            for (Object part : list) {
                if (!(part instanceof Map<?, ?> map)) {
                    continue;
                }
                Object rawType = map.get("type");
                String type = rawType == null ? "" : String.valueOf(rawType).trim().toLowerCase(Locale.ROOT);
                if (Set.of("text", "input_text", "output_text").contains(type)
                        && hasNonBlankMapText(map)) {
                    return true;
                }
                if (Set.of("image_url", "input_image").contains(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ResponseEntity<Map<String, Object>> validateModelLockSelection(
            ModelLockSelection selection,
            boolean requireModelLock) {
        if (!requireModelLock) {
            return null;
        }
        if (selection.requestedModel() == null && selection.requestedProvider() == null) {
            return sessionError(
                HttpStatus.BAD_REQUEST,
                "require_model_lock was set but no model/provider was provided",
                "missing_model");
        }
        if (selection.runtimeModel() == null || "global".equals(selection.routeSource())) {
            return sessionError(
                HttpStatus.CONFLICT,
                "Requested Browser model lock cannot be routed; refusing silent global fallback",
                "model_lock_unavailable");
        }
        return null;
    }

    private static boolean hasNonBlankMapText(Map<?, ?> map) {
        Object text = map.get("text");
        return text != null && !String.valueOf(text).trim().isEmpty();
    }

    private ChatRequest sessionChatRequest(UUID sessionId, SessionChatRequest body) {
        ModelRequestOptions options = sessionChatOptions(sessionId, body);
        return new ChatRequest(
            sessionId,
            normalizeSessionChatMessage(body),
            null,
            body != null ? body.timeoutMs() : null,
            options.modelName(),
            options.provider(),
            options.baseUrl(),
            options.apiKey(),
            options.reasoningEffort(),
            options.fastMode(),
            options.voiceMode(),
            options.personality(),
            null,
            null,
            null,
            options.subgoal(),
            options.maxCompletionTokens(),
            normalizeSessionChatSystemPrompt(body),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            options.serviceTier());
    }

    private ModelRequestOptions sessionChatOptions(UUID sessionId, SessionChatRequest body) {
        Object requestedModel = body != null ? body.model() : null;
        Object requestedProvider = body != null ? body.provider() : null;
        if (bodyHasNoRuntimeSelection(body)) {
            String storedModel = storedSessionModel(sessionId);
            if (storedModel != null && OpenAiModelRouting.routedRoute(properties.getApi(), storedModel) != null) {
                requestedModel = storedModel;
                requestedProvider = null;
            }
        }
        return sessionChatOptions(requestedModel, requestedProvider, body);
    }

    private ModelRequestOptions sessionChatOptions(SessionChatRequest body) {
        return sessionChatOptions(
            body != null ? body.model() : null,
            body != null ? body.provider() : null,
            body);
    }

    private ModelRequestOptions sessionChatOptions(Object requestedModel, Object requestedProvider, SessionChatRequest body) {
        return OpenAiRequestModelOptions.from(
            properties,
            requestedModel,
            requestedProvider,
            body != null ? modelOptionsMap(body.modelOptions()) : null,
            body != null ? body.maxCompletionTokens() : null,
            true);
    }

    private String normalizeSessionChatSystemPrompt(SessionChatRequest body) {
        Object systemPrompt = sessionChatSystemPromptPayload(body);
        if (!(systemPrompt instanceof String raw)) {
            return null;
        }
        String normalized = OpenAiContentNormalizer.normalizeSystemText(raw);
        return normalized.isBlank() ? null : normalized;
    }

    private Map<String, Object> sessionChatEnvelope(
            UUID requestedSessionId,
            UUID effectiveSessionId,
            SessionChatRequest body,
            ChatResponseDto response) {
        String content = response.content() != null ? response.content() : "";
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content);

        Integer inputTokens = response.contextTokens() != null ? response.contextTokens() : 0;
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", 0);
        usage.put("total_tokens", inputTokens);

        Map<String, Object> runtime = sessionChatRuntime(effectiveSessionId, body, response);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("object", "hermes.session.chat.completion");
        envelope.put("session_id", effectiveSessionId.toString());
        envelope.put("message", message);
        envelope.put("usage", usage);
        envelope.put("runtime", runtime);

        // Compatibility fields for existing Java-agent clients of this endpoint.
        envelope.put("sessionId", effectiveSessionId);
        envelope.put("requestedSessionId", requestedSessionId);
        envelope.put("content", content);
        envelope.put("toolCalls", response.toolCalls());
        envelope.put("tool_calls", response.toolCalls());
        envelope.put("completed", response.completed());
        envelope.put("memoryUpdated", response.memoryUpdated());
        envelope.put("modelUsed", response.modelUsed());
        envelope.put("contextTokens", response.contextTokens());
        envelope.put("contextLength", response.contextLength());
        return envelope;
    }

    private Map<String, Object> sessionChatRuntime(UUID sessionId, SessionChatRequest body, ChatResponseDto response) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        if (response != null && response.modelUsed() != null && !response.modelUsed().isBlank()) {
            runtime.put("model", response.modelUsed());
        }
        if (body != null && OpenAiRequestBooleans.coerce(body.requireModelLock(), false)) {
            ModelLockSelection selection = parseModelLockSelection(body.model(), body.provider());
            Map<String, Object> modelOptions = modelLockOptions(body);
            runtime.putAll(modelLockRuntime(selection, modelOptions));
            if (response != null && response.modelUsed() != null && !response.modelUsed().isBlank()) {
                runtime.put("model", response.modelUsed());
            }
        } else if (bodyHasNoRuntimeSelection(body)) {
            if (!applyPersistedBrowserModelLockRuntime(runtime, sessionId, body, response)) {
                applyStoredSessionModelRuntime(runtime, sessionId, body);
            }
        } else if (body != null) {
            ModelRequestOptions options = sessionChatOptions(sessionId, body);
            if (options.provider() != null && !options.provider().isBlank()) {
                runtime.put("provider", options.provider());
            }
            if (!runtime.containsKey("model") && options.modelName() != null && !options.modelName().isBlank()) {
                runtime.put("model", options.modelName());
            }
            Map<String, Object> modelOptions = modelLockOptions(body);
            if (!modelOptions.isEmpty()) {
                runtime.put("model_options", modelOptions);
            }
        }
        return runtime;
    }

    private boolean bodyHasNoRuntimeSelection(SessionChatRequest body) {
        if (body == null) {
            return true;
        }
        return blankToNull(body.model()) == null
            && blankToNull(body.provider()) == null;
    }

    private boolean applyPersistedBrowserModelLockRuntime(
            Map<String, Object> runtime,
            UUID sessionId,
            SessionChatRequest body,
            ChatResponseDto response) {
        SessionEntity entity = findSessionOrNull(sessionId);
        if (entity == null || !hasConfirmedBrowserModelLock(entity)) {
            return false;
        }
        String provider = cliStateValue(entity, MODEL_LOCK_RUNTIME_PROVIDER_KEY);
        String model = cliStateValue(entity, MODEL_LOCK_RUNTIME_MODEL_KEY);
        if (provider == null) {
            provider = blankToNull(entity.getModelProvider());
        }
        if (model == null) {
            model = response != null && response.modelUsed() != null && !response.modelUsed().isBlank()
                ? response.modelUsed().trim()
                : blankToNull(entity.getModelName());
        }
        if (provider != null) {
            runtime.put("provider", provider);
        }
        if (model != null) {
            runtime.put("model", model);
        }
        runtime.put("route_source", "session_model_lock");
        Map<String, Object> modelOptions = modelLockOptions(body);
        if (modelOptions.isEmpty()) {
            modelOptions = storedBrowserModelOptions(entity);
        }
        if (!modelOptions.isEmpty()) {
            runtime.put("model_options", modelOptions);
        }

        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("provider", cliStateValue(entity, MODEL_LOCK_REQUESTED_PROVIDER_KEY));
        requested.put("model", cliStateValue(entity, MODEL_LOCK_REQUESTED_MODEL_KEY));
        runtime.put("requested", requested);
        runtime.put("model_lock", "confirmed");
        return true;
    }

    private void applyStoredSessionModelRuntime(
            Map<String, Object> runtime,
            UUID sessionId,
            SessionChatRequest body) {
        String storedModel = storedSessionModel(sessionId);
        if (storedModel == null) {
            return;
        }
        AgentProperties.ApiProperties.ModelRouteProperties route =
            OpenAiModelRouting.routedRoute(properties.getApi(), storedModel);
        if (route == null) {
            return;
        }
        String provider = blankToNull(route.getProvider());
        String model = blankToNull(route.getModel());
        if (provider != null) {
            runtime.put("provider", provider);
        }
        if (model != null) {
            runtime.put("model", model);
        }
        runtime.put("route_source", "model_routes");
        Map<String, Object> modelOptions = modelLockOptions(body);
        if (!modelOptions.isEmpty()) {
            runtime.put("model_options", modelOptions);
        }
    }

    private void persistBrowserModelConfig(
            UUID sessionId,
            ModelLockSelection selection,
            boolean confirmed,
            Map<String, Object> modelOptions) {
        SessionEntity entity = findSessionOrNull(sessionId);
        if (entity == null) {
            return;
        }
        markBrowserModelConfig(entity, selection, confirmed, modelOptions);
        sessionRepository.save(entity);
    }

    private void markBrowserModelConfig(
            SessionEntity entity,
            ModelLockSelection selection,
            boolean confirmed,
            Map<String, Object> modelOptions) {
        if (entity == null || selection == null) {
            return;
        }
        setCliStateValue(entity, MODEL_CONFIG_PRESENT_KEY, "true");
        setCliStateValue(entity, MODEL_LOCK_CONFIRMED_KEY, Boolean.toString(confirmed));
        setCliStateValue(entity, MODEL_LOCK_REQUESTED_MODEL_KEY, selection.requestedModel());
        setCliStateValue(entity, MODEL_LOCK_REQUESTED_PROVIDER_KEY, selection.requestedProvider());
        setCliStateValue(entity, MODEL_LOCK_RUNTIME_MODEL_KEY, selection.runtimeModel());
        setCliStateValue(entity, MODEL_LOCK_RUNTIME_PROVIDER_KEY, selection.runtimeProvider());
        setCliStateValue(entity, MODEL_LOCK_ROUTE_SOURCE_KEY, selection.routeSource());
        storeBrowserModelOptions(entity, modelOptions);
        if (confirmed) {
            applyBrowserRuntimeOptions(entity, modelOptions);
        }
    }

    private void storeBrowserModelOptions(SessionEntity entity, Map<String, Object> modelOptions) {
        if (modelOptions == null || modelOptions.isEmpty()) {
            entity.removeCliStateValue(MODEL_LOCK_MODEL_OPTIONS_KEY);
            return;
        }
        try {
            entity.setCliStateValue(MODEL_LOCK_MODEL_OPTIONS_KEY, objectMapper.writeValueAsString(modelOptions));
        } catch (JsonProcessingException e) {
            log.debug("Failed to persist browser model options for session {}: {}", entity.getId(), e.getMessage());
            entity.removeCliStateValue(MODEL_LOCK_MODEL_OPTIONS_KEY);
        }
    }

    private void applyBrowserRuntimeOptions(SessionEntity entity, Map<String, Object> modelOptions) {
        Map<String, Object> options = modelOptions != null ? modelOptions : Map.of();
        setCliStateValue(entity, "reasoningEffort", OpenAiRequestModelOptions.reasoningEffort(options));
        Boolean fastMode = OpenAiRequestModelOptions.fastMode(options);
        setCliStateValue(entity, "fastMode", fastMode != null ? String.valueOf(fastMode) : null);
        Integer maxTokens = OpenAiRequestModelOptions.positiveIntOption(
            options, "max_completion_tokens", "maxCompletionTokens", "max_tokens", "maxTokens");
        setCliStateValue(entity, "maxTokens", maxTokens != null ? String.valueOf(maxTokens) : null);
    }

    private Map<String, Object> storedBrowserModelOptions(SessionEntity entity) {
        String raw = cliStateValue(entity, MODEL_LOCK_MODEL_OPTIONS_KEY);
        if (raw == null) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                raw,
                new TypeReference<LinkedHashMap<String, Object>>() {});
            return parsed != null ? parsed : Map.of();
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse browser model options for session {}: {}", entity.getId(), e.getMessage());
            return Map.of();
        }
    }

    private void setCliStateValue(SessionEntity entity, String key, String value) {
        if (value == null || value.isBlank()) {
            entity.removeCliStateValue(key);
        } else {
            entity.setCliStateValue(key, value);
        }
    }

    private SessionEntity findSessionOrNull(UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        java.util.Optional<SessionEntity> entity = sessionRepository.findById(sessionId);
        return entity != null ? entity.orElse(null) : null;
    }

    private boolean hasConfirmedBrowserModelLock(SessionEntity entity) {
        return Boolean.TRUE.equals(OpenAiRequestBooleans.coerceOptional(
            cliStateValue(entity, MODEL_LOCK_CONFIRMED_KEY)));
    }

    private boolean hasBrowserModelConfig(SessionEntity entity) {
        return Boolean.TRUE.equals(OpenAiRequestBooleans.coerceOptional(
            cliStateValue(entity, MODEL_CONFIG_PRESENT_KEY)));
    }

    private String cliStateValue(SessionEntity entity, String key) {
        if (entity == null || entity.getCliState() == null) {
            return null;
        }
        return blankToNull(entity.getCliState().get(key));
    }

    private ResponseEntity.BodyBuilder withSessionHeaders(
            ResponseEntity.BodyBuilder builder,
            UUID sessionId,
            String sessionKey) {
        builder.header(OpenAiSessionService.SESSION_ID_HEADER, sessionId.toString());
        if (sessionKey != null && !sessionKey.isBlank()) {
            builder.header(OpenAiSessionService.SESSION_KEY_HEADER, sessionKey);
        }
        return builder;
    }

    private ResponseEntity<Map<String, Object>> validateSessionImportPayload(String rawBody) {
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > SESSION_IMPORT_MAX_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("detail", "Session import payload is too large"));
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody == null ? "" : rawBody);
        } catch (JsonProcessingException e) {
            return badRequestDetail("Invalid session import payload");
        }
        if (node == null || !node.isObject()) {
            return badRequestDetail("Invalid session import payload");
        }
        JsonNode sessions = node.get("sessions");
        if (sessions == null || !sessions.isArray()) {
            return badRequestDetail("Invalid session import payload");
        }
        for (JsonNode session : sessions) {
            if (!session.isObject()) {
                return badRequestDetail("Invalid session import payload");
            }
        }
        JsonNode profile = node.get("profile");
        if (profile != null && !profile.isNull() && !profile.isTextual()) {
            return badRequestDetail("Invalid session import payload");
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> validateSessionPrunePayload(String rawBody) {
        JsonNode node;
        try {
            node = objectMapper.readTree(rawBody == null ? "" : rawBody);
        } catch (JsonProcessingException e) {
            return badRequestDetail("Invalid JSON in request body");
        }
        if (node == null || !node.isObject()) {
            return badRequestDetail("Request body must be a JSON object");
        }
        JsonNode olderThanDaysNode = node.get("older_than_days");
        if (olderThanDaysNode == null || olderThanDaysNode.isNull()) {
            return null;
        }
        Double olderThanDays = jsonDouble(olderThanDaysNode);
        if (olderThanDays == null) {
            return badRequestDetail("Invalid JSON in request body");
        }
        boolean hasWindow = node.hasNonNull("started_before") || node.hasNonNull("started_after");
        if (olderThanDays < 1.0d && !hasWindow) {
            return badRequestDetail("older_than_days must be >= 1");
        }
        return null;
    }

    private ImportSessionRow importSessionRow(JsonNode sessionNode, int index, String profile) {
        String externalId = optionalText(sessionNode.get("id"));
        if (externalId == null) {
            return ImportSessionRow.error(index, "session id is required");
        }
        JsonNode messagesNode = sessionNode.get("messages");
        if (messagesNode != null && !messagesNode.isArray()) {
            return ImportSessionRow.error(index, "messages must be a list");
        }

        UUID sessionId = importedSessionUuid(profile, externalId);
        Instant startedAt = firstInstant(
            instantFromJson(sessionNode.get("started_at")),
            instantFromJson(sessionNode.get("created_at")),
            Instant.now());
        Instant endedAt = instantFromJson(sessionNode.get("ended_at"));
        String endReason = optionalText(sessionNode.get("end_reason"));
        List<MessageEntity> messages = importedMessages(sessionId, messagesNode, startedAt);
        Instant lastActive = firstInstant(endedAt, lastMessageInstant(messages), startedAt);

        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId(firstNonBlank(optionalText(sessionNode.get("user_id")), AgentProperties.DEFAULT_USER_ID));
        entity.setTitle(firstNonBlank(optionalText(sessionNode.get("title")), externalId));
        entity.setModelProvider(firstNonBlank(optionalText(sessionNode.get("provider")), "openai-compatible"));
        entity.setModelName(firstNonBlank(
            optionalText(sessionNode.get("model_name")),
            optionalText(sessionNode.get("model")),
            optionalText(sessionNode.get("model_id")),
            ""));
        entity.setSource(firstNonBlank(optionalText(sessionNode.get("source")), "cli"));
        entity.setProfile(profile);
        entity.setEndReason(endReason);
        entity.setSessionStatus(endedAt != null || endReason != null ? "ended" : "active");
        entity.setCreatedAt(startedAt);
        entity.setUpdatedAt(firstInstant(endedAt, lastActive, startedAt));
        entity.setLastActive(lastActive);
        entity.setMessageCount(messages.size());
        entity.setPreview(importedPreview(messages));
        entity.setPinned(optionalBoolean(sessionNode.get("pinned")));
        entity.setArchived(optionalBoolean(sessionNode.get("archived")));
        entity.setHidden(optionalBoolean(sessionNode.get("hidden")));
        entity.setSystemPrompt(optionalText(sessionNode.get("system_prompt")));
        entity.setCliStateValue("imported_external_id", externalId);
        return ImportSessionRow.valid(externalId, entity, messages);
    }

    private List<MessageEntity> importedMessages(UUID sessionId, JsonNode messagesNode, Instant startedAt) {
        if (messagesNode == null || messagesNode.isNull() || messagesNode.isEmpty()) {
            return List.of();
        }
        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < messagesNode.size(); i++) {
            JsonNode node = messagesNode.get(i);
            if (!node.isObject()) {
                continue;
            }
            MessageEntity entity = new MessageEntity();
            entity.setId(UUID.nameUUIDFromBytes(
                ("hermes-session-import-message\n" + sessionId + "\n" + i).getBytes(StandardCharsets.UTF_8)));
            entity.setSessionId(sessionId);
            entity.setRole(firstNonBlank(optionalText(node.get("role")), "user"));
            entity.setContent(importedContent(node.get("content")));
            entity.setToolCallId(optionalText(node.get("tool_call_id")));
            entity.setToolCallName(optionalText(node.get("tool_call_name")));
            entity.setToolCallArguments(optionalText(node.get("tool_call_arguments")));
            entity.setToolCalls(optionalJson(node.get("tool_calls")));
            entity.setTurnIndex(i);
            entity.setImageCount(0);
            entity.setActive(true);
            entity.setCompacted(false);
            entity.setCreatedAt(firstInstant(
                instantFromJson(node.get("timestamp")),
                instantFromJson(node.get("created_at")),
                startedAt.plusSeconds(i)));
            messages.add(entity);
        }
        return messages;
    }

    private UUID importedSessionUuid(String profile, String externalId) {
        try {
            return UUID.fromString(externalId);
        } catch (IllegalArgumentException ignored) {
            String scoped = firstNonBlank(profile, "default") + "\n" + externalId;
            return UUID.nameUUIDFromBytes(("hermes-session-import\n" + scoped).getBytes(StandardCharsets.UTF_8));
        }
    }

    private Instant lastMessageInstant(List<MessageEntity> messages) {
        Instant latest = null;
        for (MessageEntity message : messages) {
            if (message.getCreatedAt() != null && (latest == null || message.getCreatedAt().isAfter(latest))) {
                latest = message.getCreatedAt();
            }
        }
        return latest;
    }

    private String importedPreview(List<MessageEntity> messages) {
        for (MessageEntity message : messages) {
            if ("user".equalsIgnoreCase(message.getRole())) {
                String content = blankToNull(message.getContent());
                if (content != null) {
                    return content.length() > 200 ? content.substring(0, 197) + "..." : content;
                }
            }
        }
        return null;
    }

    private String importedContent(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private String optionalJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? blankToNull(node.asText()) : node.toString();
    }

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return blankToNull(node.asText());
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
    }

    private static boolean optionalBoolean(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            return Boolean.TRUE.equals(OpenAiRequestBooleans.coerceOptional(node.asText()));
        }
        return false;
    }

    private static Instant instantFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return Instant.ofEpochMilli(Math.round(node.asDouble() * 1000.0d));
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            if (value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (RuntimeException ignored) {
                try {
                    return Instant.ofEpochMilli(Math.round(Double.parseDouble(value) * 1000.0d));
                } catch (NumberFormatException ignoredToo) {
                    return null;
                }
            }
        }
        return null;
    }

    private PruneRequest pruneRequest(JsonNode root) {
        Instant startedBefore = instantFromJson(root.get("started_before"));
        Instant startedAfter = instantFromJson(root.get("started_after"));
        boolean hasWindow = startedBefore != null || startedAfter != null;
        boolean olderThanExplicit = root.has("older_than_days");
        Double olderThanDays = olderThanExplicit
            ? (root.get("older_than_days") == null || root.get("older_than_days").isNull()
                ? null
                : jsonDouble(root.get("older_than_days")))
            : 90.0d;
        boolean attrFiltersSet = Stream.of(
            "source", "title_like", "end_reason", "min_messages", "max_messages", "model_like", "user_id")
            .anyMatch(name -> meaningfulPruneValue(root.get(name)));
        if (hasWindow || (attrFiltersSet && !olderThanExplicit)) {
            olderThanDays = null;
        }
        Instant lastActiveBefore = olderThanDays != null
            ? Instant.now().minusMillis(Math.round(olderThanDays * 86_400_000.0d))
            : null;
        Integer minMessages = optionalInteger(root.get("min_messages"), "min_messages");
        Integer maxMessages = optionalInteger(root.get("max_messages"), "max_messages");
        return new PruneRequest(
            lastActiveBefore,
            startedBefore,
            startedAfter,
            optionalText(root.get("source")),
            optionalText(root.get("title_like")),
            optionalText(root.get("end_reason")),
            optionalText(root.get("user_id")),
            minMessages,
            maxMessages,
            optionalText(root.get("model_like")),
            optionalBoolean(root.get("include_archived")),
            optionalBoolean(root.get("dry_run")));
    }

    private Map<String, Object> pruneDryRunResponse(List<SessionEntity> candidates, long skippedOpen) {
        List<Map<String, Object>> sessions = (candidates == null ? List.<SessionEntity>of() : candidates).stream()
            .map(this::pruneCandidatePayload)
            .toList();
        List<Instant> lastActiveValues = (candidates == null ? List.<SessionEntity>of() : candidates).stream()
            .map(this::pruneCandidateLastActive)
            .filter(java.util.Objects::nonNull)
            .toList();
        List<Instant> startedValues = (candidates == null ? List.<SessionEntity>of() : candidates).stream()
            .map(SessionEntity::getCreatedAt)
            .filter(java.util.Objects::nonNull)
            .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("removed", 0);
        response.put("matched", sessions.size());
        response.put("skipped_open", skippedOpen);
        response.put("oldest_last_active", epochSecondsOrNull(firstSorted(lastActiveValues)));
        response.put("newest_last_active", epochSecondsOrNull(lastSorted(lastActiveValues)));
        response.put("oldest_started_at", epochSecondsOrNull(firstSorted(startedValues)));
        response.put("newest_started_at", epochSecondsOrNull(lastSorted(startedValues)));
        response.put("sessions", sessions);
        return response;
    }

    private Map<String, Object> pruneCandidatePayload(SessionEntity entity) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entity.getId().toString());
        row.put("source", entity.getSource());
        row.put("title", entity.getTitle());
        row.put("model", entity.getModelName());
        row.put("started_at", epochSecondsOrNull(entity.getCreatedAt()));
        row.put("last_active", epochSecondsOrNull(pruneCandidateLastActive(entity)));
        row.put("message_count", entity.getMessageCount() != null ? entity.getMessageCount() : 0);
        return row;
    }

    private Instant pruneCandidateLastActive(SessionEntity entity) {
        return firstInstant(entity.getLastActive(), entity.getUpdatedAt(), entity.getCreatedAt());
    }

    private static Long epochSecondsOrNull(Instant instant) {
        return instant != null ? instant.getEpochSecond() : null;
    }

    private static Instant firstSorted(List<Instant> values) {
        return values == null || values.isEmpty() ? null : values.stream().min(Instant::compareTo).orElse(null);
    }

    private static Instant lastSorted(List<Instant> values) {
        return values == null || values.isEmpty() ? null : values.stream().max(Instant::compareTo).orElse(null);
    }

    private String unsupportedPruneFilter(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        for (String field : List.of(
            "cwd_prefix", "provider", "chat_id", "chat_type", "branch_like",
            "min_tokens", "max_tokens", "min_cost", "max_cost", "min_tool_calls", "max_tool_calls")) {
            if (meaningfulPruneValue(root.get(field))) {
                return field;
            }
        }
        return null;
    }

    private static boolean meaningfulPruneValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return !node.asText().isBlank();
        }
        return true;
    }

    private static Integer optionalInteger(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new SessionRequestException(field + " must be an integer", null, HttpStatus.BAD_REQUEST);
            }
        }
        throw new SessionRequestException(field + " must be an integer", null, HttpStatus.BAD_REQUEST);
    }

    private <T> ParsedRequestBody<T> parseJsonObjectBody(String rawBody, Class<T> bodyType) {
        ParsedJsonNode parsedNode = parseJsonObjectNode(rawBody);
        if (parsedNode.error() != null) {
            return new ParsedRequestBody<>(null, parsedNode.error());
        }
        try {
            T body = objectMapper.readerFor(bodyType)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(parsedNode.node());
            return new ParsedRequestBody<>(body, null);
        } catch (IOException | IllegalArgumentException e) {
            return new ParsedRequestBody<>(
                null,
                sessionError(HttpStatus.BAD_REQUEST, "Invalid JSON in request body", null));
        }
    }

    private ParsedRequestBody<Map<String, Object>> parseJsonObjectMapBody(String rawBody) {
        ParsedJsonNode parsedNode = parseJsonObjectNode(rawBody);
        if (parsedNode.error() != null) {
            return new ParsedRequestBody<>(null, parsedNode.error());
        }
        Map<String, Object> body = objectMapper.convertValue(
            parsedNode.node(),
            new TypeReference<LinkedHashMap<String, Object>>() {});
        return new ParsedRequestBody<>(body, null);
    }

    private ParsedJsonNode parseJsonObjectNode(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return new ParsedJsonNode(
                null,
                sessionError(HttpStatus.BAD_REQUEST, "Invalid JSON in request body", null));
        }
        try {
            JsonNode node = objectMapper.readTree(rawBody);
            if (node == null || !node.isObject()) {
                return new ParsedJsonNode(
                    null,
                    sessionError(HttpStatus.BAD_REQUEST, "Request body must be a JSON object", null));
            }
            return new ParsedJsonNode(node, null);
        } catch (JsonProcessingException e) {
            return new ParsedJsonNode(
                null,
                sessionError(HttpStatus.BAD_REQUEST, "Invalid JSON in request body", null));
        }
    }

    private String parseSessionKeyHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!hasConfiguredApiKey()) {
            throw new SessionRequestException(
                "X-Hermes-Session-Key requires API key authentication. "
                    + "Configure API_SERVER_KEY to enable this feature.",
                null,
                HttpStatus.FORBIDDEN);
        }
        if (containsSessionHeaderControlCharacter(value)) {
            throw new SessionRequestException("Invalid session key", null, HttpStatus.BAD_REQUEST);
        }
        if (value.length() > MAX_SESSION_HEADER_LENGTH) {
            throw new SessionRequestException("Session key too long", null, HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private boolean hasConfiguredApiKey() {
        AgentProperties.SecurityProperties security = properties.getSecurity();
        return security != null && security.getApiKey() != null && !security.getApiKey().isBlank();
    }

    private boolean containsSessionHeaderControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\0') {
                return true;
            }
        }
        return false;
    }

    private UUID parseRequestedSessionId(CreateSessionBody body) {
        if (body == null || body.id() == null || body.id().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(body.id().trim());
        } catch (IllegalArgumentException e) {
            throw new SessionRequestException(
                "Invalid session ID",
                "invalid_session_id",
                HttpStatus.BAD_REQUEST);
        }
    }

    private UUID parseOptionalSessionId(String id, String sessionId) {
        String raw = id != null && !id.isBlank() ? id : sessionId;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid session ID", e);
        }
    }

    private static int parseHermesListQueryInt(String value, int defaultValue, int maxValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                return defaultValue;
            }
            return Math.min(parsed, maxValue);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int parseNonNegativeQueryInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            if (parsed < 0) {
                throw new IllegalArgumentException(field + " must be greater than or equal to 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
    }

    private static Double jsonDouble(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            double value = node.asDouble();
            return Double.isFinite(value) ? value : null;
        }
        if (node.isTextual()) {
            try {
                double value = Double.parseDouble(node.asText().trim());
                return Double.isFinite(value) ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String normalizeSessionSource(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (SESSION_SOURCES.contains(text)) {
            return "browser".equals(text) ? "hermes_browser" : text;
        }
        return "api_server";
    }

    private String cleanRuntimeId(Object value, String field) {
        if (!(value instanceof String raw)) {
            return null;
        }
        String cleaned = raw.trim();
        if (cleaned.isBlank()) {
            return null;
        }
        if (cleaned.length() > 200 || cleaned.indexOf('\r') >= 0
                || cleaned.indexOf('\n') >= 0 || cleaned.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " contains invalid characters");
        }
        return cleaned;
    }

    private boolean isAdvertisedModelAlias(String model) {
        if (model == null) {
            return false;
        }
        String advertised = OpenAiModelRouting.advertisedModel(properties);
        return advertised != null && advertised.equals(model);
    }

    private ModelLockSelection parseModelLockSelection(Object rawModel, Object rawProvider) {
        String requestedModel = cleanRuntimeId(rawModel, "model");
        String requestedProvider = cleanRuntimeId(rawProvider, "provider");
        OpenAiModelRouting.RequestedModel requested =
            OpenAiModelRouting.requestedModelAndProvider(requestedModel, requestedProvider);
        requestedModel = requested.model();
        requestedProvider = requested.provider();
        if (isAdvertisedModelAlias(requestedModel)) {
            requestedModel = null;
        }
        AgentProperties.ApiProperties.ModelRouteProperties route =
            OpenAiModelRouting.routedRoute(properties.getApi(), requestedModel);
        String runtimeModel = requestedModel;
        String runtimeProvider = requestedProvider;
        String routeSource = "global";
        if (route != null) {
            runtimeModel = cleanRuntimeId(route.getModel(), "model");
            String routeProvider = cleanRuntimeId(route.getProvider(), "provider");
            runtimeProvider = routeProvider != null ? routeProvider : requestedProvider;
            routeSource = "model_routes";
        } else if (requestedModel != null) {
            routeSource = "raw_request";
        }
        return new ModelLockSelection(
            requestedModel,
            requestedProvider,
            runtimeModel,
            runtimeProvider,
            routeSource);
    }

    private Map<String, Object> modelLockRuntime(ModelLockSelection selection, Map<String, Object> modelOptions) {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("provider", selection.runtimeProvider() != null ? selection.runtimeProvider() : "");
        runtime.put("model", selection.runtimeModel() != null ? selection.runtimeModel() : "");
        runtime.put("route_source", selection.routeSource() != null ? selection.routeSource() : "global");
        Map<String, Object> requested = new LinkedHashMap<>();
        requested.put("provider", selection.requestedProvider());
        requested.put("model", selection.requestedModel());
        runtime.put("requested", requested);
        runtime.put("model_lock", "accepted");
        if (modelOptions != null && !modelOptions.isEmpty()) {
            runtime.put("model_options", modelOptions);
        }
        return runtime;
    }

    private Map<String, Object> modelLockOptions(SessionModelLockBody body) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (body == null) {
            return options;
        }
        copyModelOptions(body.modelOptions(), options);
        if (body.reasoningEffort() != null) {
            options.put("reasoning_effort", body.reasoningEffort());
        }
        if (body.fastMode() != null) {
            options.put("fast_mode", body.fastMode());
        }
        if (body.maxCompletionTokens() != null) {
            options.put("max_completion_tokens", body.maxCompletionTokens());
        }
        return options;
    }

    private Map<String, Object> modelLockOptions(SessionChatRequest body) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (body == null) {
            return options;
        }
        copyModelOptions(body.modelOptions(), options);
        if (body.maxCompletionTokens() != null) {
            options.put("max_completion_tokens", body.maxCompletionTokens());
        }
        return options;
    }

    private Map<String, Object> modelOptionsMap(Object rawModelOptions) {
        Map<String, Object> options = new LinkedHashMap<>();
        copyModelOptions(rawModelOptions, options);
        return options;
    }

    private void copyModelOptions(Object rawModelOptions, Map<String, Object> target) {
        if (!(rawModelOptions instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                target.put(String.valueOf(key), value);
            }
        });
    }

    private void applySessionUpdate(SessionEntity entity, Map<String, Object> body) {
        List<String> unknownFields = body.keySet().stream()
            .filter(field -> !UPDATE_SESSION_FIELDS.contains(field))
            .sorted()
            .toList();
        if (!unknownFields.isEmpty()) {
            throw new SessionUpdateException(
                "Unsupported session fields: " + String.join(", ", unknownFields),
                "unsupported_session_field");
        }
        if (body.containsKey("title")) {
            String title = sanitizeTitle(body.get("title") == null ? "" : String.valueOf(body.get("title")));
            ensureTitleAvailable(title, entity.getId(), true);
            entity.setTitle(title);
        }
        if (body.containsKey("end_reason")) {
            setEndReasonIfTruthy(entity, body.get("end_reason"));
        } else if (body.containsKey("endReason")) {
            setEndReasonIfTruthy(entity, body.get("endReason"));
        }
        if (body.containsKey("pinned")) {
            entity.setPinned(requiredBoolean(body.get("pinned"), "pinned"));
        }
        if (body.containsKey("archived")) {
            entity.setArchived(requiredBoolean(body.get("archived"), "archived"));
        }
        if (body.containsKey("hidden")) {
            entity.setHidden(requiredBoolean(body.get("hidden"), "hidden"));
        }
        if (body.containsKey("unread")) {
            entity.setUnread(requiredBoolean(body.get("unread"), "unread"));
        }
    }

    private String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private void setEndReasonIfTruthy(SessionEntity entity, Object value) {
        if (truthyJsonValue(value)) {
            entity.setEndReason(optionalString(value));
        }
    }

    private void ensureTitleAvailable(String title, UUID currentSessionId, boolean includeTitleInMessage) {
        if (title == null) {
            return;
        }
        SessionEntity conflict = sessionRepository.findByTitle(title);
        if (conflict == null || currentSessionId != null && currentSessionId.equals(conflict.getId())) {
            return;
        }
        String message = includeTitleInMessage
            ? "Title '" + title + "' is already in use by session " + conflict.getId()
            : "Title already in use by session " + conflict.getId();
        throw new SessionRequestException(message, "invalid_title", HttpStatus.BAD_REQUEST);
    }

    private Boolean requiredBoolean(Object value, String field) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new SessionUpdateException("'" + field + "' must be a boolean", "invalid_session_field");
    }

    private static String sanitizeTitle(String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(title.length());
        boolean pendingSpace = false;
        for (int i = 0; i < title.length(); i++) {
            char ch = title.charAt(i);
            if (isStrippedTitleControl(ch)) {
                continue;
            }
            if (Character.isWhitespace(ch)) {
                pendingSpace = !cleaned.isEmpty();
                continue;
            }
            if (pendingSpace) {
                cleaned.append(' ');
                pendingSpace = false;
            }
            cleaned.append(ch);
        }
        String result = cleaned.toString().trim();
        if (result.isEmpty()) {
            return null;
        }
        if (result.length() > MAX_TITLE_LENGTH) {
            throw new SessionRequestException(
                "Title too long (" + result.length() + " chars, max " + MAX_TITLE_LENGTH + ")",
                "invalid_title",
                HttpStatus.BAD_REQUEST);
        }
        return result;
    }

    private static boolean isStrippedTitleControl(char ch) {
        return (ch >= 0x00 && ch <= 0x08)
            || ch == 0x0b
            || ch == 0x0c
            || (ch >= 0x0e && ch <= 0x1f)
            || ch == 0x7f
            || (ch >= '\u200b' && ch <= '\u200f')
            || (ch >= '\u2028' && ch <= '\u202e')
            || (ch >= '\u2060' && ch <= '\u2069')
            || ch == '\ufeff'
            || ch == '\ufffc'
            || (ch >= '\ufff9' && ch <= '\ufffb');
    }

    private static final class SessionUpdateException extends RuntimeException {
        private final String code;

        private SessionUpdateException(String message, String code) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }

    private static final class SessionRequestException extends RuntimeException {
        private final String code;
        private final HttpStatus status;

        private SessionRequestException(String message, String code, HttpStatus status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        private String code() {
            return code;
        }

        private HttpStatus status() {
            return status;
        }
    }

    private record ModelLockSelection(
        String requestedModel,
        String requestedProvider,
        String runtimeModel,
        String runtimeProvider,
        String routeSource
    ) {}

    private record ParsedJsonNode(
        JsonNode node,
        ResponseEntity<Map<String, Object>> error
    ) {}

    private record ParsedRequestBody<T>(
        T body,
        ResponseEntity<Map<String, Object>> error
    ) {}

    private record ArchivedFilter(boolean includeArchived, boolean archivedOnly) {}

    private record ImportSessionRow(
        String externalId,
        SessionEntity entity,
        List<MessageEntity> messages,
        Map<String, Object> error
    ) {
        static ImportSessionRow valid(String externalId, SessionEntity entity, List<MessageEntity> messages) {
            return new ImportSessionRow(externalId, entity, messages, null);
        }

        static ImportSessionRow error(int index, String error) {
            return new ImportSessionRow(null, null, List.of(), Map.of("index", index, "error", error));
        }
    }

    private record PruneRequest(
        Instant lastActiveBefore,
        Instant startedBefore,
        Instant startedAfter,
        String source,
        String titleLike,
        String endReason,
        String userId,
        Integer minMessages,
        Integer maxMessages,
        String modelLike,
        boolean includeArchived,
        boolean dryRun
    ) {}

    private record MessageDisplayKey(
        String role,
        String content,
        String logicalPosition,
        String toolCallId,
        String toolCalls,
        String toolName
    ) {}

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String blankToNull(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private String resolvePathProfileScope(String pathProfile) {
        String raw = blankToNull(pathProfile);
        if (raw == null) {
            return null;
        }
        String profile = normalizeRequestedProfile(raw);
        if (!profileService.knownProfile(profile)) {
            throw new SessionRequestException("Unknown profile: " + profile, "profile_not_found", HttpStatus.NOT_FOUND);
        }
        return profile;
    }

    private String resolveMutationProfileScope(String pathProfile, Object bodyProfile) {
        String pathScope = resolvePathProfileScope(pathProfile);
        String bodyScope = null;
        if (bodyProfile != null) {
            if (!(bodyProfile instanceof String)) {
                throw new SessionRequestException("profile must be a string", "invalid_profile", HttpStatus.BAD_REQUEST);
            }
            bodyScope = normalizeRequestedProfile((String) bodyProfile);
            if (!profileService.knownProfile(bodyScope)) {
                throw new SessionRequestException("Unknown profile: " + bodyScope, "profile_not_found", HttpStatus.NOT_FOUND);
            }
        }
        if (pathScope != null && bodyScope != null && !pathScope.equals(bodyScope)) {
            throw new SessionRequestException("profile in request body does not match route profile",
                "profile_mismatch", HttpStatus.BAD_REQUEST);
        }
        return firstNonBlank(pathScope, bodyScope, "default");
    }

    private ResponseEntity<Map<String, Object>> rejectProfileMismatch(
            String pathProfile,
            SessionEntity entity,
            UUID sessionId) {
        String requestedProfile;
        try {
            requestedProfile = resolvePathProfileScope(pathProfile);
        } catch (SessionRequestException e) {
            return sessionError(e.status(), e.getMessage(), e.code());
        }
        if (requestedProfile == null) {
            return null;
        }
        if (!requestedProfile.equals(entityProfile(entity))) {
            return sessionNotFound(sessionId);
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> rejectProfileMismatchIfScoped(String pathProfile, UUID sessionId) {
        if (blankToNull(pathProfile) == null) {
            return null;
        }
        SessionEntity entity = sessionRepository.findById(sessionId).orElse(null);
        if (entity == null) {
            return sessionNotFound(sessionId);
        }
        return rejectProfileMismatch(pathProfile, entity, sessionId);
    }

    private String normalizeRequestedProfile(String raw) {
        try {
            String profile = profileService.normalizeProfileName(raw);
            profileService.validateProfileName(profile);
            return profile;
        } catch (IllegalArgumentException e) {
            throw new SessionRequestException(e.getMessage(), "invalid_profile", HttpStatus.BAD_REQUEST);
        }
    }

    private static String entityProfile(SessionEntity entity) {
        String profile = entity != null ? blankToNull(entity.getProfile()) : null;
        return profile != null ? profile : "default";
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
            String includeArchivedSnake) {
        String value = blankToNull(archived);
        if (value == null) {
            return anyTruthy(includeArchived, includeArchivedSnake)
                ? new ArchivedFilter(true, false)
                : new ArchivedFilter(false, false);
        }
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "exclude" -> new ArchivedFilter(false, false);
            case "include" -> new ArchivedFilter(true, false);
            case "only" -> new ArchivedFilter(true, true);
            default -> throw new SessionRequestException(
                "archived must be one of: exclude, only, include",
                "invalid_archived_filter",
                HttpStatus.BAD_REQUEST);
        };
    }

    private static String parseSessionListOrder(String order) {
        String value = blankToNull(order);
        if (value == null) {
            return "recent";
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "created", "recent" -> value.toLowerCase(Locale.ROOT);
            default -> "recent";
        };
    }

    private static List<SessionEntity> nullToEmpty(List<SessionEntity> entities) {
        return entities == null ? List.of() : entities;
    }

    private static List<UUID> parseUuidList(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new java.util.LinkedHashSet<>();
        for (String rawId : rawIds) {
            if (rawId == null || rawId.isBlank()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(rawId.trim()));
            } catch (IllegalArgumentException ignored) {
                // Hermes bulk-delete silently skips stray non-session IDs.
            }
        }
        return List.copyOf(ids);
    }

    private int deleteSessionsByIds(List<UUID> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return 0;
        }
        List<UUID> existingIds = sessionRepository.findExistingIds(requestedIds);
        if (existingIds == null || existingIds.isEmpty()) {
            return 0;
        }
        sessionRepository.orphanChildrenOf(existingIds);
        messageRepository.deleteBySessionIdIn(existingIds);
        sessionRepository.deleteAllByIdInBatch(existingIds);
        existingIds.forEach(id -> eventPublisher.publishEvent(new SessionDeletedEvent(id)));
        return existingIds.size();
    }

    private Map<String, Object> sessionCountsBySource() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Object[] row : sessionRepository.countTopLevelSessionsBySource()) {
            if (row == null || row.length < 2) {
                continue;
            }
            String source = row[0] instanceof String value && !value.isBlank() ? value : "unknown";
            Object count = row[1] instanceof Number number ? number.longValue() : row[1];
            counts.put(source, count);
        }
        return counts;
    }

    private List<MessageEntity> pageDisplayMessages(UUID sessionId, int limit, int offset, boolean latestPage) {
        List<MessageEntity> messages = dedupeDisplayMessages(
            messageRepository.findDisplayBySessionIdOrderByCreatedAtAsc(sessionId));
        if (latestPage) {
            Collections.reverse(messages);
        }
        int from = Math.min(offset, messages.size());
        int to = Math.min(messages.size(), from + limit);
        List<MessageEntity> page = new ArrayList<>(messages.subList(from, to));
        if (latestPage) {
            Collections.reverse(page);
        }
        return page;
    }

    private List<MessageEntity> dedupeDisplayMessages(List<MessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Map<MessageDisplayKey, MessageEntity> seen = new LinkedHashMap<>();
        for (MessageEntity message : messages) {
            if (message == null) {
                continue;
            }
            MessageDisplayKey key = new MessageDisplayKey(
                message.getRole(),
                message.getContent(),
                displayLogicalPosition(message),
                message.getToolCallId(),
                message.getToolCalls(),
                message.getToolCallName());
            MessageEntity current = seen.get(key);
            if (current == null || preferDisplayRow(message, current)) {
                seen.put(key, message);
            }
        }
        return seen.values().stream()
            .sorted((left, right) -> {
                int byTime = nullSafeInstant(left.getCreatedAt()).compareTo(nullSafeInstant(right.getCreatedAt()));
                if (byTime != 0) {
                    return byTime;
                }
                return String.valueOf(left.getId()).compareTo(String.valueOf(right.getId()));
            })
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private boolean preferDisplayRow(MessageEntity candidate, MessageEntity current) {
        boolean candidateActive = !Boolean.FALSE.equals(candidate.getActive());
        boolean currentActive = !Boolean.FALSE.equals(current.getActive());
        if (candidateActive != currentActive) {
            return candidateActive;
        }
        int byTime = nullSafeInstant(candidate.getCreatedAt()).compareTo(nullSafeInstant(current.getCreatedAt()));
        if (byTime != 0) {
            return byTime > 0;
        }
        return String.valueOf(candidate.getId()).compareTo(String.valueOf(current.getId())) > 0;
    }

    private static String displayLogicalPosition(MessageEntity message) {
        Integer turnIndex = message.getTurnIndex();
        if (turnIndex != null && turnIndex > 0) {
            return "turn:" + turnIndex;
        }
        return "time:" + nullSafeInstant(message.getCreatedAt());
    }

    private static Instant nullSafeInstant(Instant value) {
        return value != null ? value : Instant.EPOCH;
    }

    private Map<String, Object> sessionEnvelope(Map<String, Object> payload) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.putAll(payload);
        response.put("object", "hermes.session");
        response.put("session", payload);
        return response;
    }

    private ResponseEntity<Map<String, Object>> sessionNotFound(UUID sessionId) {
        return sessionError(HttpStatus.NOT_FOUND, "Session not found: " + sessionId, "session_not_found");
    }

    private ResponseEntity<Map<String, Object>> sessionError(HttpStatus status, String message, String code) {
        return sessionError(status, message, code, null);
    }

    private ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
            .body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> unprocessable(String detail) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("detail", detail, "error", detail));
    }

    private ResponseEntity<Map<String, Object>> badRequestDetail(String detail) {
        return ResponseEntity.badRequest().body(Map.of("detail", detail));
    }

    private ResponseEntity<Map<String, Object>> concurrencyLimitedSessionResponse() {
        int limit = runAdmissionService.maxConcurrentRuns();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", "Too many concurrent runs (max " + limit + ")");
        error.put("type", "rate_limit_error");
        error.put("param", null);
        error.put("code", "rate_limit_exceeded");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("error", error));
    }

    private ResponseEntity<Map<String, Object>> sessionError(HttpStatus status, String message, String code, String param) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", "invalid_request_error");
        error.put("param", param);
        error.put("code", code);
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("error", error));
    }

    private Map<String, Object> toSessionPayload(SessionSummaryDto session) {
        Map<String, Object> response = toSessionPayload(
            session.id(), session.userId(), session.title(), session.modelName());
        if (session.createdAt() != null) {
            response.put("started_at", epochSeconds(session.createdAt()));
        }
        if (session.updatedAt() != null) {
            response.put("last_active", epochSeconds(session.updatedAt()));
            response.put("is_active", sessionIsActive(session.updatedAt(), null));
        }
        if (session.parentSessionId() != null) {
            response.put("parent_session_id", session.parentSessionId().toString());
        }
        return response;
    }

    private Map<String, Object> toSessionPayload(SessionEntity entity) {
        Map<String, Object> response = toSessionPayload(
            entity.getId(), entity.getUserId(), entity.getTitle(), entity.getModelName());
        String profile = entityProfile(entity);
        response.put("source", entity.getSource() != null && !entity.getSource().isBlank()
            ? entity.getSource() : "api_server");
        if (entity.getCreatedAt() != null) {
            response.put("started_at", epochSeconds(entity.getCreatedAt()));
        }
        Instant endedAt = endedAt(entity);
        response.put("ended_at", endedAt != null ? epochSeconds(endedAt) : null);
        if (entity.getEndReason() != null) {
            response.put("end_reason", entity.getEndReason());
        }
        response.put("message_count", entity.getMessageCount() != null ? entity.getMessageCount() : 0);
        if (entity.getParentSessionId() != null) {
            response.put("parent_session_id", entity.getParentSessionId().toString());
        }
        Instant lastActive = entity.getLastActive() != null ? entity.getLastActive() : entity.getUpdatedAt();
        if (lastActive != null) {
            response.put("last_active", epochSeconds(lastActive));
        }
        response.put("is_active", sessionIsActive(lastActive, endedAt));
        if (entity.getPreview() != null) {
            response.put("preview", entity.getPreview());
        }
        response.put("pinned", Boolean.TRUE.equals(entity.getPinned()));
        response.put("archived", Boolean.TRUE.equals(entity.getArchived()));
        response.put("hidden", Boolean.TRUE.equals(entity.getHidden()));
        response.put("profile", profile);
        response.put("is_default_profile", "default".equals(profile));
        response.put("has_system_prompt", entity.getSystemPrompt() != null && !entity.getSystemPrompt().isBlank());
        response.put("has_model_config", hasBrowserModelConfig(entity));
        return response;
    }

    private Map<String, Object> toSessionPayload(UUID id, String userId, String title, String model) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id.toString());
        response.put("source", "api_server");
        response.put("user_id", userId);
        response.put("model", blankToNull(model));
        response.put("title", title);
        response.put("started_at", 0L);
        response.put("ended_at", null);
        response.put("last_active", 0L);
        response.put("is_active", false);
        response.put("message_count", 0);
        response.put("tool_call_count", 0);
        response.put("input_tokens", 0);
        response.put("output_tokens", 0);
        response.put("preview", null);
        response.put("pinned", false);
        response.put("archived", false);
        response.put("hidden", false);
        response.put("profile", "default");
        response.put("is_default_profile", true);
        response.put("has_system_prompt", false);
        response.put("has_model_config", false);
        return response;
    }

    private static long epochSeconds(Instant instant) {
        return instant.getEpochSecond();
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

    private Map<String, Object> toMessageResponse(MessageEntity msg) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", msg.getId().toString());
        response.put("session_id", msg.getSessionId().toString());
        response.put("role", msg.getRole());
        response.put("content", msg.getContent());
        if (msg.getToolCallName() != null) {
            response.put("tool_name", msg.getToolCallName());
        }
        if (msg.getToolCallId() != null) {
            response.put("tool_call_id", msg.getToolCallId());
        }
        List<Map<String, Object>> toolCalls = toolCallsResponse(msg);
        if (!toolCalls.isEmpty()) {
            response.put("tool_calls", toolCalls);
        }
        if (msg.getCreatedAt() != null) {
            response.put("timestamp", epochSeconds(msg.getCreatedAt()));
        }
        return response;
    }

    private List<Map<String, Object>> toolCallsResponse(MessageEntity msg) {
        if (!"assistant".equals(msg.getRole())) {
            return List.of();
        }
        if (msg.getToolCalls() != null && !msg.getToolCalls().isBlank()) {
            try {
                List<Map<String, Object>> toolCalls = objectMapper.readValue(
                    msg.getToolCalls(),
                    new TypeReference<List<Map<String, Object>>>() {});
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    return toolCalls;
                }
            } catch (JsonProcessingException e) {
                log.debug("Failed to parse stored tool_calls for message {}: {}", msg.getId(), e.getMessage());
            }
        }
        if (msg.getToolCallName() == null) {
            return List.of();
        }
        return List.of(Map.of(
            "id", msg.getToolCallId() != null ? msg.getToolCallId() : "",
            "type", "function",
            "function", Map.of(
                "name", msg.getToolCallName(),
                "arguments", msg.getToolCallArguments() != null ? msg.getToolCallArguments() : ""
            )
        ));
    }
}
