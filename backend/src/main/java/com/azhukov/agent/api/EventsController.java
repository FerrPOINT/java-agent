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
            profileScope = EventSubscription.resolveProfileScope(profileService, pathProfile, queryProfile);
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
            afterCursor = EventSubscription.parseCursor(after != null ? after : cursor);
            boundedLimit = EventSubscription.parseLimit(limit);
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
