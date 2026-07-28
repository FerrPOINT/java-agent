package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.repository.UsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks token usage per turn/session/day.
 * Persists usage records to the {@code usage_log} table via {@link UsageRepository}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UsageTracker {

    private final UsageRepository usageRepository;

    /**
     * Record a single turn's token usage.
     *
     * @param sessionId         the session UUID
     * @param userId             the user identifier
     * @param model              the model name
     * @param promptTokens       input token count
     * @param completionTokens   output token count
     */
    public void recordTurn(UUID sessionId, String userId, String model, int promptTokens, int completionTokens) {
        try {
            UsageEntity entity = new UsageEntity();
            entity.setSessionId(sessionId);
            entity.setUserId(userId);
            entity.setModel(model);
            entity.setPromptTokens(promptTokens);
            entity.setCompletionTokens(completionTokens);
            entity.setTotalTokens(promptTokens + completionTokens);
            entity.setCost(computeCost(model, promptTokens, completionTokens));
            entity.setCreatedAt(Instant.now());
            usageRepository.save(entity);
            log.debug("Recorded usage: session={}, model={}, prompt={}, completion={}, total={}",
                sessionId, model, promptTokens, completionTokens, entity.getTotalTokens());
        } catch (Exception e) {
            log.warn("Failed to record usage for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Get aggregated usage for a specific session.
     *
     * @param sessionId the session UUID
     * @return aggregated usage DTO
     */
    public UsageDto getSessionUsage(UUID sessionId) {
        List<UsageEntity> records = usageRepository.findBySessionId(sessionId);
        int messageCount = records.size();
        int totalTokens = records.stream().mapToInt(UsageEntity::getTotalTokens).sum();
        return new UsageDto(sessionId, messageCount, totalTokens);
    }

    /**
     * Get aggregated usage for a specific day.
     *
     * @param date the day to query
     * @return aggregated usage DTO
     */
    public UsageDto getDailyUsage(LocalDate date) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // Query all records for the day — userId is null here for a global daily total
        List<UsageEntity> records = usageRepository.findByUserIdAndCreatedAtBetween(null, start, end);
        if (records.isEmpty()) {
            // Fallback: scan all records for the day (null userId may not match in some DBs)
            records = usageRepository.findAll().stream()
                .filter(e -> e.getCreatedAt() != null
                    && !e.getCreatedAt().isBefore(start)
                    && e.getCreatedAt().isBefore(end))
                .toList();
        }
        int messageCount = records.size();
        int totalTokens = records.stream().mapToInt(UsageEntity::getTotalTokens).sum();
        return new UsageDto(null, messageCount, totalTokens);
    }

    /**
     * Get usage insights for a user.
     *
     * @param userId the user identifier (may be null for global insights)
     * @return insights DTO with totals and per-model breakdown
     */
    public InsightsDto getInsights(String userId) {
        List<UsageEntity> allRecords = usageRepository.findAll();
        if (userId != null && !userId.isBlank()) {
            allRecords = allRecords.stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
        }
        int totalTokens = allRecords.stream().mapToInt(UsageEntity::getTotalTokens).sum();
        int totalMessages = allRecords.size();
        Map<String, Integer> byModel = new HashMap<>();
        for (UsageEntity e : allRecords) {
            String model = e.getModel() != null ? e.getModel() : "unknown";
            byModel.merge(model, e.getTotalTokens(), Integer::sum);
        }
        return new InsightsDto(totalTokens, totalMessages, byModel);
    }

    private Double computeCost(String model, int promptTokens, int completionTokens) {
        // Simple cost estimation — $0.01 per 1K tokens (placeholder)
        return (promptTokens + completionTokens) / 1000.0 * 0.01;
    }
}