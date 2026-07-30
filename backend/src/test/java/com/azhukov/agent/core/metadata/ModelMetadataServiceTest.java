package com.azhukov.agent.core.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelMetadataServiceTest {

    private final ModelMetadataService service = new ModelMetadataService();

    @Test
    void stripProviderPrefix_stripsKnownPrefix() {
        assertThat(service.stripProviderPrefix("openrouter:google/gemini-3-flash")).isEqualTo("google/gemini-3-flash");
        assertThat(service.stripProviderPrefix("anthropic:claude-3-5-sonnet")).isEqualTo("claude-3-5-sonnet");
        assertThat(service.stripProviderPrefix("nous:gpt-4o")).isEqualTo("gpt-4o");
    }

    @Test
    void stripProviderPrefix_preservesOllamaTags() {
        assertThat(service.stripProviderPrefix("qwen3.5:27b")).isEqualTo("qwen3.5:27b");
        assertThat(service.stripProviderPrefix("deepseek:latest")).isEqualTo("deepseek:latest");
    }

    @Test
    void stripProviderPrefix_noPrefix_returnsAsIs() {
        assertThat(service.stripProviderPrefix("gpt-4o")).isEqualTo("gpt-4o");
        assertThat(service.stripProviderPrefix("http://localhost:8080")).isEqualTo("http://localhost:8080");
    }

    @Test
    void detectContextLength_knownModel() {
        assertThat(service.detectContextLength("gpt-4o")).isEqualTo(128_000);
        assertThat(service.detectContextLength("claude-opus-4-8")).isEqualTo(1_000_000);
        assertThat(service.detectContextLength("gemini-1.5-pro")).isEqualTo(1_048_576);
    }

    @Test
    void detectContextLength_unknownModel_returnsDefault() {
        assertThat(service.detectContextLength("some-unknown-model")).isEqualTo(ModelMetadataService.DEFAULT_FALLBACK_CONTEXT);
    }

    @Test
    void detectContextLength_stripsProviderPrefix() {
        int direct = service.detectContextLength("claude-opus-4-8");
        int prefixed = service.detectContextLength("anthropic:claude-opus-4-8");
        assertThat(direct).isEqualTo(prefixed);
    }

    @Test
    void detectContextLength_nullOrBlank_returnsDefault() {
        assertThat(service.detectContextLength(null)).isEqualTo(ModelMetadataService.DEFAULT_FALLBACK_CONTEXT);
        assertThat(service.detectContextLength("")).isEqualTo(ModelMetadataService.DEFAULT_FALLBACK_CONTEXT);
        assertThat(service.detectContextLength("  ")).isEqualTo(ModelMetadataService.DEFAULT_FALLBACK_CONTEXT);
    }

    @Test
    void detectContextLength_cachesResult() {
        ModelMetadataService ModelMetadataService = new ModelMetadataService();
        int first = ModelMetadataService.detectContextLength("gpt-4o");
        int second = ModelMetadataService.detectContextLength("gpt-4o");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void estimateTokens_positiveForNonEmptyText() {
        assertThat(service.estimateTokens("hello world", "gpt-4o")).isGreaterThan(0);
        assertThat(service.estimateTokens("", "gpt-4o")).isEqualTo(0);
        assertThat(service.estimateTokens(null, "gpt-4o")).isEqualTo(0);
    }

    @Test
    void getMetadata_returnsFullMetadata() {
        var meta = service.getMetadata("anthropic:claude-opus-4-8");
        assertThat(meta.originalName()).isEqualTo("anthropic:claude-opus-4-8");
        assertThat(meta.strippedName()).isEqualTo("claude-opus-4-8");
        assertThat(meta.contextLength()).isEqualTo(1_000_000);
    }

    @Test
    void getMetadata_emptyModel_returnsDefault() {
        var meta = service.getMetadata("");
        assertThat(meta.contextLength()).isEqualTo(ModelMetadataService.DEFAULT_FALLBACK_CONTEXT);
    }

    @Test
    void modelMetadata_estimateTokensUsesCharsPerToken() {
        var meta = new ModelMetadataService.ModelMetadata("test", "test", 128_000, 4);
        assertThat(meta.estimateTokens("hello world")).isGreaterThan(0);
        assertThat(meta.estimateTokens("")).isEqualTo(0);
    }

    @Test
    void clearCache_works() {
        service.detectContextLength("gpt-4o");
        service.clearCache();
        // Should still work after cache clear
        assertThat(service.detectContextLength("gpt-4o")).isEqualTo(128_000);
    }

    @Test
    void glmModelUsesLowerCharsPerToken() {
        var meta = service.getMetadata("glm-5.2");
        assertThat(meta.charsPerToken()).isEqualTo(3);
    }

    @Test
    void grokModelHasCorrectContextLength() {
        assertThat(service.detectContextLength("grok-4-fast")).isEqualTo(2_000_000);
        assertThat(service.detectContextLength("grok-3")).isEqualTo(131_072);
    }
}