package com.azhukov.agent.api;

import com.azhukov.agent.service.EventService;
import com.azhukov.agent.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/events", "/p/{profile}/api/events"})
@RequiredArgsConstructor
public class EventsController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final EventService eventService;
    private final ProfileService profileService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> events(
        @PathVariable(name = "profile", required = false) String pathProfile,
        @RequestParam(name = "profile", required = false) String queryProfile,
        @RequestParam(name = "after", required = false) String after,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", required = false) String limit
    ) {
        String profileScope;
        try {
            profileScope = resolveProfileScope(pathProfile, queryProfile);
        } catch (FileNotFoundException e) {
            return notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IOException e) {
            return serverError(e.getMessage());
        }

        long afterCursor;
        int boundedLimit;
        try {
            afterCursor = parseCursor(after != null ? after : cursor);
            boundedLimit = parseLimit(limit);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        List<EventService.EventEnvelope> events = eventService.replay(profileScope, afterCursor, boundedLimit);
        long nextCursor = events.isEmpty()
            ? afterCursor
            : events.get(events.size() - 1).cursor();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("object", "event_list");
        response.put("events", events);
        response.put("data", events);
        response.put("profile", profileScope != null ? profileScope : "all");
        response.put("replay_after", afterCursor);
        response.put("next_cursor", nextCursor);
        response.put("latest_cursor", eventService.latestCursor());
        response.put("limit", boundedLimit);
        return ResponseEntity.ok(response);
    }

    private String resolveProfileScope(String pathProfile, String queryProfile) throws IOException {
        if (hasText(pathProfile)) {
            return requireKnownProfile(pathProfile);
        }
        if (!hasText(queryProfile) || "all".equalsIgnoreCase(queryProfile.trim())) {
            return null;
        }
        return requireKnownProfile(queryProfile);
    }

    private String requireKnownProfile(String rawProfile) throws IOException {
        String profile = profileService.normalizeProfileName(rawProfile);
        profileService.validateProfileName(profile);
        profileService.requireKnownProfile(profile);
        return profile;
    }

    private static long parseCursor(String raw) {
        if (!hasText(raw)) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed < 0L) {
                throw new IllegalArgumentException("cursor must be greater than or equal to 0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cursor must be an integer");
        }
    }

    private static int parseLimit(String raw) {
        if (!hasText(raw)) {
            return DEFAULT_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 1 || parsed > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be an integer");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.badRequest().body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> serverError(String detail) {
        String message = detail != null && !detail.isBlank() ? detail : "Failed to read events";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("detail", message, "error", message));
    }
}
