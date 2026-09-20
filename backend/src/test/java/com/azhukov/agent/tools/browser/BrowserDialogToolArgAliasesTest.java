package com.azhukov.agent.tools.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: camelCase spelling of {@code prompt_text} must deserialize
 * (audit rule: snake_case {@code @JsonProperty} requires camelCase
 * {@code @JsonAlias}). Symptom before fix: prompt dialogs answered with null
 * text when the model sent {@code promptText}.
 */
class BrowserDialogToolArgAliasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void promptTextCamelCaseIsAccepted() throws Exception {
        String json = "{\"action\":\"accept\",\"promptText\":\"hello\"}";
        BrowserDialogTool.DialogArgs args = JSON.readValue(json, BrowserDialogTool.DialogArgs.class);
        assertThat(args.promptText()).isEqualTo("hello");
    }

    @Test
    void promptTextSnakeCaseStaysAccepted() throws Exception {
        String json = "{\"action\":\"accept\",\"prompt_text\":\"hello\"}";
        BrowserDialogTool.DialogArgs args = JSON.readValue(json, BrowserDialogTool.DialogArgs.class);
        assertThat(args.promptText()).isEqualTo("hello");
    }

    @Test
    void promptTextLegacyTextAliasStaysAccepted() throws Exception {
        String json = "{\"action\":\"accept\",\"text\":\"hello\"}";
        BrowserDialogTool.DialogArgs args = JSON.readValue(json, BrowserDialogTool.DialogArgs.class);
        assertThat(args.promptText()).isEqualTo("hello");
    }
}
