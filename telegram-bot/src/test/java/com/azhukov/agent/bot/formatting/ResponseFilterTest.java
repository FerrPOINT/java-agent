package com.azhukov.agent.bot.formatting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseFilterTest {

    private final ResponseFilter filter = new ResponseFilter();

    @Test
    void shouldFilter_silenceMarker_returnsTrue() {
        assertThat(filter.shouldFilter("***")).isTrue();
    }

    @Test
    void shouldFilter_normalContent_returnsFalse() {
        assertThat(filter.shouldFilter("Hello world")).isFalse();
    }
}