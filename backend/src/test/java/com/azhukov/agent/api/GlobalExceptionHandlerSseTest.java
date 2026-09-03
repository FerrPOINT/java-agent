package com.azhukov.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Bug 2: GlobalExceptionHandler SSE error handling.
 * <p>
 * When an exception occurs during SSE streaming, the response content type is
 * text/event-stream. The GlobalExceptionHandler previously tried to return JSON
 * ResponseEntity, which caused HttpMessageNotWritableException because Spring
 * can't convert Map to text/event-stream.
 * <p>
 * Fix: Detect SSE requests (Accept header contains text/event-stream) and return
 * an SseEmitter with an error event instead of a JSON ResponseEntity.
 * <p>
 * Also: AgentStreamingService.safeCompleteWithError() now calls emitter.complete()
 * instead of emitter.completeWithError() to prevent exceptions from propagating
 * to GlobalExceptionHandler in the first place.
 */
class GlobalExceptionHandlerSseTest {

    GlobalExceptionHandler h = new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper());

    // ── SSE detection: AgentException on SSE endpoint ──

    @Test
    void agentExceptionOnSseRequestReturnsSseEmitterNotJson() {
        setSseRequestContext();

        Object result = h.handleAgentException(
            new AgentException(HttpStatus.INTERNAL_SERVER_ERROR, "stream failed"));

        assertThat(result).isInstanceOf(SseEmitter.class);
        clearRequestContext();
    }

    // ── SSE detection: generic exception on SSE endpoint ──

    @Test
    void genericExceptionOnSseRequestReturnsSseEmitterNotJson() {
        setSseRequestContext();

        Object result = h.handleGeneric(new RuntimeException("internal stream error"));

        assertThat(result).isInstanceOf(SseEmitter.class);
        clearRequestContext();
    }

    // ── Non-SSE detection: AgentException on regular endpoint returns JSON ──

    @Test
    void agentExceptionOnNonSseRequestReturnsJsonResponseEntity() {
        setNonSseRequestContext();

        Object result = h.handleAgentException(
            new AgentException(HttpStatus.NOT_FOUND, "session not found"));

        assertThat(result).isNotInstanceOf(SseEmitter.class);
        assertThat(result).isInstanceOf(org.springframework.http.ResponseEntity.class);
        clearRequestContext();
    }

    // ── Non-SSE detection: generic exception on regular endpoint returns JSON ──

    @Test
    void genericExceptionOnNonSseRequestReturnsJsonResponseEntity() {
        setNonSseRequestContext();

        Object result = h.handleGeneric(new RuntimeException("internal error"));

        assertThat(result).isNotInstanceOf(SseEmitter.class);
        assertThat(result).isInstanceOf(org.springframework.http.ResponseEntity.class);
        clearRequestContext();
    }

    // ── No request context at all: fallback to JSON ──

    @Test
    void agentExceptionWithNoRequestContextReturnsJsonResponseEntity() {
        RequestContextHolder.resetRequestAttributes();

        Object result = h.handleAgentException(
            new AgentException(HttpStatus.NOT_FOUND, "no context"));

        assertThat(result).isInstanceOf(org.springframework.http.ResponseEntity.class);
    }

    @Test
    void genericExceptionWithNoRequestContextReturnsJsonResponseEntity() {
        RequestContextHolder.resetRequestAttributes();

        Object result = h.handleGeneric(new RuntimeException("no context error"));

        assertThat(result).isInstanceOf(org.springframework.http.ResponseEntity.class);
    }

    // ── Helpers ──

    private void setSseRequestContext() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn(MediaType.TEXT_EVENT_STREAM_VALUE);
        ServletRequestAttributes attrs = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attrs);
    }

    private void setNonSseRequestContext() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        ServletRequestAttributes attrs = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attrs);
    }

    private void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }
}