package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.InsightsDto;
import com.azhukov.agent.api.dto.UsageDto;
import com.azhukov.agent.core.model.TokenUsage;
import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.repository.UsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks token usage per turn/session/day.
 * Supports per-model pricing and real token counts from API responses
 * (prompt_tokens, completion_tokens, cache_read, cache_write, reasoning).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UsageTracker {

    private final UsageRepository usageRepository;

    // Per-model pricing map (USD per 1M tokens)
    // Format: model_name -> [input_cost_per_1M, output_cost_per_1M, cache_read_cost_per_1M]
    private static final Map<String, double[]> MODEL_PRICING = Map.ofEntries(
        Map.entry("gpt-4o", new double[]{2.50, 10.00, 1.25}),
        Map.entry("gpt-4o-mini", new double[]{0.150, 0.600, 0.075}),
        Map.entry("gpt-4o-2024-08-06", new double[]{2.50, 10.00, 1.25}),
        Map.entry("gpt-4-turbo", new double[]{10.00, 30.00, 0.0}),
        Map.entry("gpt-4", new double[]{30.00, 60.00, 0.0}),
        Map.entry("gpt-3.5-turbo", new double[]{0.50, 1.50, 0.0}),
        Map.entry("o1", new double[]{15.00, 60.00, 7.50}),
        Map.entry("o1-mini", new double[]{3.00, 12.00, 1.50}),
        Map.entry("o1-preview", new double[]{15.00, 60.00, 7.50}),
        Map.entry("o3-mini", new double[]{3.00, 12.00, 1.50}),
        // Anthropic models
        Map.entry("claude-opus-4-8", new double[]{5.00, 25.00, 0.50}),
        Map.entry("claude-opus-4-8-fast", new double[]{10.00, 50.00, 1.00}),
        Map.entry("claude-sonnet-4-5", new double[]{3.00, 15.00, 0.30}),
        Map.entry("claude-sonnet-4-5-20250514", new double[]{3.00, 15.00, 0.30}),
        Map.entry("claude-3-5-sonnet", new double[]{3.00, 15.00, 0.30}),
        Map.entry("claude-3-5-sonnet-20241022", new double[]{3.00, 15.00, 0.30}),
        Map.entry("claude-3-5-haiku", new double[]{0.80, 4.00, 0.08}),
        Map.entry("claude-3-opus", new double[]{15.00, 75.00, 1.50}),
        Map.entry("claude-3-haiku", new double[]{0.25, 1.25, 0.03}),
        // Kimi models
        Map.entry("kimi-k2", new double[]{1.00, 4.00, 0.10}),
        Map.entry("moonshot-v1-8k", new double[]{1.67, 8.33, 0.0}),
        Map.entry("moonshot-v1-32k", new double[]{3.33, 13.33, 0.0}),
        Map.entry("moonshot-v1-128k", new double[]{8.33, 33.33, 0.0}),
        // Gemini models
        Map.entry("gemini-2.0-flash", new double[]{0.10, 0.40, 0.025}),
        Map.entry("gemini-2.0-flash-lite", new double[]{0.075, 0.30, 0.01875}),
        Map.entry("gemini-1.5-pro", new double[]{1.25, 5.00, 0.3125}),
        Map.entry("gemini-1.5-flash", new double[]{0.075, 0.30, 0.01875}),
        Map.entry("gemini-1.5-flash-8b", new double[]{0.0375, 0.15, 0.01}),
        // Deepseek models
        Map.entry("deepseek-chat", new double[]{0.27, 1.10, 0.07}),
        Map.entry("deepseek-coder", new double[]{0.27, 1.10, 0.07}),
        Map.entry("deepseek-reasoner", new double[]{0.55, 2.19, 0.14}),
        // Ollama / local models (free)
        Map.entry("llama-3.1-70b", new double[]{0.0, 0.0, 0.0}),
        Map.entry("llama-3.1-8b", new double[]{0.0, 0.0, 0.0}),
        Map.entry("qwen-2.5-72b", new double[]{0.0, 0.0, 0.0}),
        Map.entry("glm-5.2", new double[]{0.0, 0.0, 0.0})
    );

    private static final double DEFAULT_INPUT_COST = 0.50;
    private static final double DEFAULT_OUTPUT_COST = 1.50;
    private static final double DEFAULT_CACHE_READ_COST = 0.05;

    /**
     * Record a single turn's token usage (basic).
     */
    public void recordTurn(UUID sessionId, String userId, String model, int promptTokens, int completionTokens) {
        recordTurn(sessionId, userId, model, promptTokens, completionTokens, 0, 0);
    }

    /**
     * Record a turn with cache token tracking.
     */
    public void recordTurn(UUID sessionId, String userId, String model, int promptTokens, int completionTokens,
                           int cacheReadTokens, int cacheWriteTokens) {
        recordTurn(sessionId, userId, model, TokenUsage.of(promptTokens, completionTokens, cacheReadTokens, cacheWriteTokens, 0));
    }

    /**
     * Record a turn from real API response token usage.
     * Uses real token counts (prompt_tokens, completion_tokens, cache_read, cache_write, reasoning).
     */
    public void recordTurn(UUID sessionId, String userId, String model, TokenUsage usage) {
        try {
            int promptTokens = usage.promptTokens();
            int completionTokens = usage.completionTokens();
            int cacheReadTokens = usage.cacheReadTokens();
            int cacheWriteTokens = usage.cacheWriteTokens();

            UsageEntity entity = new UsageEntity();
            entity.setSessionId(sessionId);
            entity.setUserId(userId);
            entity.setModel(model);
            entity.setPromptTokens(promptTokens);
            entity.setCompletionTokens(completionTokens);
            entity.setTotalTokens(usage.totalTokens());
            entity.setCost(computeCost(model, promptTokens, completionTokens, cacheReadTokens, cacheWriteTokens));
            entity.setCacheReadTokens(cacheReadTokens);
            entity.setCacheWriteTokens(cacheWriteTokens);
            entity.setCreatedAt(Instant.now());
            usageRepository.save(entity);
            log.debug("Recorded usage: session={}, model={}, prompt={}, completion={}, total={}, cacheRead={}, cacheWrite={}, reasoning={}, cost={}",
                sessionId, model, promptTokens, completionTokens, entity.getTotalTokens(),
                cacheReadTokens, cacheWriteTokens, usage.reasoningTokens(), entity.getCost());
        } catch (Exception e) {
            log.warn("Failed to record usage for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Get aggregated usage for a specific session.
     */
    public UsageDto getSessionUsage(UUID sessionId) {
        List<UsageEntity> records = usageRepository.findBySessionId(sessionId);
        int messageCount = records.size();
        int totalTokens = records.stream().mapToInt(UsageEntity::getTotalTokens).sum();
        return new UsageDto(sessionId, messageCount, totalTokens);
    }

    /**
     * Get aggregated usage for a specific day.
     */
    public UsageDto getDailyUsage(LocalDate date) {
        Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<UsageEntity> records = usageRepository.findByUserIdAndCreatedAtBetween(null, start, end);
        if (records.isEmpty()) {
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
     * Combined credits summary computed from a single {@code findAll()} call.
     * Avoids the N+1 pattern where {@link #getInsights(String)} and
     * {@link #getTotalCost(String)} each issue a separate full table scan.
     */
    public record CreditsSummary(int totalTokens, int totalMessages, double totalCost) {}

    /**
     * Get total tokens, total messages, and total cost in a single query.
     */
    public CreditsSummary getCreditsSummary(String userId) {
        List<UsageEntity> allRecords = loadAllRecords(userId);
        int totalTokens = allRecords.stream().mapToInt(UsageEntity::getTotalTokens).sum();
        int totalMessages = allRecords.size();
        double totalCost = allRecords.stream()
            .mapToDouble(e -> e.getCost() != null ? e.getCost() : 0.0)
            .sum();
        return new CreditsSummary(totalTokens, totalMessages, totalCost);
    }

    /**
     * Get usage insights with per-model cost breakdown.
     */
    public InsightsDto getInsights(String userId) {
        List<UsageEntity> allRecords = loadAllRecords(userId);
        int totalTokens = allRecords.stream().mapToInt(UsageEntity::getTotalTokens).sum();
        int totalMessages = allRecords.size();
        Map<String, Integer> byModel = new HashMap<>();
        for (UsageEntity e : allRecords) {
            String model = e.getModel() != null ? e.getModel() : "unknown";
            byModel.merge(model, e.getTotalTokens(), Integer::sum);
        }
        return new InsightsDto(totalTokens, totalMessages, byModel);
    }

    /**
     * Get cost breakdown per model.
     */
    public Map<String, Double> getCostBreakdown(String userId) {
        List<UsageEntity> allRecords = loadAllRecords(userId);
        Map<String, Double> costByModel = new HashMap<>();
        for (UsageEntity e : allRecords) {
            String model = e.getModel() != null ? e.getModel() : "unknown";
            double cost = e.getCost() != null ? e.getCost() : 0.0;
            costByModel.merge(model, cost, Double::sum);
        }
        return costByModel;
    }

    /**
     * Get total cost across all models.
     */
    public double getTotalCost(String userId) {
        return getCostBreakdown(userId).values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /**
     * Load all usage records, optionally filtered by userId.
     * Extracted to a helper so {@link #getInsights}, {@link #getCostBreakdown},
     * and {@link #getCreditsSummary} all share a single {@code findAll()} call.
     */
    private List<UsageEntity> loadAllRecords(String userId) {
        List<UsageEntity> allRecords = usageRepository.findAll();
        if (userId != null && !userId.isBlank()) {
            allRecords = allRecords.stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
        }
        return allRecords;
    }

    /**
     * Compute cost based on model-specific pricing.
     */
    private Double computeCost(String model, int promptTokens, int completionTokens,
                               int cacheReadTokens, int cacheWriteTokens) {
        double[] pricing = MODEL_PRICING.getOrDefault(model,
            MODEL_PRICING.getOrDefault(normalizeModel(model), new double[]{DEFAULT_INPUT_COST, DEFAULT_OUTPUT_COST, DEFAULT_CACHE_READ_COST}));

        double inputCost = (promptTokens / 1_000_000.0) * pricing[0];
        double outputCost = (completionTokens / 1_000_000.0) * pricing[1];
        double cacheReadCost = (cacheReadTokens / 1_000_000.0) * pricing[2];
        double cacheWriteCost = (cacheWriteTokens / 1_000_000.0) * (pricing[0] * 1.25);

        double total = inputCost + outputCost + cacheReadCost + cacheWriteCost;
        return BigDecimal.valueOf(total).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private String normalizeModel(String model) {
        if (model == null) return "";
        String lower = model.toLowerCase();
        if (lower.startsWith("gpt-4o-mini")) return "gpt-4o-mini";
        if (lower.startsWith("gpt-4o")) return "gpt-4o";
        if (lower.startsWith("gpt-4-turbo")) return "gpt-4-turbo";
        if (lower.startsWith("gpt-4")) return "gpt-4";
        if (lower.startsWith("gpt-3.5")) return "gpt-3.5-turbo";
        if (lower.startsWith("claude-3-5-sonnet")) return "claude-3-5-sonnet";
        if (lower.startsWith("claude-3-5-haiku")) return "claude-3-5-haiku";
        if (lower.startsWith("claude-3-opus")) return "claude-3-opus";
        if (lower.startsWith("claude-3-haiku")) return "claude-3-haiku";
        if (lower.startsWith("claude-sonnet")) return "claude-sonnet-4-5";
        if (lower.startsWith("o1-mini")) return "o1-mini";
        if (lower.startsWith("o1-preview")) return "o1-preview";
        if (lower.startsWith("o1")) return "o1";
        if (lower.startsWith("o3-mini")) return "o3-mini";
        if (lower.startsWith("gemini-2.0-flash-lite")) return "gemini-2.0-flash-lite";
        if (lower.startsWith("gemini-2.0-flash")) return "gemini-2.0-flash";
        if (lower.startsWith("gemini-1.5-pro")) return "gemini-1.5-pro";
        if (lower.startsWith("gemini-1.5-flash-8b")) return "gemini-1.5-flash-8b";
        if (lower.startsWith("gemini-1.5-flash")) return "gemini-1.5-flash";
        if (lower.startsWith("deepseek-coder")) return "deepseek-coder";
        if (lower.startsWith("deepseek-reasoner")) return "deepseek-reasoner";
        if (lower.startsWith("deepseek")) return "deepseek-chat";
        if (lower.startsWith("kimi")) return "kimi-k2";
        if (lower.startsWith("moonshot-v1-8k")) return "moonshot-v1-8k";
        if (lower.startsWith("moonshot-v1-32k")) return "moonshot-v1-32k";
        if (lower.startsWith("moonshot-v1-128k")) return "moonshot-v1-128k";
        return "";
    }
}