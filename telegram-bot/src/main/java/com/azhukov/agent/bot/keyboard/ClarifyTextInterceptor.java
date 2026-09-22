package com.azhukov.agent.bot.keyboard;

/**
 * Resolves a pending blocking-clarify prompt with a typed Telegram message.
 *
 * <p>Hermes parity (clarify_gateway.py): while a turn is blocked on a clarify
 * prompt, the user's typed text is coerced into a selection (numbers, labels,
 * comma lists) and posted to the backend, which unblocks the waiting tool.</p>
 */

import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Typed-message interception for blocking clarify (Hermes
 * attempt_text_response_for_session): while the agent turn blocks on a
 * clarify prompt, a plain message is first offered to the backend
 * (/clarify/text). "resolved" feeds the answer into the open turn;
 * "rejected_prose"/"no_pending" return null so the message routes normally.
 */
@Slf4j
@Component
public class ClarifyTextInterceptor {

    private final RestClient backendRestClient;
    private final ObjectMapper objectMapper;
    private final ClarifyInteractionRenderer clarifyInteractionRenderer;

    public ClarifyTextInterceptor(@Qualifier("backendRestClient") RestClient backendRestClient,
                                  ObjectMapper objectMapper,
                                  ClarifyInteractionRenderer clarifyInteractionRenderer) {
        this.backendRestClient = backendRestClient;
        this.objectMapper = objectMapper;
        this.clarifyInteractionRenderer = clarifyInteractionRenderer;
    }

    /**
     * @return "resolved", "invalid_selection" when the backend classified the
     *         text as a failed selection (prompt stays armed), or null when
     *         there is no pending clarify (route the message normally).
     */
    public String tryResolve(BotSessionEntity session, String text) {
        if (session == null || session.getId() == null || text == null || text.isBlank()) {
            return null;
        }
        String backendSessionId = clarifyInteractionRenderer.awaitingTextSession(
            parseChatId(session.getChatId()));
        if (backendSessionId == null || backendSessionId.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> outcome = backendRestClient.post()
                .uri("/api/v1/agent/session/{sessionId}/clarify/text", backendSessionId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("text", text))
                .retrieve()
                .body(Map.class);
            if (outcome == null) {
                return null;
            }
            String result = String.valueOf(outcome.get("outcome"));
            return switch (result) {
                case "resolved" -> {
                    clarifyInteractionRenderer.clearAwaitingTextSession(parseChatId(session.getChatId()));
                    yield "resolved";
                }
                case "rejected_selection" -> "invalid_selection";
                default -> null;
            };
        } catch (Exception e) {
            log.debug("Clarify text intercept failed for session {}: {}", session.getId(), e.getMessage());
            return null;
        }
    }

    private long parseChatId(String chatId) {
        try {
            return Long.parseLong(chatId);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
