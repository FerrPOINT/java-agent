package com.azhukov.agent.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    GlobalExceptionHandler h = new GlobalExceptionHandler(new com.fasterxml.jackson.databind.ObjectMapper());

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
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) r.getBody().get("error");
        assertThat(nested.get("type")).isEqualTo("invalid_request_error");
        assertThat(nested.get("message")).isEqualTo("Invalid JSON");
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
    void methodNotSupportedReturnsClean405() {
        org.springframework.web.HttpRequestMethodNotSupportedException ex =
            new org.springframework.web.HttpRequestMethodNotSupportedException("DELETE");
        ResponseEntity<Map<String, Object>> r = h.handleMethodNotSupported(ex);
        assertThat(r.getStatusCode().value()).isEqualTo(405);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("method_not_allowed");
    }

    @Test
    void securityExceptionReturns403Forbidden() {
        java.lang.SecurityException ex =
            new java.lang.SecurityException("Session does not belong to the current user");
        ResponseEntity<Map<String, Object>> r = h.handleSecurity(ex);
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().get("type")).isEqualTo("forbidden");
        assertThat(r.getBody().get("error")).isEqualTo("Session does not belong to the current user");
    }
}
