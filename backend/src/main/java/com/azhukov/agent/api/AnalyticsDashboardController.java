package com.azhukov.agent.api;

import com.azhukov.agent.persistence.entity.UsageEntity;
import com.azhukov.agent.persistence.repository.UsageRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@Tag(name = "Hermes-compatible", description = "Dashboard analytics compatibility")
public class AnalyticsDashboardController {

    private final UsageRepository usageRepository;

    @GetMapping("/models")
    @Operation(summary = "Return per-model token analytics in Hermes dashboard shape")
    public ResponseEntity<Map<String, Object>> models(
        @RequestParam(name = "days", defaultValue = "30") String days,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        int periodDays;
        try {
            periodDays = parseDays(days);
        } catch (IllegalArgumentException e) {
            return unprocessable(e.getMessage());
        }
        Instant end = Instant.now().plusSeconds(1);
        Instant start = end.minus(periodDays, ChronoUnit.DAYS);
        List<UsageEntity> rows = usageRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(start, end);

        List<Map<String, Object>> models = byModel(rows).stream()
            .map(AnalyticsDashboardController::toModelAnalyticsPayload)
            .toList();
        Totals totals = totals(rows);

        Map<String, Object> totalsPayload = new LinkedHashMap<>();
        totalsPayload.put("distinct_models", rows.stream()
            .map(AnalyticsDashboardController::modelName)
            .filter(model -> !"unknown".equals(model))
            .distinct()
            .count());
        totalsPayload.put("total_input", totals.inputTokens);
        totalsPayload.put("total_output", totals.outputTokens);
        totalsPayload.put("total_cache_read", totals.cacheReadTokens);
        totalsPayload.put("total_reasoning", 0);
        totalsPayload.put("total_estimated_cost", totals.estimatedCost);
        totalsPayload.put("total_actual_cost", 0.0);
        totalsPayload.put("total_sessions", totals.sessionCount());
        totalsPayload.put("total_api_calls", totals.apiCalls);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("models", models);
        response.put("totals", totalsPayload);
        response.put("period_days", periodDays);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/usage")
    @Operation(summary = "Return token usage analytics in Hermes dashboard shape")
    public ResponseEntity<Map<String, Object>> usage(
        @RequestParam(name = "days", defaultValue = "30") String days,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        int periodDays;
        try {
            periodDays = parseDays(days);
        } catch (IllegalArgumentException e) {
            return unprocessable(e.getMessage());
        }
        Instant end = Instant.now().plusSeconds(1);
        Instant start = end.minus(periodDays, ChronoUnit.DAYS);
        List<UsageEntity> rows = usageRepository.findByCreatedAtBetweenOrderByCreatedAtAsc(start, end);

        List<Map<String, Object>> daily = daily(rows);
        List<Map<String, Object>> byModel = byModel(rows);
        Totals totals = totals(rows);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("daily", daily);
        response.put("by_model", byModel);
        response.put("by_task", List.of());
        response.put("totals", totals.toPayload());
        response.put("period_days", periodDays);
        response.put("skills", emptySkills());
        response.put("tools", List.of());
        return ResponseEntity.ok(response);
    }

    private static List<Map<String, Object>> daily(List<UsageEntity> rows) {
        Map<LocalDate, Aggregate> byDay = new LinkedHashMap<>();
        for (UsageEntity row : rows) {
            Instant createdAt = row.getCreatedAt();
            if (createdAt == null) {
                continue;
            }
            LocalDate day = createdAt.atZone(ZoneOffset.UTC).toLocalDate();
            byDay.computeIfAbsent(day, ignored -> new Aggregate()).add(row);
        }
        return byDay.entrySet().stream()
            .map(entry -> entry.getValue().toDailyPayload(entry.getKey()))
            .toList();
    }

    private static List<Map<String, Object>> byModel(List<UsageEntity> rows) {
        Map<String, Aggregate> byModel = new LinkedHashMap<>();
        for (UsageEntity row : rows) {
            String model = modelName(row);
            byModel.computeIfAbsent(model, ignored -> new Aggregate()).add(row);
        }
        return byModel.entrySet().stream()
            .sorted(Comparator
                .<Map.Entry<String, Aggregate>>comparingLong(entry -> entry.getValue().totalTokens())
                .reversed()
            .thenComparing(Map.Entry::getKey))
            .map(entry -> entry.getValue().toModelPayload(entry.getKey()))
            .toList();
    }

    private static Map<String, Object> toModelAnalyticsPayload(Map<String, Object> modelRow) {
        long inputTokens = ((Number) modelRow.getOrDefault("input_tokens", 0L)).longValue();
        long outputTokens = ((Number) modelRow.getOrDefault("output_tokens", 0L)).longValue();
        long sessions = ((Number) modelRow.getOrDefault("sessions", 0L)).longValue();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", modelRow.get("model"));
        response.put("provider", "");
        response.put("input_tokens", inputTokens);
        response.put("output_tokens", outputTokens);
        response.put("cache_read_tokens", modelRow.getOrDefault("cache_read_tokens", 0L));
        response.put("reasoning_tokens", 0);
        response.put("estimated_cost", modelRow.getOrDefault("estimated_cost", 0.0));
        response.put("actual_cost", 0.0);
        response.put("sessions", sessions);
        response.put("api_calls", modelRow.getOrDefault("api_calls", 0L));
        response.put("tool_calls", 0);
        response.put("last_used_at", modelRow.getOrDefault("last_used_at", 0L));
        response.put("avg_tokens_per_session", sessions > 0 ? ((double) (inputTokens + outputTokens) / sessions) : 0.0);
        response.put("capabilities", Map.of());
        return response;
    }

    private static Totals totals(List<UsageEntity> rows) {
        Totals totals = new Totals();
        rows.forEach(totals::add);
        return totals;
    }

    private static Map<String, Object> emptySkills() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("distinct_skills_used", 0);
        summary.put("total_skill_actions", 0);
        summary.put("total_skill_edits", 0);
        summary.put("total_skill_loads", 0);
        return Map.of(
            "summary", summary,
            "top_skills", List.of());
    }

