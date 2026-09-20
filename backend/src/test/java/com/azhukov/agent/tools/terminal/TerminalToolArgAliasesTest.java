package com.azhukov.agent.tools.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: the model may send camelCase (or Hermes' kebab-case) variants of
 * snake_case tool args; Jackson must accept all spellings (audit rule: every
 * {@code @JsonProperty("snake_case")} on a tool Args record needs a camelCase
 * {@code @JsonAlias}). Before the fix {@code notifyOnComplete} /
 * {@code watchPatterns} were silently dropped as {@code false} / empty.
 */
class TerminalToolArgAliasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void notifyOnCompleteCamelCaseIsAccepted() throws Exception {
        String json = "{\"command\":\"sleep 1\",\"background\":true,\"notifyOnComplete\":true}";
        TerminalTool.TerminalArgs args = JSON.readValue(json, TerminalTool.TerminalArgs.class);
        assertThat(args.notifyOnComplete()).isTrue();
    }

    @Test
    void notifyOnCompleteKebabCaseAliasStaysAccepted() throws Exception {
        String json = "{\"command\":\"sleep 1\",\"background\":true,\"notify-on-complete\":true}";
        TerminalTool.TerminalArgs args = JSON.readValue(json, TerminalTool.TerminalArgs.class);
        assertThat(args.notifyOnComplete()).isTrue();
    }

    @Test
    void notifyOnCompleteSnakeCaseStaysAccepted() throws Exception {
        String json = "{\"command\":\"sleep 1\",\"background\":true,\"notify_on_complete\":true}";
        TerminalTool.TerminalArgs args = JSON.readValue(json, TerminalTool.TerminalArgs.class);
        assertThat(args.notifyOnComplete()).isTrue();
    }

    @Test
    void watchPatternsCamelCaseIsAccepted() throws Exception {
        String json = "{\"command\":\"sleep 1\",\"background\":true,\"watchPatterns\":[\"READY\"]}";
        TerminalTool.TerminalArgs args = JSON.readValue(json, TerminalTool.TerminalArgs.class);
        assertThat(args.watchPatterns()).containsExactly("READY");
    }
}
