package com.azhukov.agent.core.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPromptPolicyTest {

    @Test
    void classifiesKnownFamiliesCaseInsensitively() {
        assertThat(ModelPromptPolicy.detectFamily("GPT-5-mini")).isEqualTo("openai");
        assertThat(ModelPromptPolicy.detectFamily("Gemma-3")).isEqualTo("google");
        assertThat(ModelPromptPolicy.detectFamily("llama-3")).isNull();
        assertThat(ModelPromptPolicy.detectFamily(" ")).isNull();
        assertThat(ModelPromptPolicy.detectFamily(null)).isNull();
    }

    @Test
    void selectsDeveloperRoleOnlyForConfiguredPrefixes() {
        assertThat(ModelPromptPolicy.usesDeveloperRole("gpt-5.1")).isTrue();
        assertThat(ModelPromptPolicy.usesDeveloperRole("CoDeX-mini")).isTrue();
        assertThat(ModelPromptPolicy.usesDeveloperRole("gpt-4o")).isFalse();
        assertThat(ModelPromptPolicy.usesDeveloperRole(null)).isFalse();
    }

    @Test
    void selectsFamilyAndExecutionGuidanceWithoutChangingPrecedence() {
        assertThat(ModelPromptPolicy.guidanceFor("gemini-2.5", "openai", "google")).isEqualTo("google");
        assertThat(ModelPromptPolicy.guidanceFor("gpt-4o", "openai", "google")).isEqualTo("openai");
        assertThat(ModelPromptPolicy.guidanceFor("KIMI-k2", "openai", "google")).isEqualTo("openai");
        assertThat(ModelPromptPolicy.guidanceFor("llama-3", "openai", "google")).isEmpty();
    }

    @Test
    void identifiesModelsThatNeedToolUseEnforcement() {
        assertThat(ModelPromptPolicy.needsToolUseEnforcement("deepseek-chat")).isTrue();
        assertThat(ModelPromptPolicy.needsToolUseEnforcement("Gemini-2.5-pro")).isTrue();
        assertThat(ModelPromptPolicy.needsToolUseEnforcement("kimi-k2")).isFalse();
        assertThat(ModelPromptPolicy.needsToolUseEnforcement("")).isFalse();
    }
}