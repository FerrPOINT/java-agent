package com.azhukov.agent.api;

import com.azhukov.agent.tools.memory.SessionSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/sessions", "/api/v2/sessions"})
@RequiredArgsConstructor
@Tag(name = "Session Search", description = "Session search endpoint compatible with Hermes")
public class SessionSearchController {

    private final SessionSearchService sessionSearchService;

    @Operation(summary = "Search sessions by title, ID, or message content")
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchSessions(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String profile,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String sources,
            @RequestParam(name = "exclude_sources", required = false) String excludeSources,
            @RequestParam(name = "excludeSources", required = false) String excludeSourcesCamel) {
        Integer parsedLimit;
        try {
            parsedLimit = parseOptionalInteger(limit);
        } catch (NumberFormatException e) {
            return error(HttpStatus.BAD_REQUEST, "limit must be an integer");
        }
        if (q == null || q.isBlank()) {
            return ResponseEntity.ok(Map.of("results", List.of()));
        }
        try {
            return ResponseEntity.ok(sessionSearchService.webSearch(
                q,
                parsedLimit,
                profile,
                source,
                sources,
                firstNonBlank(excludeSources, excludeSourcesCamel)));
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Search failed");
        }
    }

    private static Integer parseOptionalInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Integer.parseInt(value.trim());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second != null && !second.isBlank() ? second.trim() : null;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", message);
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }
}
