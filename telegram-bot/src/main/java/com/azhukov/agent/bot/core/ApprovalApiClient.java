package com.azhukov.agent.bot.core;

import com.azhukov.agent.shared.dto.ToolApprovalDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class ApprovalApiClient extends BaseBackendClient {

    public ApprovalApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    public String decide(ToolApprovalDecision decision) {
        try {
            String result = restClient.post()
                .uri("/api/v1" + ToolApprovalDecision.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(decision)
                .retrieve()
                .body(String.class);
            return result != null && !result.isBlank() ? result : "Error: approval response is empty";
        } catch (Exception e) {
            log.warn("Approval decision failed: {}", e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

}
