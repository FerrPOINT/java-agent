package com.azhukov.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch-coverage tests for {@link RuntimeConfigService} and {@link TurnUsageCollector}.
 */
class RuntimeConfigAndTurnUsageTest {

    // ── RuntimeConfigService ──

    @Test
    void setModelOverrideStoresValue() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("gpt-4o");
        assertThat(service.getModelOverride()).isEqualTo("gpt-4o");
    }

    @Test
    void setModelOverrideWithNullClearsOverride() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("gpt-4o");
        service.setModelOverride(null);
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void setModelOverrideWithBlankClearsOverride() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("gpt-4o");
        service.setModelOverride("  ");
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void clearModelOverrideClearsValue() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("gpt-4o");
        service.clearModelOverride();
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void getModelOverrideReturnsNullWhenNotSet() {
        RuntimeConfigService service = new RuntimeConfigService();
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void setModelOverrideWithEmptyStringClears() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("gpt-4o");
        service.setModelOverride("");
        assertThat(service.getModelOverride()).isNull();
    }

    // ── TurnUsageCollector ──

    @Test
    void recordAndRetrieveReturnsValues() {
        TurnUsageCollector collector = new TurnUsageCollector();
        collector.record(100, 50);
        int[] result = collector.getAndClear();
        assertThat(result).isNotNull();
        assertThat(result[0]).isEqualTo(100);
        assertThat(result[1]).isEqualTo(50);
    }

    @Test
    void getAndClearReturnsNullWhenNothingRecorded() {
        TurnUsageCollector collector = new TurnUsageCollector();
        assertThat(collector.getAndClear()).isNull();
    }

    @Test
    void getAndClearClearsAfterRetrieval() {
        TurnUsageCollector collector = new TurnUsageCollector();
        collector.record(200, 100);
        collector.getAndClear();
        assertThat(collector.getAndClear()).isNull();
    }

    @Test
    void recordOverwritesPreviousValue() {
        TurnUsageCollector collector = new TurnUsageCollector();
        collector.record(100, 50);
        collector.record(300, 200);
        int[] result = collector.getAndClear();
        assertThat(result[0]).isEqualTo(300);
        assertThat(result[1]).isEqualTo(200);
    }

    @Test
    void recordWithZeroValues() {
        TurnUsageCollector collector = new TurnUsageCollector();
        collector.record(0, 0);
        int[] result = collector.getAndClear();
        assertThat(result[0]).isZero();
        assertThat(result[1]).isZero();
    }
}