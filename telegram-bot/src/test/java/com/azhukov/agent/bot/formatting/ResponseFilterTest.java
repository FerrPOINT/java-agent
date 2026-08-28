package com.azhukov.agent.bot.formatting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseFilterTest {

    private final ResponseFilter filter = new ResponseFilter();

    @Test
    void shouldFilter_exactHermesSilenceMarkers() {
        assertThat(filter.shouldFilter("[SILENT]")).isTrue();
        assertThat(filter.shouldFilter("silent")).isTrue();
        assertThat(filter.shouldFilter("NO_REPLY")).isTrue();
        assertThat(filter.shouldFilter(" no   reply ")).isTrue();
        assertThat(filter.shouldFilter(".NO_REPLY*")).isTrue();
    }

    @Test
    void shouldNotFilterLegacyAsterisksOrProseMentioningMarker() {
        // Hermes no longer treats *** as a silence sentinel: it can be a
        // legitimate Markdown separator. Markers embedded in normal prose also
        // must never suppress a human-facing response.
        assertThat(filter.shouldFilter("***")).isFalse();
        assertThat(filter.shouldFilter("The result is NO_REPLY only in the protocol docs.")).isFalse();
        assertThat(filter.shouldFilter("[SILENT")).isFalse();
    }

    @Test
    void shouldFilterBlankAndNullContent() {
        assertThat(filter.shouldFilter(null)).isTrue();
        assertThat(filter.shouldFilter(" \n\t ")).isTrue();
    }

    @Test
    void shouldFilter_normalContent_returnsFalse() {
        assertThat(filter.shouldFilter("Hello world")).isFalse();
    }
}
