package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Coverage + regression for WebExtractTool guard branches: malformed args,
 * >5 URL cap, secret-prefix URLs, credential-like query params, per-call
 * char_limit clamping.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebExtractToolGuardBranchTest {

    @Mock private UrlSafety urlSafety;
    @Mock private Redactor redactor;

    private final ObjectMapper mapper = new ObjectMapper();

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getWeb().setExtractTimeoutSeconds(10);
        p.getWeb().setExtractMaxChars(100_000);
        return p;
    }

    private WebExtractTool tool() {
        WebExtractTool tool = new WebExtractTool(properties(), urlSafety, redactor);
        tool.init();
        return tool;
    }

    private Session session() {
        return Session.create("user", "openai", "gpt-4");
    }

    @Test
    void malformedJsonReturnsErrorPayload() throws Exception {
        ToolResult r = tool().execute("{bad", null, session());
        JsonNode root = mapper.readTree(r.content());
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(root.path("error").asText()).contains("Error extracting content");
    }

    @Test
    void emptyUrlsListReturnsNotAccessible() throws Exception {
        ToolResult r = tool().execute("{\"urls\":[]}", null, session());
        JsonNode root = mapper.readTree(r.content());
        assertThat(root.path("error").asText()).contains("inaccessible");
    }

    @Test
    void secretPrefixedUrlIsBlockedWholeCall() throws Exception {
        // Pattern: sk-[a-z0-9]{20,} — the run AFTER "sk-" must be ≥20 alnum chars.
        // "sk-proj-…-…" has a hyphen inside, so use a plain long sk- run.
        ToolResult r = tool().execute(
            "{\"urls\":[\"https://example.com/path?x=sk-abcdefghijklmnopqrstuvwxyz\"]}", null, session());
        JsonNode root = mapper.readTree(r.content());
        assertThat(root.path("error").asText()).contains("API key or token");
    }

    @Test
    void credentialLikeQueryParamIsBlocked() throws Exception {
        ToolResult r = tool().execute(
            "{\"urls\":[\"https://example.com/page?password=hunter2\"]}", null, session());
        JsonNode root = mapper.readTree(r.content());
        assertThat(root.path("error").asText()).contains("credential-like query parameter");
    }

    @Test
    void moreThanFiveUrlsAreCappedAtFive() throws Exception {
        WebExtractTool spyTool = spy(tool());
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        // stub extract to avoid real network: return minimal html
        org.mockito.Mockito.doReturn("<html><head><title>t</title></head><body>x</body></html>")
            .when(spyTool).extract(anyString());

        String urls = List.of("https://a.example.com", "https://b.example.com",
                "https://c.example.com", "https://d.example.com", "https://e.example.com",
                "https://f.example.com", "https://g.example.com").stream()
            .map(u -> "\"" + u + "\"")
            .reduce((a, b) -> a + "," + b).orElse("");
        ToolResult r = spyTool.execute("{\"urls\":[" + urls + "]}", null, session());

        JsonNode results = mapper.readTree(r.content()).path("results");
        assertThat(results).hasSize(5);
    }

    @Test
    void charLimitClampedToAtLeast2000() throws Exception {
        WebExtractTool spyTool = spy(tool());
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv -> inv.getArgument(0));
        String body = "y".repeat(4000);
        org.mockito.Mockito.doReturn("<html><head><title>t</title></head><body><p>" + body + "</p></body></html>")
            .when(spyTool).extract(anyString());

        ToolResult r = spyTool.execute(
            "{\"urls\":[\"https://a.example.com\"],\"char_limit\":100}", null, session());
        JsonNode content = mapper.readTree(r.content()).path("results").get(0).path("content");
        // clamped floor is 2000 → full body returned
        assertThat(content.asText().length()).isGreaterThanOrEqualTo(2000);
    }
}
