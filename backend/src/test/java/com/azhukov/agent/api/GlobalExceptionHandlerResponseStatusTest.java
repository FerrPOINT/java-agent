package com.azhukov.agent.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mu8 regression: RBAC denials threw ResponseStatusException(403) which the
 * generic Exception handler swallowed into a 500 with a stack trace. The
 * dedicated handler must map the exception's own status to the response.
 */
class GlobalExceptionHandlerResponseStatusTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("403 ResponseStatusException maps to 403 forbidden, not 500")
    void forbiddenMapsTo403() {
        ResponseStatusException ex = new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Admin role required for user management");

        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
            .containsEntry("type", "forbidden")
            .containsEntry("error", "Admin role required for user management");
    }

    @Test
    @DisplayName("404 ResponseStatusException keeps its 404")
    void notFoundMapsTo404() {
        ResponseStatusException ex = new ResponseStatusException(
            HttpStatus.NOT_FOUND, "User not found: u-1");

        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("type", "not_found");
    }

    @Test
    @DisplayName("null reason falls back to the status reason phrase")
    void nullReasonUsesReasonPhrase() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.CONFLICT);

        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "Conflict");
    }
}
