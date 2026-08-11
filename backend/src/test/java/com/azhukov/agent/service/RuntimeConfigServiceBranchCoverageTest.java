package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch-coverage tests for RuntimeConfigService — covers null/blank model,
 * clearing override, and get/set cycles.
 */
class RuntimeConfigServiceBranchCoverageTest {

    @Test
    void setModelOverrideNullClearsOverride() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("test-model");
        assertThat(service.getModelOverride()).isEqualTo("test-model");

        service.setModelOverride(null);
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void setModelOverrideBlankClearsOverride() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("test-model");
        assertThat(service.getModelOverride()).isEqualTo("test-model");

        service.setModelOverride("  ");
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void setModelOverrideValidSetsValue() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("gpt-4o");
        assertThat(service.getModelOverride()).isEqualTo("gpt-4o");
    }

    @Test
    void clearModelOverrideRemovesValue() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("claude-3");
        service.clearModelOverride();
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void clearModelOverrideWhenNoneSetIsNoOp() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.clearModelOverride();
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void setModelOverrideEmptyStringClearsOverride() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("model-a");
        service.setModelOverride("");
        assertThat(service.getModelOverride()).isNull();
    }

    @Test
    void setModelOverrideMultipleTimesUpdatesValue() {
        RuntimeConfigService service = new RuntimeConfigService();
        service.setModelOverride("model-a");
        service.setModelOverride("model-b");
        assertThat(service.getModelOverride()).isEqualTo("model-b");
    }
}