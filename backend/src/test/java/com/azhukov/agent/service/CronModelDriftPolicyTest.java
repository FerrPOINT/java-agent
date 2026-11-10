package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.CronJobEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CronModelDriftPolicyTest {
    @Test
    void pinnedAndFleetDefaultAxesDoNotDrift() {
        var job = new CronJobEntity();
        job.setProviderSnapshot("old-provider");
        job.setModelSnapshot("old-model");
        assertThat(CronModelDriftPolicy.axes(job, "new-provider", "new-model", Map.of()))
            .containsExactly("provider", "model");
        assertThat(CronModelDriftPolicy.axes(job, " OLD-PROVIDER ", "OLD-MODEL", Map.of())).isEmpty();

        job.setModelProvider("pinned-provider");
        var cron = Map.<String, Object>of("cron", Map.of("model", "fleet-model", "model_drift_guard", false));
        assertThat(CronModelDriftPolicy.axes(job, "new-provider", "new-model", cron)).isEmpty();
        assertThat(CronModelDriftPolicy.enabled(cron)).isFalse();
        assertThat(CronModelDriftPolicy.enabled(Map.of())).isTrue();
    }
}
