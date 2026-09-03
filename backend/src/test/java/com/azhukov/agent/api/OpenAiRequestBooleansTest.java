package com.azhukov.agent.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestBooleansTest {

    @Test
    void coerceMatchesHermesBoolishScalarPolicy() {
        assertThat(OpenAiRequestBooleans.coerce(true, false)).isTrue();
        assertThat(OpenAiRequestBooleans.coerce(false, true)).isFalse();
        assertThat(OpenAiRequestBooleans.coerce("yes", false)).isTrue();
        assertThat(OpenAiRequestBooleans.coerce("on", false)).isTrue();
        assertThat(OpenAiRequestBooleans.coerce("0", true)).isFalse();
        assertThat(OpenAiRequestBooleans.coerce("off", true)).isFalse();
        assertThat(OpenAiRequestBooleans.coerce(2, false)).isTrue();
        assertThat(OpenAiRequestBooleans.coerce(0.5d, false)).isTrue();
        assertThat(OpenAiRequestBooleans.coerce(0, true)).isFalse();
        assertThat(OpenAiRequestBooleans.coerce("maybe", true)).isTrue();
        assertThat(OpenAiRequestBooleans.coerce("maybe", false)).isFalse();
    }
}
