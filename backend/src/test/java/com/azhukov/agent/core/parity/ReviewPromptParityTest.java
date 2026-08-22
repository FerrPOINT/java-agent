package com.azhukov.agent.core.parity;

import com.azhukov.agent.core.memory.ReviewPrompts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION GUARDS: byte-parity of the background-review prompts with the
 * Hermes reference (agent/background_review.py — _MEMORY_REVIEW_PROMPT 456,
 * _SKILL_REVIEW_PROMPT 7106, _COMBINED_REVIEW_PROMPT 5639 chars).
 *
 * <p>Fixture extracted LIVE from the reference module (2026-08-22). A failure
 * means the prompt drifted — restore the exact bytes; never paraphrase.
 */
class ReviewPromptParityTest {

    private static JsonNode fixtures;

    @BeforeAll
    static void load() throws Exception {
        fixtures = new ObjectMapper().readTree(Files.readString(Path.of(
            "src/test/resources/parity/hermes-review-prompts.json")));
    }

    @Test
    @DisplayName("MEMORY_REVIEW_PROMPT byte-parity (456 chars)")
    void memoryReviewParity() {
        String expected = fixtures.get("memory").asText();
        assertThat(ReviewPrompts.MEMORY_REVIEW_PROMPT)
            .hasSize(456)
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("SKILL_REVIEW_PROMPT byte-parity (7106 chars)")
    void skillReviewParity() {
        String expected = fixtures.get("skill").asText();
        assertThat(ReviewPrompts.SKILL_REVIEW_PROMPT)
            .hasSize(7106)
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("COMBINED_REVIEW_PROMPT byte-parity (5639 chars)")
    void combinedReviewParity() {
        String expected = fixtures.get("combined").asText();
        assertThat(ReviewPrompts.COMBINED_REVIEW_PROMPT)
            .hasSize(5639)
            .isEqualTo(expected);
    }
}
