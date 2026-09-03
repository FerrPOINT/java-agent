package com.azhukov.agent.service;

import com.azhukov.agent.api.dto.ChatRequest;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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

    @Test
    void runtimeSelectionOverridesDefaultStampedSessionModelForNewJavaSessions() {
        AgentProperties properties = new AgentProperties();
        properties.getModel().setProvider("openai-compatible");
        properties.getModel().setModelName("configured-default");
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService();
        runtimeConfigService.setModelSelection(
            "openrouter",
            "anthropic/claude-sonnet-4-5",
            "https://openrouter.example/api/v1",
            "secret");
        Session session = new Session(
            UUID.randomUUID(),
            "user",
            "New chat",
            "openai-compatible",
            "configured-default",
            null,
            java.util.Map.of());

        ModelRequestOptions options = RuntimeModelOptionsResolver.resolve(
            properties,
            ChatRequest.simple(session.id(), "hello", null, 1_000L),
            session,
            runtimeConfigService);

        assertThat(options.modelName()).isEqualTo("anthropic/claude-sonnet-4-5");
        assertThat(options.provider()).isEqualTo("openrouter");
        assertThat(options.baseUrl()).isEqualTo("https://openrouter.example/api/v1");
        assertThat(options.apiKey()).isEqualTo("secret");
        assertThat(RuntimeModelOptionsResolver.modelUsed(
            properties, runtimeConfigService, session, "unknown"))
            .isEqualTo("anthropic/claude-sonnet-4-5");
    }

    @Test
    void explicitSessionModelStillWinsOverRuntimeSelection() {
        AgentProperties properties = new AgentProperties();
        properties.getModel().setModelName("configured-default");
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService();
        runtimeConfigService.setModelSelection("openrouter", "runtime-model", null, null);
        Session session = new Session(
            UUID.randomUUID(),
            "user",
            "Pinned",
            "browser",
            "session-model",
            null,
            java.util.Map.of());

        ModelRequestOptions options = RuntimeModelOptionsResolver.resolve(
            properties,
            ChatRequest.simple(session.id(), "hello", null, 1_000L),
            session,
            runtimeConfigService);

        assertThat(options.modelName()).isEqualTo("session-model");
        assertThat(options.provider()).isEqualTo("browser");
        assertThat(RuntimeModelOptionsResolver.modelUsed(
            properties, runtimeConfigService, session, "unknown"))
            .isEqualTo("session-model");
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
