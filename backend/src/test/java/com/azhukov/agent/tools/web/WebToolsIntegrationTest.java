package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.DefaultRedactor;
import com.azhukov.agent.core.security.DefaultUrlSafety;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ENABLE_NETWORK_TESTS", matches = "true")
@Tag("slow")
class WebToolsIntegrationTest {

    private final AgentProperties properties = new AgentProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void webSearchReturnsResults() throws Exception {
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, new DefaultUrlSafety(properties), new DefaultRedactor(properties));
        tool.init();
        tool.init();
        var result = tool.execute("{\"query\":\"OpenAI\",\"limit\":3}", null, null);

        assertThat(result.success()).isTrue();
        List<Map<String, Object>> items = objectMapper.readValue(result.content(),
            new com.fasterxml.jackson.core.type.TypeReference<>() {});
        assertThat(items).isNotEmpty();
        assertThat(items.get(0)).containsKey("title");
        assertThat(items.get(0)).containsKey("url");
    }

    @Test
    void webExtractReturnsText() {
        WebExtractTool tool = new WebExtractTool(properties, new DefaultUrlSafety(properties), new DefaultRedactor(properties));
        tool.init();
        var result = tool.execute("{\"urls\":\"https://example.com\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).containsIgnoringCase("Example Domain");
    }
}
