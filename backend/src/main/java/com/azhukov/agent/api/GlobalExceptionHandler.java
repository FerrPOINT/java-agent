package com.azhukov.agent.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Preserves protocol-specific errors while keeping unexpected diagnostics out of client responses. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public GlobalExceptionHandler(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Convenience ctor for tests/standalone MockMvc setups. */
    public GlobalExceptionHandler() {
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }

    // ExceptionHandlerExceptionResolver does not support SseEmitter return values.
    // A terminal event is synchronous and must retain its error status and SSE framing.
    private ResponseEntity<String> sseErrorEvent(HttpStatus status, String type, String message) {
        String json;
        try {
            // rev-75: use ObjectMapper for proper JSON escaping instead of manual
            // string concatenation — the old code only escaped " and \n, missing
            // backslash, \t, \r, and control characters, producing invalid JSON
            // on messages containing those bytes.
            Map<String, String> payload = Map.of("type", type, "error", message);
            json = objectMapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Cannot serialize terminal streaming error", e);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            json = "{\"type\":\"internal\",\"error\":\"Internal server error\"}";
        }
        return ResponseEntity.status(status).contentType(MediaType.TEXT_EVENT_STREAM)
            .body("event:error\ndata:" + json + "\n\n");
    }

    private static boolean requestsSse(HttpServletRequest request) {
        if (request == null) return false;
        try {
            List<MediaType> accepted = MediaType.parseMediaTypes(Collections.list(request.getHeaders(HttpHeaders.ACCEPT)));
            boolean explicitStream = accepted.stream()
                .anyMatch(type -> type.getType().equals("text") && type.getSubtype().equals("event-stream"));
            return explicitStream && acceptedQuality(accepted, MediaType.TEXT_EVENT_STREAM)
                > acceptedQuality(accepted, MediaType.APPLICATION_JSON);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static double acceptedQuality(List<MediaType> accepted, MediaType format) {
        // RFC 9110: a specific media range overrides a wildcard even when its q is lower or zero.
        return accepted.stream().filter(type -> type.includes(format))
            .max(Comparator.comparing((MediaType type) -> !type.isWildcardType())
                .thenComparing(type -> !type.isWildcardSubtype())
                .thenComparingDouble(MediaType::getQualityValue))
            .map(MediaType::getQualityValue).orElse(0.0);
    }

    @ExceptionHandler(AgentException.class)
    public ResponseEntity<?> handleAgentException(AgentException ex, HttpServletRequest request) {
        return requestsSse(request) ? handleAgentExceptionSse(ex) : handleAgentException(ex);
    }

    public ResponseEntity<Map<String, Object>> handleAgentException(AgentException ex) {
        log.warn("Agent exception: {}", ex.getMessage());
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
            "type", "agent",
            "error", ex.getMessage()
        ));
    }

    public ResponseEntity<String> handleAgentExceptionSse(AgentException ex) {
        log.warn("Agent exception: {}", ex.getMessage());
        return sseErrorEvent(ex.getStatus(), "agent", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        log.debug("Validation error: {}", errors);
        return ResponseEntity.badRequest().body(Map.of(
            "type", "VALIDATION_ERROR",
            "errors", errors
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String details = ex.getConstraintViolations().stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.joining("; "));
        log.warn("Configuration constraint violation: {}", details);
        return ResponseEntity.badRequest().body(Map.of(
            "type", "configuration",
            "error", "Invalid configuration: " + details
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBadJson(HttpMessageNotReadableException ex) {
        log.debug("Malformed JSON request body: {}", ex.getMessage());
        // Hermes/OpenAI contract keeps the message generic; details stay in logs.
        String message = "Invalid JSON";
        Map<String, Object> nested = Map.of(
            "type", "invalid_request_error",
            "message", message
        );
        Map<String, Object> body = new HashMap<>();
        body.put("type", "bad_request");
        body.put("error", nested);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Illegal argument in request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
            "type", "bad_request",
            "error", ex.getMessage()
        ));
    }

    @ExceptionHandler({TypeMismatchException.class, ServletRequestBindingException.class})
    public ResponseEntity<?> handleRequestBinding(Exception ex, HttpServletRequest request) {
        // Binding also covers server configuration failures; missing-after-conversion stays a client error.
        if (ex instanceof ConversionNotSupportedException
                || (ex instanceof ServletRequestBindingException binding && binding.getStatusCode().is5xxServerError())) {
            return handleGeneric(ex, request);
        }
        if (requestsSse(request)) return handleRequestBindingSse(ex);
        log.debug("Invalid or missing request parameter: {}", ex.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Map.of(
            "type", "bad_request",
            "error", "Invalid or missing request parameter"
        ));
    }

    public ResponseEntity<String> handleRequestBindingSse(Exception ex) {
        log.debug("Invalid or missing request parameter: {}", ex.getClass().getSimpleName());
        return sseErrorEvent(HttpStatus.BAD_REQUEST, "bad_request", "Invalid or missing request parameter");
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTimeout(TimeoutException ex) {
        log.warn("External call timed out", ex);
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of(
            "type", "timeout",
            "error", "External service call timed out: " + ex.getMessage()
        ));
    }

    @ExceptionHandler(java.net.http.HttpTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleHttpTimeout(java.net.http.HttpTimeoutException ex) {
        log.warn("HTTP timeout", ex);
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of(
            "type", "timeout",
            "error", "External HTTP request timed out: " + ex.getMessage()
        ));
    }

    /**
     * Unknown API paths must return a clean 404, not fall into the generic
     * 500 handler with a full stack trace. NoResourceFoundException is thrown
     * by Spring MVC's resource chain when no controller matches the path
     * (Hermes-parity behaviour for wrong API paths).
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        String path = "unknown";
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                path = sra.getRequest().getRequestURI();
            }
        } catch (Exception ignored) {
            // path is informational only
        }
        log.debug("No handler for path: {}", path);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "type", "not_found",
            "error", "No such endpoint: " + path
        ));
    }

    /**
     * Wrong HTTP method on a known path: clean 405 instead of a 500.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.debug("Method not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of(
            "type", "method_not_allowed",
            "error", ex.getMessage()
        ));
    }

    @ExceptionHandler(java.lang.SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(java.lang.SecurityException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
            "type", "forbidden",
            "error", ex.getMessage()
        ));
    }

    /**
     * ResponseStatusException carries its own status (403 from requireAdmin,
     * 404 from lookup failures) — rethrow it as the response instead of
     * falling into the generic 500 handler, which masked RBAC denials.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        if (status.is5xxServerError()) {
            log.warn("ResponseStatusException {}: {}", status, message);
        } else {
            // Client errors (403 RBAC, 404 not found) are expected flows — debug only.
            log.debug("ResponseStatusException {}: {}", status, message);
        }
        String type = status == HttpStatus.FORBIDDEN ? "forbidden"
            : status == HttpStatus.NOT_FOUND ? "not_found" : "status";
        return ResponseEntity.status(status).body(Map.of(
            "type", type,
            "error", message
        ));
    }

    /**
     * A peer may close an SSE response while the agent is still finishing a turn.
     * The response is already unusable, so attempting to serialize another SSE
     * error event recurses through this advice and produces a misleading 500.
     */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleDisconnectedAsyncRequest(
            org.springframework.web.context.request.async.AsyncRequestNotUsableException ex) {
        log.debug("Client disconnected from async response: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneric(Exception ex, HttpServletRequest request) {
        return requestsSse(request) ? handleGenericSse(ex) : handleGeneric(ex);
    }

    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "type", "internal",
            "error", "Internal server error"
        ));
    }

    public ResponseEntity<String> handleGenericSse(Exception ex) {
        log.error("Unhandled streaming exception", ex);
        return sseErrorEvent(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal server error");
    }
}
