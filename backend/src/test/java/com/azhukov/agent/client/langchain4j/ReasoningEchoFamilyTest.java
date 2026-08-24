package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningEchoFamilyTest {

    @Test
    void detectsKimiByProvider() {
        assertThat(ReasoningEchoFamily.detect("kimi-coding", null, null))
            .isEqualTo(ReasoningEchoFamily.Family.KIMI);
    }

    @Test
    void detectsKimiByBaseUrl() {
        assertThat(ReasoningEchoFamily.detect(null, null, "https://api.kimi.com/v1"))
            .isEqualTo(ReasoningEchoFamily.Family.KIMI);
    }

    @Test
    void detectsKimiByMoonshotHost() {
        assertThat(ReasoningEchoFamily.detect(null, null, "https://moonshot.ai/v1"))
            .isEqualTo(ReasoningEchoFamily.Family.KIMI);
    }

    @Test
    void detectsDeepSeekByProvider() {
        assertThat(ReasoningEchoFamily.detect("deepseek", null, null))
            .isEqualTo(ReasoningEchoFamily.Family.DEEPSEEK);
    }

    @Test
    void detectsDeepSeekByModel() {
        assertThat(ReasoningEchoFamily.detect(null, "deepseek-chat", null))
            .isEqualTo(ReasoningEchoFamily.Family.DEEPSEEK);
    }

    @Test
    void detectsDeepSeekByBaseUrl() {
        assertThat(ReasoningEchoFamily.detect(null, null, "https://api.deepseek.com/v1"))
            .isEqualTo(ReasoningEchoFamily.Family.DEEPSEEK);
    }

    @Test
    void detectsMiMoByProvider() {
        assertThat(ReasoningEchoFamily.detect("xiaomi", null, null))
            .isEqualTo(ReasoningEchoFamily.Family.MIMO);
    }

    @Test
    void detectsMiMoByModel() {
        assertThat(ReasoningEchoFamily.detect(null, "mimo-7b", null))
            .isEqualTo(ReasoningEchoFamily.Family.MIMO);
    }

    @Test
    void detectsMiMoByBaseUrl() {
        assertThat(ReasoningEchoFamily.detect(null, null, "https://api.xiaomimimo.com/v1"))
            .isEqualTo(ReasoningEchoFamily.Family.MIMO);
    }

    @Test
    void noMatchForOpenAI() {
        assertThat(ReasoningEchoFamily.detect("openai", "gpt-4o", "https://api.openai.com/v1"))
            .isEqualTo(ReasoningEchoFamily.Family.NONE);
    }

    @Test
    void noMatchForMistral() {
        assertThat(ReasoningEchoFamily.detect("mistral", "mistral-large", "https://api.mistral.ai/v1"))
            .isEqualTo(ReasoningEchoFamily.Family.NONE);
    }

    @Test
    void noMatchForLiteLLM() {
        // LiteLLM proxy is not a reasoning_content echo family
        assertThat(ReasoningEchoFamily.detect("openai-compatible", "app-test", "http://192.168.10.1:4000/v1"))
            .isEqualTo(ReasoningEchoFamily.Family.NONE);
    }

    @Test
    void needsReasoningEchoTrueForDeepSeek() {
        assertThat(ReasoningEchoFamily.needsReasoningEcho("deepseek", null, null)).isTrue();
    }

    @Test
    void needsReasoningEchoFalseForGeneric() {
        assertThat(ReasoningEchoFamily.needsReasoningEcho("openai", "gpt-4", null)).isFalse();
    }

    @Test
    void nullInputsReturnNone() {
        assertThat(ReasoningEchoFamily.detect(null, null, null))
            .isEqualTo(ReasoningEchoFamily.Family.NONE);
    }

    @Test
    void emptyInputsReturnNone() {
        assertThat(ReasoningEchoFamily.detect("", "", ""))
            .isEqualTo(ReasoningEchoFamily.Family.NONE);
    }

    @Test
    void kimiCodingCnVariant() {
        assertThat(ReasoningEchoFamily.detect("kimi-coding-cn", null, null))
            .isEqualTo(ReasoningEchoFamily.Family.KIMI);
    }
}