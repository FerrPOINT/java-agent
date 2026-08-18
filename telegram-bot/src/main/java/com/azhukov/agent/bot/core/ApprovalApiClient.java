package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Per-domain delegate covering approval endpoints:
 * approve, deny, resolveApproval (exec-approval buttons) and
 * resolveSlashConfirm (slash-confirm buttons).
 */
@Service
@Slf4j
public class ApprovalApiClient extends BaseBackendClient {

    public ApprovalApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    public String approve(boolean all, String scope) {
        Map<String, Object> body = body();
        body.put("all", all);
        if (scope != null) body.put("scope", scope);
        try {
            return restClient.post()
                .uri("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            log.warn("approve failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String deny(boolean all) {
        Map<String, Object> body = body();
        body.put("all", all);
        try {
            return restClient.post()
                .uri("/api/v1/agent/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            log.warn("deny failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Resolve an exec-approval callback from an inline button press.
     * <p>
     * Maps the button choice to the backend's approve/deny API:
     * <ul>
     * <li>{@code once} → approve single (scope=sessionKey)</li>
     * <li>{@code session} → approve with scope "session"</li>
     * <li>{@code always} → approve with scope "always"</li>
     * <li>{@code deny} → deny single</li>
     * </ul>
     *
     * @param sessionKey the session key to resolve (from ApprovalStateStore)
     * @param choice the button choice: once, session, always, deny
     * @return the backend response string
     */
    public String resolveApproval(String sessionKey, String choice) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return "No session key";
        }
        try {
            return switch (choice) {
                case "once" -> {
                    Map<String, Object> body = body();
                    body.put("all", false);
                    body.put("scope", sessionKey);
                    yield restClient.post()
                        .uri("/api/v1/agent/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                }
                case "session" -> approve(false, "session");
                case "always" -> approve(false, "always");
                case "deny" -> deny(false);
                default -> "Unknown choice: " + choice;
            };
        } catch (Exception e) {
            log.warn("resolveApproval failed for sessionKey={}, choice={}: {}", sessionKey, choice, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Resolve a slash-confirm callback from an inline button press.
     * <p>
     * Maps the button choice to the backend's approve/deny API:
     * <ul>
     * <li>{@code once} → approve single (scope=sessionKey)</li>
     * <li>{@code always} → approve with scope "always"</li>
     * <li>{@code cancel} → deny single</li>
     * </ul>
     *
     * @param sessionKey the session key to resolve (from ApprovalStateStore)
     * @param confirmId the confirm prompt ID (unused by backend but logged)
     * @param choice the button choice: once, always, cancel
     * @return the backend response string
     */
    public String resolveSlashConfirm(String sessionKey, String confirmId, String choice) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return "No session key";
        }
        log.debug("Resolving slash-confirm: sessionKey={}, confirmId={}, choice={}", sessionKey, confirmId, choice);
        try {
            return switch (choice) {
                case "once" -> {
                    Map<String, Object> body = body();
                    body.put("all", false);
                    body.put("scope", sessionKey);
                    yield restClient.post()
                        .uri("/api/v1/agent/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                }
                case "always" -> approve(false, "always");
                case "cancel" -> deny(false);
                default -> "Unknown choice: " + choice;
            };
        } catch (Exception e) {
            log.warn("resolveSlashConfirm failed for sessionKey={}, confirmId={}, choice={}: {}",
                sessionKey, confirmId, choice, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}