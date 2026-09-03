package com.azhukov.agent.api;

import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.HttpEventPlatformAdapter;
import com.azhukov.agent.gateway.model.Platform;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping({"/api/platforms", "/p/{profile}/api/platforms"})
@RequiredArgsConstructor
public class PlatformEventsController {

    private final GatewayRoutingService gatewayRoutingService;
    private final ObjectMapper objectMapper;

    @PostMapping("/{platform}/events")
    public ResponseEntity<Map<String, Object>> platformEvent(
        @PathVariable String platform,
        @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
        @RequestBody(required = false) String body
    ) {
        String normalized = normalizePlatform(platform);
        if (normalized == null) {
            return error(HttpStatus.BAD_REQUEST, "Invalid platform name", "invalid_platform");
        }

        Platform platformEnum = toPlatform(normalized);
        if (platformEnum == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "Platform adapter is not connected", "platform_unavailable");
        }

        Optional<BasePlatformAdapter> adapter = gatewayRoutingService.adapterFor(platformEnum);
        if (adapter.isEmpty()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "Platform adapter is not connected", "platform_unavailable");
        }
        if (!(adapter.get() instanceof HttpEventPlatformAdapter httpAdapter)) {
            return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Platform adapter does not support HTTP events",
                "platform_http_events_unsupported");
        }

        HttpEventPlatformAdapter.VerificationResult verification;
        try {
            verification = httpAdapter.verifyHttpEventRequest(authorizationHeader != null ? authorizationHeader : "");
        } catch (Exception e) {
            verification = HttpEventPlatformAdapter.VerificationResult.denied("platform_event_verifier_error");
        }
        if (verification == null || !verification.ok()) {
            return error(
                HttpStatus.UNAUTHORIZED,
                "Invalid platform event authorization",
                verification != null && verification.code() != null && !verification.code().isBlank()
                    ? verification.code()
                    : "invalid_platform_event_authorization");
        }

        Object payload;
        try {
            payload = objectMapper.readValue(body != null ? body : "", Object.class);
        } catch (JsonProcessingException e) {
            return error(HttpStatus.BAD_REQUEST, "Invalid JSON in platform event", "invalid_json");
        }
        if (!(payload instanceof Map<?, ?> rawMap)) {
            return error(HttpStatus.BAD_REQUEST, "Platform event must be a JSON object", "invalid_request");
        }

        Map<String, Object> eventPayload = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            eventPayload.put(String.valueOf(entry.getKey()), entry.getValue());
        }

        Object result;
        try {
            result = httpAdapter.dispatchHttpEvent(eventPayload);
        } catch (Exception e) {
            return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Platform event dispatch failed",
                "platform_event_dispatch_failed",
                "server_error");
        }
        if (result instanceof Map<?, ?> resultMap) {
            Map<String, Object> response = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                response.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.ok(Map.of());
    }

    private String normalizePlatform(String platform) {
        String normalized = platform == null
            ? ""
            : platform.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        if (normalized.isBlank() || !normalized.matches("[a-z0-9_]+")) {
            return null;
        }
        return normalized;
    }

    private Platform toPlatform(String normalized) {
        try {
            return Platform.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, String code) {
        return error(status, message, code, "invalid_request_error");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, String code, String type) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("param", null);
        error.put("code", code);
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
}
