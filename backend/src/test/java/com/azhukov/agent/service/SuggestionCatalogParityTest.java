package com.azhukov.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION GUARD: the curated starter catalog must match the Hermes
 * reference (cron/suggestion_catalog.py CATALOG — 4 entries) EXACTLY:
 * key, title, description and the full job prompt/schedule/name. The
 * mail-monitor prompt drifted once (423 vs 645 chars after Hermes updated
 * its urgency-classifier wording) — this fixture pins the live reference.
 */
class SuggestionCatalogParityTest {

    private static JsonNode catalog;

    @BeforeAll
    static void load() throws Exception {
        catalog = new ObjectMapper().readTree(Files.readString(Path.of(
            "src/test/resources/parity/hermes-suggestion-catalog.json")));
    }

    @Test
    @DisplayName("catalog seeds exactly the 4 Hermes entries with byte-exact fields")
    void catalogParity() {
        CronSuggestionService service = new CronSuggestionService(null);
        int seeded = service.seedCatalogSuggestions();
        assertThat(seeded).isEqualTo(4);

        var pending = service.listPending();
        assertThat(pending).hasSize(4);

        for (JsonNode expected : catalog) {
            var match = pending.stream()
                .filter(s -> s.dedupKey().equals(expected.get("key").asText()))
                .findFirst()
                .orElse(null);
            assertThat(match).as("entry %s present", expected.get("key")).isNotNull();
            assertThat(match.title()).isEqualTo(expected.get("title").asText());
            assertThat(match.description()).isEqualTo(expected.get("description").asText());
            JsonNode spec = expected.get("job_spec");
            assertThat(match.jobSpec().prompt())
                .as("prompt for %s must be byte-exact", expected.get("key"))
                .isEqualTo(spec.get("prompt").asText());
            assertThat(match.jobSpec().schedule()).isEqualTo(spec.get("schedule").asText());
            assertThat(match.jobSpec().name()).isEqualTo(spec.get("name").asText());
        }
    }
}
