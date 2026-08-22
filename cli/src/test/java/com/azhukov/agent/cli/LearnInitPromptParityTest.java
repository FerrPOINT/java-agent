package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REGRESSION GUARDS: byte-parity of the /learn and /init prompt builders with
 * the Hermes reference (agent/learn_prompt.py, hermes_cli/init_command.py).
 *
 * <p>Fixtures under src/test/resources/parity were extracted LIVE from the
 * reference modules (2026-08-22). A failure means the text drifted — restore
 * the exact bytes; never paraphrase.
 */
class LearnInitPromptParityTest {

    private static JsonNode learnInit;
    private static JsonNode initExtra;

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper m = new ObjectMapper();
        learnInit = m.readTree(Files.readString(Path.of(
            "src/test/resources/parity/hermes-learn-init.json")));
        initExtra = m.readTree(Files.readString(Path.of(
            "src/test/resources/parity/hermes-init-extra.json")));
    }

    @Test
    @DisplayName("buildLearnPrompt byte-parity with Hermes build_learn_prompt")
    void learnPromptParity() {
        String expected = learnInit.get("learn").asText();
        String actual = LearnInitCommands.buildLearnPrompt("TESTREQUEST");
        assertThat(actual).hasSize(expected.length());
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("authoring/knowledge/hygiene standards byte-parity")
    void standardsParity() {
        // The standards are embedded inside the learn prompt; verify via the
        // assembled prompt containing each standard verbatim.
        String learn = LearnInitCommands.buildLearnPrompt("TESTREQUEST");
        assertThat(learn).contains(learnInit.get("auth").asText());
        assertThat(learn).contains(learnInit.get("kb").asText());
        assertThat(learn).contains(learnInit.get("hygiene").asText());
    }

    @Test
    @DisplayName("buildInitPrompt (fresh) byte-parity with Hermes build_init_prompt")
    void initPromptParity() {
        String expected = initExtra.get("init").asText();
        String actual = LearnInitCommands.buildInitPrompt("/opt/dev/java-agent", null, "");
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("buildInitPrompt (update mode) switches to merge discipline")
    void initUpdatePromptParity() {
        String expected = initExtra.get("init_update").asText();
        String actual = LearnInitCommands.buildInitPrompt("/opt/dev/java-agent", "# Existing content", "");
        assertThat(actual).isEqualTo(expected);
    }
}
