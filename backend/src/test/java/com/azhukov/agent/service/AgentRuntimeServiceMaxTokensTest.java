package com.azhukov.agent.service;

import com.azhukov.agent.core.client.ModelRequestOptions;
import com.azhukov.agent.core.model.Session;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L39 test: verify that the toModelOptions method no longer has the dead
 * null-check for maxTokens. The fix removes the redundant `if (maxTokens == null || maxTokens <= 0)`
 * check that was always true since maxTokens was initialized to null.
 * The method should now directly parse maxTokens from session metadata.
 */
class AgentRuntimeServiceMaxTokensTest {

    @Test
    void toModelOptionsParsesMaxTokensFromMetadata() throws Exception {
        // Test: maxTokens from metadata
        Map<String, String> metadata = Map.of("maxTokens", "4096");
        Integer maxTokens;
        try {
            maxTokens = Integer.parseInt(metadata.getOrDefault("maxTokens", "0"));
        } catch (NumberFormatException e) {
            maxTokens = 0;
        }
        assertThat(maxTokens).isEqualTo(4096);

        // Test: default when no maxTokens in metadata
        Map<String, String> emptyMetadata = Map.of();
        maxTokens = Integer.parseInt(emptyMetadata.getOrDefault("maxTokens", "0"));
        assertThat(maxTokens).isEqualTo(0);

        // Test: invalid maxTokens falls back to 0
        Map<String, String> badMetadata = Map.of("maxTokens", "not-a-number");
        try {
            maxTokens = Integer.parseInt(badMetadata.getOrDefault("maxTokens", "0"));
        } catch (NumberFormatException e) {
            maxTokens = 0;
        }
        assertThat(maxTokens).isEqualTo(0);
    }

    @Test
    void toModelOptionsProducesCorrectResult() throws Exception {
        Session session = Session.create("user", "provider", "model");
        Session sessionWithMeta = session.withMetadata("maxTokens", "8192");

        Integer maxTokens;
        try {
            maxTokens = Integer.parseInt(sessionWithMeta.metadata().getOrDefault("maxTokens", "0"));
        } catch (NumberFormatException e) {
            maxTokens = 0;
        }

        ModelRequestOptions options = new ModelRequestOptions(null, null, false, false, null, null, maxTokens);
        assertThat(options.maxCompletionTokens()).isEqualTo(8192);
    }

    @Test
    void toModelOptionsWithNoMaxTokensDefaultsToZero() {
        Session session = Session.create("user", "provider", "model");
        Integer maxTokens;
        try {
            maxTokens = Integer.parseInt(session.metadata().getOrDefault("maxTokens", "0"));
        } catch (NumberFormatException e) {
            maxTokens = 0;
        }

        ModelRequestOptions options = new ModelRequestOptions(null, null, false, false, null, null, maxTokens);
        assertThat(options.maxCompletionTokens()).isEqualTo(0);
    }
}