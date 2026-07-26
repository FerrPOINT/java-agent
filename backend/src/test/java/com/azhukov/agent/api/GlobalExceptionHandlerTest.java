package com.azhukov.agent.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;
import org.junit.jupiter.api.Test;
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

    GlobalExceptionHandler h = new GlobalExceptionHandler();

    @Test
    void agentException() {
        ResponseEntity<Map<String, Object>> r = h.handleAgentException(new AgentException(org.springframework.http.HttpStatus.NOT_FOUND, "x"));
        assertThat(r.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void validationException() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "o");
        br.addError(new FieldError("o", "f", "msg"));
        ResponseEntity<Map<String, Object>> r = h.handleValidation(new MethodArgumentNotValidException(null, br));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void constraintViolation() {
        Set<ConstraintViolation<?>> set = new HashSet<>();
        set.add(new ConstraintViolation<String>() {
            @Override public String getMessage() { return "bad"; }
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
        ResponseEntity<Map<String, Object>> r = h.handleConstraintViolation(new ConstraintViolationException(set));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void badJson() {
        ResponseEntity<Map<String, Object>> r = h.handleBadJson(new HttpMessageNotReadableException("bad", (Throwable) null, null));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void illegalArgument() {
        ResponseEntity<Map<String, Object>> r = h.handleIllegalArgument(new IllegalArgumentException("bad"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void timeout() {
        ResponseEntity<Map<String, Object>> r = h.handleTimeout(new TimeoutException("t"));
        assertThat(r.getStatusCode().value()).isEqualTo(504);
    }

    @Test
    void httpTimeout() {
        ResponseEntity<Map<String, Object>> r = h.handleHttpTimeout(new java.net.http.HttpTimeoutException("t"));
        assertThat(r.getStatusCode().value()).isEqualTo(504);
    }

    @Test
    void generic() {
        ResponseEntity<Map<String, Object>> r = h.handleGeneric(new RuntimeException("boom"));
        assertThat(r.getStatusCode().value()).isEqualTo(500);
    }
}
