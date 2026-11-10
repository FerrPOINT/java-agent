package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.CronJobEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared model-drift rules for cron execution and the configuration preview. */
public final class CronModelDriftPolicy {
    private CronModelDriftPolicy() {}

    public static boolean enabled(Map<String, Object> config) {
        Map<?, ?> cron = cronConfig(config);
        return cron.get("model_drift_guard") != Boolean.FALSE;
    }

    public static List<String> axes(CronJobEntity job, String currentProvider, String currentModel,
                                    Map<String, Object> config) {
        Map<?, ?> cron = cronConfig(config);
        List<String> axes = new ArrayList<>(2);
        if (drifted(job.getModelProvider(), job.getProviderSnapshot(), currentProvider, cron.get("model_provider"))) {
            axes.add("provider");
        }
        if (drifted(job.getModelName(), job.getModelSnapshot(), currentModel, cron.get("model"))) {
            axes.add("model");
        }
        return axes;
    }

    private static boolean drifted(String pinned, String snapshot, String current, Object fleetDefault) {
        if (configText(fleetDefault) != null || (pinned != null && !pinned.isBlank())) {
            return false;
        }
        String previous = configText(snapshot);
        String resolved = configText(current);
        return previous != null && resolved != null
            && !previous.toLowerCase(Locale.ROOT).equals(resolved.toLowerCase(Locale.ROOT));
    }

    private static Map<?, ?> cronConfig(Map<String, Object> config) {
        return config.get("cron") instanceof Map<?, ?> cron ? cron : Map.of();
    }

    private static String configText(Object value) {
        return value instanceof String text && !text.trim().isBlank() ? text.trim() : null;
    }
}
