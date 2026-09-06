package com.azhukov.agent.core.context;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for DefaultContextCompressor pure string helpers:
 * boundContent truncation and boundSummaryInput head+tail bounding.
 */
class DefaultContextCompressorStringHelpersTest {

    private String invoke(String method, String arg) throws Exception {
        Method m = DefaultContextCompressor.class.getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, arg);
    }

    @Test
    void boundContentKeepsShortContentUntouched() throws Exception {
        assertThat(invoke("truncateForSummary", "short")).isEqualTo("short");
        assertThat(invoke("truncateForSummary", null)).isNull();
    }

    @Test
    void boundContentTruncatesWithMarker() throws Exception {
        String huge = "x".repeat(50_000);
        String bounded = invoke("truncateForSummary", huge);
        assertThat(bounded).endsWith("...[truncated]");
        assertThat(bounded.length()).isLessThan(50_000);
    }

    @Test
    void boundSummaryInputBoundsHeadAndTail() throws Exception {
        String huge = "A".repeat(400_000);
        String bounded = invoke("boundSummaryInput", huge);
        assertThat(bounded).contains("summary input truncated");
        assertThat(bounded.length()).isLessThanOrEqualTo(400_000);
        assertThat(bounded.length()).isGreaterThan(1_000);
    }

    @Test
    void boundSummaryInputKeepsShortInputUntouched() throws Exception {
        assertThat(invoke("boundSummaryInput", "short")).isEqualTo("short");
        assertThat(invoke("boundSummaryInput", null)).isNull();
    }
}
