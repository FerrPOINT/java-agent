package com.azhukov.agent.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public GlobalExceptionHandler(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Detect whether the current request is an SSE streaming endpoint by checking
     * the Accept header for text/event-stream. When an exception propagates here
     * during SSE streaming, returning a JSON ResponseEntity fails with
     * HttpMessageNotWritableException because the response content type is
     * text/event-stream. In that case, return an SseEmitter that emits an error event.
     */
    private static boolean isSseRequest() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                String accept = sra.getRequest().getHeader("Accept");
                return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
            }
        } catch (Exception e) {
            log.debug("Failed to detect SSE request type: {}", e.getMessage());
            // Ignore — fallback to non-SSE handling
        }
        return false;
    }

    /**
     * Build an SSE error response for streaming endpoints.
     * Returns an SseEmitter that immediately emits an error event and completes.
     */
    private SseEmitter sseErrorEvent(HttpStatus status, String type, String message) {
        SseEmitter emitter = new SseEmitter(5_000L);
        try {
            // rev-75: use ObjectMapper for proper JSON escaping instead of manual
            // string concatenation — the old code only escaped " and \n, missing
            // backslash, \t, \r, and control characters, producing invalid JSON
            // on messages containing those bytes.
            Map<String, String> payload = Map.of("type", type, "error", message);
            String json = objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name("error").data(json));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @ExceptionHandler(AgentException.class)
    public Object handleAgentException(AgentException ex) {
        log.warn("Agent exception: {}", ex.getMessage());
        if (isSseRequest()) {
            return sseErrorEvent(ex.getStatus(), "agent", ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus()).body(Map.of(
            "type", "agent",
            "error", ex.getMessage()
        ));
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
        return ResponseEntity.badRequest().body(Map.of(
            "type", "bad_request",
            "error", "Invalid JSON body: " + ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Illegal argument in request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
            "type", "bad_request",
            "error", ex.getMessage()
        ));
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

    @ExceptionHandler(Exception.class)
    public Object handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        if (isSseRequest()) {
            return sseErrorEvent(HttpStatus.INTERNAL_SERVER_ERROR, "internal",
                "Internal error: " + ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "type", "internal",
            "error", "Internal error: " + ex.getMessage()
        ));
    }
}
