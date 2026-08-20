package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Per-domain delegate covering cron job endpoints:
 * list, pause, resume and delete.
 */
@Service
@Slf4j
public class CronApiClient extends BaseBackendClient {

    public CronApiClient(@Qualifier("backendRestClient") RestClient restClient, ObjectMapper objectMapper) {
        super(restClient, objectMapper);
    }

    /** List all cron jobs from the backend. */
    public JsonNode listCronJobs() {
        try {
            String json = restClient.get()
                .uri("/api/v1/agent/cron")
                .retrieve()
                .body(String.class);
            JsonNode parsed = readTree(json);
            return parsed != null ? parsed : arrayNode();
        } catch (Exception e) {
            log.warn("listCronJobs failed: {}", e.getMessage());
            return arrayNode();
        }
    }

    /** Delete (dismiss) a cron job by ID. */
    public boolean deleteCronJob(String id) {
        try {
            restClient.delete()
                .uri("/api/v1/agent/cron/{id}", id)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("deleteCronJob failed for id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Pause a cron job by ID. */
    public boolean pauseCronJob(String id) {
        try {
            restClient.post()
                .uri("/api/v1/agent/cron/{id}/pause", id)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("pauseCronJob failed for id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Resume a cron job by ID. */
    public boolean resumeCronJob(String id) {
        try {
            restClient.post()
                .uri("/api/v1/agent/cron/{id}/resume", id)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("resumeCronJob failed for id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /** h76: advance the delivery high-water mark after a successful delivery. */
    public boolean markDelivered(String id) {
        try {
            restClient.post()
                .uri("/api/v1/agent/cron/{id}/delivered", id)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("markDelivered failed for id={}: {}", id, e.getMessage());
            return false;
        }
    }
}