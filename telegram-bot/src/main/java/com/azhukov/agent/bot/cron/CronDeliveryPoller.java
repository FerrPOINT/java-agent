package com.azhukov.agent.bot.cron;

import com.azhukov.agent.bot.client.TelegramClient;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.CronApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Cron output delivery poller (Hermes parity: gateway/delivery.py + delivery_ledger.py).
 *
 * <p>Backend cron jobs run headless into their own sessions — without this poller the
 * user NEVER sees the output. Every tick (30s) we list cron jobs, and for each job with
 * {@code deliverTo} pointing at this bot's chat whose {@code lastRunAt} is newer than
 * {@code lastDeliveredRunAt}, we fetch the run session's last assistant message and
 * deliver it, then advance the high-water mark via {@code POST /api/v1/agent/cron/{id}/delivered}.</p>
 *
 * <p>Failure nudge (Hermes cron/monitor parity): a job stuck in {@code error} with
 * consecutive failures ≥ threshold gets ONE attention message instead of per-error pings.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CronDeliveryPoller {

    private final CronApiClient cronApiClient;
    private final TelegramClient telegramClient;
    private final BotProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Chat this bot serves (single-user deployment: the owner's chat id). */
    private Long ownerChatId;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 20_000L)
    public void poll() {
        if (!properties.isCronDeliveryEnabled()) {
            return;
        }
        try {
            JsonNode jobs = cronApiClient.listCronJobs();
            if (jobs == null || !jobs.isArray()) {
                return;
            }
            for (JsonNode job : jobs) {
                deliverIfDue(job);
            }
        } catch (Exception e) {
            log.debug("Cron delivery poll failed: {}", e.getMessage());
        }
    }

    private void deliverIfDue(JsonNode job) {
        String jobId = job.path("id").asText(null);
        String deliverTo = job.path("deliverTo").asText(null);
        if (jobId == null || deliverTo == null || deliverTo.isBlank()) {
            return; // no delivery target — headless job, user reads it via session search
        }
        Long chatId = resolveChatId(deliverTo);
        if (chatId == null) {
            return;
        }

        Instant lastRun = parseInstant(job.path("lastRunAt").asText(null));
        Instant lastDelivered = parseInstant(job.path("lastDeliveredRunAt").asText(null));
        String name = job.path("name").asText("cron-job");
        String status = job.path("lastStatus").asText(null);

        // Error nudge (h76): one attention message at the threshold, not per failure.
        int consecutiveFailures = job.path("consecutiveFailures").asInt(0);
        if ("error".equals(status) && consecutiveFailures > 0
                && (lastDelivered == null || lastRun != null && lastRun.isAfter(lastDelivered))) {
            String lastError = job.path("lastError").asText("unknown");
            boolean sent = telegramClient.sendMessage(chatId,
                "⚠️ Automation needs attention: cron job '" + name + "' has failed "
                    + consecutiveFailures + " consecutive times. Last error: "
                    + truncate(lastError, 300)).isPresent();
            if (sent) {
                cronApiClient.markDelivered(jobId);
            }
            return;
        }

        // Success delivery: run finished after the last delivered mark.
        if (lastRun == null || (lastDelivered != null && !lastRun.isAfter(lastDelivered))) {
            return;
        }
        if (!"success".equals(status)) {
            return; // error path handled above; other statuses (running) wait
        }

        String sessionId = job.path("lastRunSessionId").asText(null);
        String output = sessionId != null ? fetchLastAssistantMessage(sessionId) : null;
        if (output == null) {
            output = "(cron job '" + name + "' completed — no output recorded)";
        }

        // R6 (Hermes response_filters.py is_autonomous_silence_response): a tick
        // that emitted a silence marker ([SILENT], whole/line/prefix) produced
        // nothing worth a human's attention — mark delivered WITHOUT sending.
        if (AutonomousSilenceFilter.isAutonomousSilence(output)) {
            log.info("Cron job '{}' stayed silent — skipping delivery, advancing mark", name);
            cronApiClient.markDelivered(jobId);
            return;
        }

        String header = "🕐 Cron: " + name + "\n\n";
        boolean sent = telegramClient.sendMessage(chatId, truncate(header + output, 4000)).isPresent();
        if (sent) {
            cronApiClient.markDelivered(jobId);
            log.info("Delivered cron output for job '{}' to chat {}", name, chatId);
        }
    }

    /** Fetch the last assistant message of the run session via the backend REST API. */
    private String fetchLastAssistantMessage(String sessionId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(properties.getBackendUrl()
                    + "/api/v2/sessions/" + sessionId + "/messages?limit=500"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("Session messages fetch for cron delivery: HTTP {}", resp.statusCode());
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(resp.body());
            JsonNode messages = root.path("messages");
            if (!messages.isArray()) {
                return null;
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                JsonNode m = messages.get(i);
                if ("assistant".equals(m.path("role").asText())) {
                    String content = m.path("content").asText(null);
                    return (content == null || content.isBlank()) ? null : content;
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("fetchLastAssistantMessage failed: {}", e.getMessage());
            return null;
        }
    }

    private Long resolveChatId(String deliverTo) {
        // Formats: "telegram:<chatId>", "telegram", or bare numeric chat id.
        String v = deliverTo.trim();
        if (v.startsWith("telegram:")) {
            v = v.substring("telegram:".length());
        }
        if (v.equals("telegram") || v.equals("origin")) {
            return ownerChat();
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return ownerChat();
        }
    }

    /** Lazily resolve the owner chat: configured owner id, else the first allowed user. */
    private Long ownerChat() {
        if (ownerChatId != null) {
            return ownerChatId;
        }
        var allowed = properties.getAuth().getAllowedUserIds();
        if (allowed != null && !allowed.isEmpty()) {
            try {
                ownerChatId = Long.parseLong(allowed.iterator().next().trim());
            } catch (NumberFormatException ignored) {
                // usernames configured instead of ids — owner chat unresolved until first message
            }
        }
        return ownerChatId;
    }

    private Instant parseInstant(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
