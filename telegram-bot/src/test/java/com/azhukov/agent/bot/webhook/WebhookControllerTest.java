package com.azhukov.agent.bot.webhook;

import com.azhukov.agent.bot.polling.UpdateEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WebhookController} covering webhook handling,
 * secret validation, update processing, and edge cases.
 */
class WebhookControllerTest {

    private WebhookSecretValidator secretValidator;
    private ObjectMapper objectMapper;
    @SuppressWarnings("unchecked")
    private final Consumer<UpdateEvent> updateHandler = mock(Consumer.class);

    private WebhookController controller;

    // A valid Telegram update JSON body
    private static final String VALID_UPDATE_JSON =
        "{\"update_id\":12345,\"message\":{\"message_id\":1,\"chat\":{\"id\":100,\"type\":\"private\"},"
        + "\"from\":{\"id\":200,\"username\":\"testuser\"},\"text\":\"hello\"}}";

    @BeforeEach
    void setUp() {
        secretValidator = mock(WebhookSecretValidator.class);
        objectMapper = new ObjectMapper();
        controller = new WebhookController(secretValidator, objectMapper, updateHandler);

        // Default: secret is configured and valid
        when(secretValidator.isConfigured()).thenReturn(true);
        when(secretValidator.isValid(any())).thenReturn(true);
    }

    // ─── Happy path ──────────────────────────────────────────────────────

    @Test
    void receive_validSecretAndBody_returnsOkAndInvokesHandler() {
        ResponseEntity<String> response = controller.receive("valid-secret", VALID_UPDATE_JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("OK");
        verify(updateHandler).accept(any(UpdateEvent.class));
    }

    // ─── Secret not configured ───────────────────────────────────────────

    @Test
    void receive_secretNotConfigured_returns403() {
        when(secretValidator.isConfigured()).thenReturn(false);

        ResponseEntity<String> response = controller.receive("any", VALID_UPDATE_JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo("Webhook secret not configured");
        verify(updateHandler, never()).accept(any());
    }

    // ─── Invalid secret ──────────────────────────────────────────────────

    @Test
    void receive_invalidSecret_returns403() {
        when(secretValidator.isValid("wrong-secret")).thenReturn(false);

        ResponseEntity<String> response = controller.receive("wrong-secret", VALID_UPDATE_JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo("FORBIDDEN");
        verify(updateHandler, never()).accept(any());
    }

    // ─── Missing secret header (null) ────────────────────────────────────

    @Test
    void receive_nullSecretHeader_returns403() {
        when(secretValidator.isValid(null)).thenReturn(false);

        ResponseEntity<String> response = controller.receive(null, VALID_UPDATE_JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo("FORBIDDEN");
        verify(updateHandler, never()).accept(any());
    }

    // ─── Malformed JSON body ─────────────────────────────────────────────

    @Test
    void receive_malformedJson_returns400() {
        ResponseEntity<String> response = controller.receive("valid-secret", "not-json{");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo("ERROR");
        verify(updateHandler, never()).accept(any());
    }

    // ─── Empty body ──────────────────────────────────────────────────────

    @Test
    void receive_emptyBody_returns400() {
        ResponseEntity<String> response = controller.receive("valid-secret", "");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo("ERROR");
        verify(updateHandler, never()).accept(any());
    }

    // ─── Handler throws exception ────────────────────────────────────────

    @Test
    void receive_handlerThrows_returns400() {
        doThrow(new RuntimeException("Handler crashed"))
            .when(updateHandler).accept(any(UpdateEvent.class));

        ResponseEntity<String> response = controller.receive("valid-secret", VALID_UPDATE_JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo("ERROR");
    }

    // ─── Callback query update ───────────────────────────────────────────

    @Test
    void receive_callbackQueryUpdate_returnsOkAndInvokesHandler() {
        String callbackJson =
            "{\"update_id\":999,\"callback_query\":{\"id\":\"cq1\",\"data\":\"btn_click\","
            + "\"from\":{\"id\":200,\"username\":\"testuser\"},"
            + "\"message\":{\"message_id\":1,\"chat\":{\"id\":100,\"type\":\"private\"}}}}";

        ResponseEntity<String> response = controller.receive("valid-secret", callbackJson);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("OK");
        verify(updateHandler).accept(any(UpdateEvent.class));
    }

    // ─── Unknown update type (no message, no callback_query) ─────────────

    @Test
    void receive_unknownUpdateType_returnsOkAndInvokesHandler() {
        String unknownJson = "{\"update_id\":555}";

        ResponseEntity<String> response = controller.receive("valid-secret", unknownJson);

        // UpdateEvent.from will produce an UNKNOWN type event — still accepted by handler
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("OK");
        verify(updateHandler).accept(any(UpdateEvent.class));
    }

    // ─── Secret configured but header is empty string ────────────────────

    @Test
    void receive_emptySecretHeader_returns403() {
        when(secretValidator.isValid("")).thenReturn(false);

        ResponseEntity<String> response = controller.receive("", VALID_UPDATE_JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(updateHandler, never()).accept(any());
    }

    // ─── JSON without update_id (missing required field) ─────────────────

    @Test
    void receive_jsonMissingUpdateId_returns400() {
        String noUpdateIdJson =
            "{\"message\":{\"message_id\":1,\"chat\":{\"id\":100,\"type\":\"private\"},"
            + "\"from\":{\"id\":200},\"text\":\"hello\"}}";

        ResponseEntity<String> response = controller.receive("valid-secret", noUpdateIdJson);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo("ERROR");
        verify(updateHandler, never()).accept(any());
    }
}