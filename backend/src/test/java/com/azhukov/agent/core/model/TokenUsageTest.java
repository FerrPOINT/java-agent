package com.azhukov.agent.core.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenUsageTest {

    @Test
    void of_basic() {
        TokenUsage usage = TokenUsage.of(100, 50);
        assertThat(usage.promptTokens()).isEqualTo(100);
        assertThat(usage.completionTokens()).isEqualTo(50);
        assertThat(usage.totalTokens()).isEqualTo(150);
        assertThat(usage.cacheReadTokens()).isEqualTo(0);
        assertThat(usage.cacheWriteTokens()).isEqualTo(0);
        assertThat(usage.reasoningTokens()).isEqualTo(0);
    }

    @Test
    void of_withCacheAndReasoning() {
        TokenUsage usage = TokenUsage.of(100, 50, 80, 20, 10);
        assertThat(usage.promptTokens()).isEqualTo(100);
        assertThat(usage.completionTokens()).isEqualTo(50);
        assertThat(usage.totalTokens()).isEqualTo(150);
        assertThat(usage.cacheReadTokens()).isEqualTo(80);
        assertThat(usage.cacheWriteTokens()).isEqualTo(20);
        assertThat(usage.reasoningTokens()).isEqualTo(10);
    }

    @Test
    void fromMap_openAiFormat() {
        Map<String, Object> usage = Map.of(
            "prompt_tokens", 200,
            "completion_tokens", 100,
            "total_tokens", 300
        );
        TokenUsage result = TokenUsage.fromMap(usage);
        assertThat(result.promptTokens()).isEqualTo(200);
        assertThat(result.completionTokens()).isEqualTo(100);
        assertThat(result.totalTokens()).isEqualTo(300);
    }

    @Test
    void fromMap_anthropicFormat() {
        Map<String, Object> usage = Map.of(
            "input_tokens", 150,
            "output_tokens", 80,
            "cache_read_input_tokens", 120,
            "cache_creation_input_tokens", 30
        );
        TokenUsage result = TokenUsage.fromMap(usage);
        assertThat(result.promptTokens()).isEqualTo(150);
        assertThat(result.completionTokens()).isEqualTo(80);
        assertThat(result.cacheReadTokens()).isEqualTo(120);
        assertThat(result.cacheWriteTokens()).isEqualTo(30);
    }

    @Test
    void fromMap_withReasoningTokens() {
        Map<String, Object> usage = Map.of(
            "prompt_tokens", 200,
            "completion_tokens", 100,
            "reasoning_tokens", 50
        );
        TokenUsage result = TokenUsage.fromMap(usage);
        assertThat(result.reasoningTokens()).isEqualTo(50);
    }

    @Test
    void fromMap_null_returnsZeros() {
        TokenUsage result = TokenUsage.fromMap(null);
        assertThat(result.promptTokens()).isEqualTo(0);
        assertThat(result.completionTokens()).isEqualTo(0);
    }

    @Test
    void fromMap_emptyMap_returnsZeros() {
        TokenUsage result = TokenUsage.fromMap(Map.of());
        assertThat(result.promptTokens()).isEqualTo(0);
    }

    @Test
    void fromMap_stringValues_parsed() {
        Map<String, Object> usage = Map.of(
            "prompt_tokens", "150",
            "completion_tokens", "75"
        );
        TokenUsage result = TokenUsage.fromMap(usage);
        assertThat(result.promptTokens()).isEqualTo(150);
        assertThat(result.completionTokens()).isEqualTo(75);
    }
}