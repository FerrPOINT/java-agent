package com.azhukov.agent.core.agent;

import com.azhukov.agent.client.langchain4j.ErrorClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkingTimeoutGuidanceTest {

    @Test
    void detectsTransportKillOnReasoningModel() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.TIMEOUT,
            "nvidia/nemotron-3-ultra-550b-a55b",
            "Connection broken pipe")).isTrue();
    }

    @Test
    void detectsServerDisconnectedOnDeepSeekR1() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.TIMEOUT,
            "deepseek-r1",
            "server disconnected")).isTrue();
    }

    @Test
    void detectsPeerClosedOnO3() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.TIMEOUT,
            "openai/o3",
            "peer closed connection")).isTrue();
    }

    @Test
    void detectsErrno32OnQwq() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.TIMEOUT,
            "qwen-qwq",
            "errno 32")).isTrue();
    }

    @Test
    void rejectsNonReasoningModel() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.TIMEOUT,
            "gpt-4o",
            "broken pipe")).isFalse();
    }

    @Test
    void rejectsNonTimeoutErrorType() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.BILLING,
            "nemotron-3-ultra",
            "broken pipe")).isFalse();
    }

    @Test
    void rejectsNonTransportKillMessage() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.TIMEOUT,
            "nemotron-3-ultra",
            "rate limit exceeded")).isFalse();
    }

    @Test
    void rejectsNullModelName() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.TIMEOUT,
            null,
            "broken pipe")).isFalse();
    }

    @Test
    void rejectsEmptyModelErrorMsg() {
        assertThat(ThinkingTimeoutGuidance.isThinkingTimeout(
            ErrorClassifier.ErrorType.TIMEOUT,
            "o3",
            "")).isFalse();
    }

    @Test
    void isReasoningModelDetectsAllKeywords() {
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("nemotron-3-ultra")).isTrue();
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("openai/o1")).isTrue();
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("o3-mini")).isTrue();
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("deepseek-r1")).isTrue();
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("qwen-qwq-32b")).isTrue();
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("grok-4")).isTrue();
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("claude-opus-4-thinking")).isTrue();
    }

    @Test
    void isReasoningModelRejectsNonReasoning() {
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("gpt-4o")).isFalse();
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("claude-3.5-sonnet")).isFalse();
        assertThat(ThinkingTimeoutGuidance.isReasoningModel("gemini-2.0-flash")).isFalse();
    }

    @Test
    void buildGuidanceContainsModelName() {
        String guidance = ThinkingTimeoutGuidance.buildGuidance("nvidia", "nemotron-3-ultra");
        assertThat(guidance).contains("nemotron-3-ultra");
        assertThat(guidance).contains("stale_timeout_seconds");
        assertThat(guidance).contains("reasoning_effort");
    }

    @Test
    void buildGuidanceHandlesNullProvider() {
        String guidance = ThinkingTimeoutGuidance.buildGuidance(null, "o3");
        assertThat(guidance).contains("your-provider");
        assertThat(guidance).contains("o3");
    }

    @Test
    void buildGuidanceHandlesNullModel() {
        String guidance = ThinkingTimeoutGuidance.buildGuidance("openai", null);
        assertThat(guidance).contains("this model");
        assertThat(guidance).contains("your-model");
    }
}