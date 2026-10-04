package com.azhukov.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks terminal SSE framing, error statuses and JSON escaping.
 * Actual MVC binding and content negotiation are covered by GlobalExceptionHandlerBindingTest.
 */
class GlobalExceptionHandlerSseTest {

    GlobalExceptionHandler h = new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void terminalFrameKeepsEscapedJsonEvenWhenMapperUsesPrettyPrinting() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        String message = "quote \" and newline\nbackslash\\";
        String frame = new GlobalExceptionHandler(mapper)
            .handleAgentExceptionSse(new AgentException(HttpStatus.BAD_REQUEST, message)).getBody();
        assertThat(frame).isNotNull().startsWith("event:error\ndata:").endsWith("\n\n");
        java.util.List<String> data = frame.lines().filter(line -> line.startsWith("data:")).toList();
        assertThat(data).hasSize(1);
        assertThat(mapper.readTree(data.getFirst().substring(5)).get("error").asText()).isEqualTo(message);
    }

    // ── SSE detection: AgentException on SSE endpoint ──

    @Test
    void agentExceptionOnSseRequestReturnsTerminalEvent() {
        setSseRequestContext();

        Object result = h.handleAgentExceptionSse(
            new AgentException(HttpStatus.INTERNAL_SERVER_ERROR, "stream failed"));

        assertThat(result).isInstanceOf(org.springframework.http.ResponseEntity.class);
        org.springframework.http.ResponseEntity<?> response = (org.springframework.http.ResponseEntity<?>) result;
        assertThat(response.getBody()).asString().startsWith("event:error\ndata:").endsWith("\n\n");
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        clearRequestContext();
    }

    // ── SSE detection: generic exception on SSE endpoint ──

    @Test
    void genericExceptionOnSseRequestReturnsSafeTerminalEvent() {
        setSseRequestContext();

        Object result = h.handleGenericSse(new RuntimeException("internal stream error"));

        assertThat(result).isInstanceOf(org.springframework.http.ResponseEntity.class);
        org.springframework.http.ResponseEntity<?> response = (org.springframework.http.ResponseEntity<?>) result;
        assertThat(response.getBody()).asString().contains("Internal server error").doesNotContain("internal stream error");
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        clearRequestContext();
    }

    @Test
    void disconnectedAsyncRequestIsIgnoredWithoutCreatingSecondSseResponse() {
        setSseRequestContext();

        h.handleDisconnectedAsyncRequest(new AsyncRequestNotUsableException("Broken pipe"));

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
