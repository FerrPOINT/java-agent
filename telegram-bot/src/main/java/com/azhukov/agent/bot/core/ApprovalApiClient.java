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
 * approve and deny commands.
 */
@Service
@Slf4j
public class ApprovalApiClient extends BaseBackendClient {

    public ApprovalApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    public String approve(boolean all, String requestId) {
        Map<String, Object> body = body();
        body.put("all", all);
        if (requestId != null) body.put("requestId", requestId);
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return result != null && !result.isBlank() ? result : "Error: approval response is empty";
        } catch (Exception e) {
            log.warn("approve failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    public String deny(boolean all) {
        Map<String, Object> body = body();
        body.put("all", all);
        try {
            String result = restClient.post()
                .uri("/api/v1/agent/deny")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
            return result != null && !result.isBlank() ? result : "Error: denial response is empty";
        } catch (Exception e) {
            log.warn("deny failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

}
