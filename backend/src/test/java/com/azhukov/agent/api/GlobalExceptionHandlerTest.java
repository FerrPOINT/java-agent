package com.azhukov.agent.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    GlobalExceptionHandler h = new GlobalExceptionHandler();

    @Test
    void agentExceptionReturnsCorrectStatusTypeAndMessage() {
        Object result = h.handleAgentException(
            new AgentException(HttpStatus.NOT_FOUND, "session not found"));
        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> r = (ResponseEntity<Map<String, Object>>) result;
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("agent");
        assertThat(r.getBody().get("error")).isEqualTo("session not found");
    }

    @Test
    void agentExceptionOnOpenAiCompatibleRequestReturnsOpenAiEnvelopeLikeHermes() {
        setRequestPath("/v1/chat/completions");
        try {
            Object result = h.handleAgentException(
                new AgentException(HttpStatus.FORBIDDEN, "Session continuation requires API key authentication. "
                    + "Configure API_SERVER_KEY to enable this feature."));

            assertThat(result).isInstanceOf(ResponseEntity.class);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> r = (ResponseEntity<Map<String, Object>>) result;
            assertThat(r.getStatusCode().value()).isEqualTo(403);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("Session continuation requires API key authentication. "
                + "Configure API_SERVER_KEY to enable this feature.");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void agentExceptionOnHermesSessionRequestReturnsOpenAiEnvelopeLikeHermes() {
        setRequestPath("/api/sessions/22222222-2222-2222-2222-222222222222/chat");
        try {
            Object result = h.handleAgentException(
                new AgentException(HttpStatus.FORBIDDEN, "X-Hermes-Session-Key requires API key authentication. "
                    + "Configure API_SERVER_KEY to enable this feature."));

            assertThat(result).isInstanceOf(ResponseEntity.class);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> r = (ResponseEntity<Map<String, Object>>) result;
            assertThat(r.getStatusCode().value()).isEqualTo(403);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("X-Hermes-Session-Key requires API key authentication. "
                + "Configure API_SERVER_KEY to enable this feature.");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void validationExceptionReturnsErrorsMapWithType() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "o");
        br.addError(new FieldError("o", "fieldName", "must not be blank"));
        br.addError(new FieldError("o", "anotherField", "must be positive"));
        ResponseEntity<Map<String, Object>> r = h.handleValidation(
            new MethodArgumentNotValidException(null, br));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("VALIDATION_ERROR");
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) r.getBody().get("errors");
        assertThat(errors)
            .containsEntry("fieldName", "must not be blank")
            .containsEntry("anotherField", "must be positive");
    }

    @Test
    void constraintViolationReturnsJoinedMessages() {
        Set<ConstraintViolation<?>> set = new HashSet<>();
        set.add(new ConstraintViolation<String>() {
            @Override public String getMessage() { return "must not be null"; }
            @Override public String getMessageTemplate() { return ""; }
            @Override public Path getPropertyPath() { return null; }
            @Override public String getRootBean() { return null; }
            @Override public Class<String> getRootBeanClass() { return String.class; }
            @Override public Object getLeafBean() { return null; }
            @Override public Object[] getExecutableParameters() { return null; }
            @Override public Object getExecutableReturnValue() { return null; }
            @Override public Object getInvalidValue() { return null; }
            @Override public ConstraintDescriptor<?> getConstraintDescriptor() { return null; }
            @Override public <U> U unwrap(Class<U> type) { return null; }
        });
        set.add(new ConstraintViolation<String>() {
            @Override public String getMessage() { return "must be positive"; }
            @Override public String getMessageTemplate() { return ""; }
            @Override public Path getPropertyPath() { return null; }
            @Override public String getRootBean() { return null; }
            @Override public Class<String> getRootBeanClass() { return String.class; }
            @Override public Object getLeafBean() { return null; }
            @Override public Object[] getExecutableParameters() { return null; }
            @Override public Object getExecutableReturnValue() { return null; }
            @Override public Object getInvalidValue() { return null; }
            @Override public ConstraintDescriptor<?> getConstraintDescriptor() { return null; }
            @Override public <U> U unwrap(Class<U> type) { return null; }
        });
        ResponseEntity<Map<String, Object>> r = h.handleConstraintViolation(
            new ConstraintViolationException(set));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("configuration");
        String error = (String) r.getBody().get("error");
        assertThat(error).startsWith("Invalid configuration: ");
        assertThat(error).contains("must not be null");
        assertThat(error).contains("must be positive");
    }

    @Test
    void badJsonReturnsErrorWithOriginalMessage() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("malformed payload", (Throwable) null, null);
        ResponseEntity<Map<String, Object>> r = h.handleBadJson(ex);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("bad_request");
        assertThat(r.getBody().get("error")).asString().contains("malformed payload");
    }

    @Test
    void badJsonOnHermesSessionRequestReturnsOpenAiEnvelopeLikeHermes() {
        setRequestPath("/api/sessions/22222222-2222-2222-2222-222222222222/chat");
        try {
            HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "malformed payload", (Throwable) null, null);
            ResponseEntity<Map<String, Object>> r = h.handleBadJson(ex);

            assertThat(r.getStatusCode().value()).isEqualTo(400);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("Invalid JSON in request body");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void badJsonOnProfilePrefixedOpenAiRequestReturnsOpenAiEnvelopeLikeHermes() {
        setRequestPath("/p/work/v1/responses");
        try {
            HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "malformed payload", (Throwable) null, null);
            ResponseEntity<Map<String, Object>> r = h.handleBadJson(ex);

            assertThat(r.getStatusCode().value()).isEqualTo(400);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("Invalid JSON in request body");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void badJsonOnProfilePrefixedRunsRequestKeepsRunsMessageLikeHermes() {
        setRequestPath("/p/work/v1/runs", "POST");
        try {
            HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "malformed payload", (Throwable) null, null);
            ResponseEntity<Map<String, Object>> r = h.handleBadJson(ex);

            assertThat(r.getStatusCode().value()).isEqualTo(400);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("Invalid JSON");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void illegalArgumentReturnsErrorWithType() {
        ResponseEntity<Map<String, Object>> r = h.handleIllegalArgument(
            new IllegalArgumentException("invalid argument value"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("bad_request");
        assertThat(r.getBody().get("error")).isEqualTo("invalid argument value");
    }

    @Test
    void illegalArgumentOnHermesSessionRequestReturnsOpenAiEnvelopeLikeHermes() {
        setRequestPath("/api/sessions/not-a-uuid/messages");
        try {
            ResponseEntity<Map<String, Object>> r = h.handleIllegalArgument(
                new IllegalArgumentException("Invalid session ID"));

            assertThat(r.getStatusCode().value()).isEqualTo(400);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("Invalid session ID");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void illegalArgumentOnProfilePrefixedSessionRequestReturnsOpenAiEnvelopeLikeHermes() {
        setRequestPath("/p/work/api/sessions/not-a-uuid/messages");
        try {
            ResponseEntity<Map<String, Object>> r = h.handleIllegalArgument(
                new IllegalArgumentException("Invalid session ID"));

            assertThat(r.getStatusCode().value()).isEqualTo(400);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("Invalid session ID");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void timeoutReturns504WithTypeAndMessage() {
        ResponseEntity<Map<String, Object>> r = h.handleTimeout(
            new TimeoutException("LLM call exceeded 30s"));
        assertThat(r.getStatusCode().value()).isEqualTo(504);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("timeout");
        assertThat(r.getBody().get("error")).asString().contains("LLM call exceeded 30s");
    }

    @Test
    void httpTimeoutReturns504WithTypeAndMessage() {
        java.net.http.HttpTimeoutException ex = new java.net.http.HttpTimeoutException("connect timeout");
        ResponseEntity<Map<String, Object>> r = h.handleHttpTimeout(ex);
        assertThat(r.getStatusCode().value()).isEqualTo(504);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("timeout");
        assertThat(r.getBody().get("error")).asString().contains("connect timeout");
    }

    @Test
    void genericExceptionReturns500WithInternalTypeAndMessage() {
        Object result = h.handleGeneric(
            new RuntimeException("unexpected NPE in service layer"));
        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> r = (ResponseEntity<Map<String, Object>>) result;
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("internal");
        assertThat(r.getBody().get("error")).asString().contains("unexpected NPE in service layer");
    }

    @Test
    void genericExceptionOnOpenAiCompatibleRequestReturnsServerErrorEnvelopeLikeHermes() {
        setRequestPath("/v1/chat/completions");
        try {
            Object result = h.handleGeneric(new RuntimeException("model service unavailable"));

            assertThat(result).isInstanceOf(ResponseEntity.class);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> r = (ResponseEntity<Map<String, Object>>) result;
            assertThat(r.getStatusCode().value()).isEqualTo(500);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("Internal server error: model service unavailable");
            assertThat(error.get("type")).isEqualTo("server_error");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void agentExceptionWithDifferentStatusCodes() {
        // Verify that the status from the exception is respected, not hardcoded
        Object r1 = h.handleAgentException(
            new AgentException(HttpStatus.FORBIDDEN, "denied"));
        assertThat(r1).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> re1 = (ResponseEntity<Map<String, Object>>) r1;
        assertThat(re1.getStatusCode().value()).isEqualTo(403);
        assertThat(re1.getBody().get("error")).isEqualTo("denied");

        Object r2 = h.handleAgentException(
            new AgentException(HttpStatus.UNPROCESSABLE_ENTITY, "bad state"));
        assertThat(r2).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> re2 = (ResponseEntity<Map<String, Object>>) r2;
        assertThat(re2.getStatusCode().value()).isEqualTo(422);
        assertThat(re2.getBody().get("error")).isEqualTo("bad state");
    }

    @Test
    void validationExceptionWithNoErrorsReturnsEmptyErrorsMap() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "o");
        ResponseEntity<Map<String, Object>> r = h.handleValidation(
            new MethodArgumentNotValidException(null, br));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().get("type")).isEqualTo("VALIDATION_ERROR");
        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) r.getBody().get("errors");
        assertThat(errors).isEmpty();
    }

    @Test
    void noResourceFoundReturnsClean404InsteadOf500() {
        org.springframework.web.servlet.resource.NoResourceFoundException ex =
            new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/v1/agent/capabilities", null);
        ResponseEntity<Map<String, Object>> r = h.handleNoResourceFound(ex);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("not_found");
        assertThat((String) r.getBody().get("error")).contains("No such endpoint");
    }

    @Test
    void noResourceFoundOnV1PathReturnsOpenAiEnvelopeLikeHermes() {
        setRequestPath("/v1/unknown");
        try {
            org.springframework.web.servlet.resource.NoResourceFoundException ex =
                new org.springframework.web.servlet.resource.NoResourceFoundException(
                    org.springframework.http.HttpMethod.GET, "/v1/unknown", null);

            ResponseEntity<Map<String, Object>> r = h.handleNoResourceFound(ex);

            assertThat(r.getStatusCode().value()).isEqualTo(404);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).isEqualTo("No such endpoint: /v1/unknown");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
            assertThat(error).containsEntry("param", null).containsEntry("code", null);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void methodNotSupportedReturnsClean405() {
        org.springframework.web.HttpRequestMethodNotSupportedException ex =
            new org.springframework.web.HttpRequestMethodNotSupportedException("DELETE");
        ResponseEntity<Map<String, Object>> r = h.handleMethodNotSupported(ex);
        assertThat(r.getStatusCode().value()).isEqualTo(405);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("method_not_allowed");
    }

    @Test
    void methodNotSupportedOnModelApiReturnsOpenAiEnvelopeLikeHermes() {
        setRequestPath("/api/model/options");
        try {
            org.springframework.web.HttpRequestMethodNotSupportedException ex =
                new org.springframework.web.HttpRequestMethodNotSupportedException("POST");

            ResponseEntity<Map<String, Object>> r = h.handleMethodNotSupported(ex);

            assertThat(r.getStatusCode().value()).isEqualTo(405);
            assertThat(r.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) r.getBody().get("error");
            assertThat(error.get("message")).asString().contains("POST");
            assertThat(error.get("type")).isEqualTo("invalid_request_error");
            assertThat(error.get("code")).isEqualTo("method_not_allowed");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private void setRequestPath(String path) {
        setRequestPath(path, "GET");
    }

    private void setRequestPath(String path, String method) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Accept")).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getMethod()).thenReturn(method);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