    private static class Aggregate {
        protected long inputTokens;
        protected long outputTokens;
        protected long cacheReadTokens;
        protected double estimatedCost;
        protected long apiCalls;
        protected long lastUsedAt;
        private final Set<UUID> sessions = new LinkedHashSet<>();
        private long recordsWithoutSession;

        void add(UsageEntity row) {
            inputTokens += row.getPromptTokens();
            outputTokens += row.getCompletionTokens();
            cacheReadTokens += row.getCacheReadTokens();
            estimatedCost += row.getCost() != null ? row.getCost() : 0.0;
            apiCalls++;
            if (row.getSessionId() != null) {
                sessions.add(row.getSessionId());
            } else {
                recordsWithoutSession++;
            }
            if (row.getCreatedAt() != null) {
                lastUsedAt = Math.max(lastUsedAt, row.getCreatedAt().getEpochSecond());
            }
        }

        long totalTokens() {
            return inputTokens + outputTokens;
        }

        long sessionCount() {
            return sessions.size() + recordsWithoutSession;
        }

        Map<String, Object> toDailyPayload(LocalDate day) {
            Map<String, Object> row = basePayload();
            row.put("day", day.toString());
            row.put("cache_read_tokens", cacheReadTokens);
            row.put("reasoning_tokens", 0);
            row.put("actual_cost", 0.0);
            return row;
        }

        Map<String, Object> toModelPayload(String model) {
            Map<String, Object> row = basePayload();
            row.put("model", model);
            row.put("cache_read_tokens", cacheReadTokens);
            row.put("last_used_at", lastUsedAt);
            return row;
        }

        private Map<String, Object> basePayload() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("input_tokens", inputTokens);
            row.put("output_tokens", outputTokens);
            row.put("estimated_cost", estimatedCost);
            row.put("sessions", sessionCount());
            row.put("api_calls", apiCalls);
            return row;
        }
    }

    private static final class Totals extends Aggregate {
        private Map<String, Object> toPayload() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("total_input", inputTokens);
            row.put("total_output", outputTokens);
            row.put("total_cache_read", cacheReadTokens);
            row.put("total_reasoning", 0);
            row.put("total_estimated_cost", estimatedCost);
            row.put("total_actual_cost", 0.0);
            row.put("total_sessions", sessionCount());
            row.put("total_api_calls", apiCalls);
            return row;
        }
    }

    private static String modelName(UsageEntity row) {
        return row.getModel() == null || row.getModel().isBlank()
            ? "unknown"
            : row.getModel().trim();
    }

    private static int parseDays(String raw) {
        try {
            int parsed = Integer.parseInt(raw == null ? "" : raw.trim());
            if (parsed < 1 || parsed > 365) {
                throw new IllegalArgumentException("days must be between 1 and 365");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("days must be an integer");
        }
    }

    private static ResponseEntity<Map<String, Object>> unprocessable(String detail) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of("detail", detail, "error", detail));
    }
}
