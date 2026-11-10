package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.CronJobEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CronModelDriftPolicy {
    private CronModelDriftPolicy() {}

    public static boolean enabled(Map<String, Object> config) {
        Map<String, Object> cron = cronConfig(config);
        return cron.get("model_drift_guard") != Boolean.FALSE;
    }

    public static List<String> axes(CronJobEntity job,
                                                   String currentProvider,
                                                   String currentModel,
                                                   Map<String, Object> config) {
        List<String> axes = new ArrayList<>();
        if (cronAxisDrifted(
            "provider",
            job.getModelProvider(),
            job.getProviderSnapshot(),
            currentProvider,
            config)) {
            axes.add("provider");
        }
        if (cronAxisDrifted(
            "model",
            job.getModelName(),
            job.getModelSnapshot(),
            currentModel,
            config)) {
            axes.add("model");
        }
        return axes;
    }

    private static boolean cronAxisDrifted(String axis,
                                           String pinnedValue,
                                           String snapshotValue,
                                           String currentValue,
                                           Map<String, Object> config) {
        if (cronFleetDefaultCoversAxis(axis, config) || (pinnedValue != null && !pinnedValue.isBlank())) {
            return false;
        }
        String snapshot = configText(snapshotValue);
        String current = configText(currentValue);
        return snapshot != null && current != null && !snapshot.equalsIgnoreCase(current);
    }

    private static boolean cronFleetDefaultCoversAxis(String axis, Map<String, Object> config) {
        Map<String, Object> cron = cronConfig(config);
        String key = "model".equals(axis) ? "model" : "model_provider";
        return configText(cron.get(key)) != null;
    }

    private static Map<?, ?> cronConfig(Map<String, Object> config) {
        return config.get("cron") instanceof Map<?, ?> cron ? cron : Map.of();
    }

    private static String configText(Object value) {
        return value instanceof String text && !text.trim().isBlank() ? text.trim() : null;
    }
}
