package com.azhukov.agent.core.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSummaryTest {

    @Test
    void empty_noActions() {
        ReviewSummary summary = ReviewSummary.empty();
        assertThat(summary.hasActions()).isFalse();
        assertThat(summary.memoryUpdated()).isFalse();
        assertThat(summary.actions()).isEmpty();
        assertThat(summary.formattedSummary()).isEmpty();
    }

    @Test
    void of_memoryActions_countsCorrectly() {
        ReviewSummary summary = ReviewSummary.of(true,
            List.of("Memory: Added to memory store.", "Memory: Replaced in user store."));
        assertThat(summary.memoryUpdated()).isTrue();
        assertThat(summary.memoryActions()).isEqualTo(2);
        assertThat(summary.skillActions()).isZero();
        assertThat(summary.hasActions()).isTrue();
    }

    @Test
    void of_skillActions_countsCorrectly() {
        ReviewSummary summary = ReviewSummary.of(false,
            List.of("Skill: Skill test-skill created.", "Skill: Skill test-skill patched."));
        assertThat(summary.memoryUpdated()).isFalse();
        assertThat(summary.memoryActions()).isZero();
        assertThat(summary.skillActions()).isEqualTo(2);
    }

    @Test
    void of_combinedActions_countsCorrectly() {
        ReviewSummary summary = ReviewSummary.of(true,
            List.of("Memory: Added to memory store.", "Skill: Skill test-skill created."));
        assertThat(summary.memoryUpdated()).isTrue();
        assertThat(summary.memoryActions()).isEqualTo(1);
        assertThat(summary.skillActions()).isEqualTo(1);
    }

    @Test
    void of_emptyActions_hasNoActions() {
        ReviewSummary summary = ReviewSummary.of(false, List.of());
        assertThat(summary.hasActions()).isFalse();
        assertThat(summary.formattedSummary()).isEmpty();
    }

    @Test
    void formattedSummary_deduplicatesActions() {
        ReviewSummary summary = ReviewSummary.of(true,
            List.of("Memory: Added to memory store.", "Memory: Added to memory store."));
        // The deduplicated string should only show the action once
        assertThat(summary.formattedSummary()).contains("Memory: Added to memory store.");
        // Should contain the prefix
        assertThat(summary.formattedSummary()).startsWith("💾");
    }

    @Test
    void of_nullActions_treatedAsEmpty() {
        ReviewSummary summary = ReviewSummary.of(false, null);
        assertThat(summary.hasActions()).isFalse();
        assertThat(summary.actions()).isEmpty();
    }
}