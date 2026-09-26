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
     * @return "resolved", "invalid_selection", or "awaiting_response". The
     *         latter consumes arbitrary prose while a choice clarify remains
     *         visible, preventing busy-mode interruption of the blocked turn.
     */
    public String tryResolve(BotSessionEntity session, String text) {
        if (session == null || session.getId() == null || text == null || text.isBlank()) {
            return null;
        }
        long chatId = parseChatId(session.getChatId());
        String backendSessionId = clarifyInteractionRenderer.awaitingResponseSession(chatId);
        if (backendSessionId == null || backendSessionId.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> request = new java.util.HashMap<>();
            request.put("text", text);
            String clarifyId = clarifyInteractionRenderer.awaitingTextClarifyId(chatId);
            if (clarifyId != null && !clarifyId.isBlank()) {
                request.put("clarifyId", clarifyId);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outcome = backendRestClient.post()
                .uri("/api/v1/agent/session/{sessionId}/clarify/text", backendSessionId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Map.class);
            if (outcome == null) {
                return null;
            }
            String result = String.valueOf(outcome.get("outcome"));
            if ("resolved".equals(result)) {
                clarifyInteractionRenderer.completeTextResponse(chatId, String.valueOf(outcome.get("clarifyId")));
                return "resolved";
            }
            if ("rejected_selection".equals(result)) {
                return "invalid_selection";
            }
            return clarifyInteractionRenderer.isAwaitingResponse(chatId) ? "awaiting_response" : null;
        } catch (Exception e) {
            log.debug("Clarify text intercept failed for session {}: {}", session.getId(), e.getMessage());
            return clarifyInteractionRenderer.isAwaitingResponse(chatId) ? "awaiting_response" : null;
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
