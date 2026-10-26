package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;


import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DelegateTaskToolTimeoutTest extends DelegateTaskToolFixture {

    @Test
    @Tag("slow") // Exercises real retry/backoff or timeout waits; run manually.
    void singleTaskTimeoutReturnsTimeoutResult() throws Exception {
        AgentProperties p = properties();
        mockRuntimeHangs();
        mockRegistry();
        DelegateTaskTool tool = newTool(p);

        ToolResult result = tool.execute(
            "{\"goal\":\"slow task\",\"timeoutSeconds\":1}", null, session());

        assertThat(result.success()).isTrue();
        JsonNode entry = mapper.readTree(result.content()).get("results").get(0);
        assertThat(entry.get("status").asText()).isEqualTo("timeout");
    }

}
