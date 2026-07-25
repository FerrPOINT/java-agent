package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.UrlSafety;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSearchToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UrlSafety urlSafety;

    @Mock
    private Redactor redactor;

    private AgentProperties properties() {
        AgentProperties p = new AgentProperties();
        p.getWeb().setSearchResults(5);
        return p;
    }

    @Test
    void searchReturnsParsedResults() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);

        String html = """
            <html><body>
            <div class="result">
              <a class="result__a" href="https://en.wikipedia.org/wiki/OpenAI">OpenAI - Wikipedia</a>
              <span class="result__snippet">OpenAI is an AI research and deployment company.</span>
            </div>
            <div class="result">
              <a class="result__a" href="/l/?rut=abc&amp;uddg=https://openai.com">OpenAI Official</a>
              <span class="result__snippet">Creating safe AGI that benefits all of humanity.</span>
            </div>
            </body></html>
            """;

        Document doc = Jsoup.parse(html, "https://html.duckduckgo.com/html/");
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("q=OpenAI"))).thenReturn(connection);

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);
            var result = tool.execute("{\"query\":\"OpenAI\",\"limit\":2}", null, null);

            assertThat(result.success()).isTrue();
            List<Map<String, Object>> items = objectMapper.readValue(result.content(), new TypeReference<>() {});
            assertThat(items).hasSize(2);
            assertThat(items.get(0)).containsEntry("title", "OpenAI - Wikipedia");
            assertThat(items.get(0)).containsEntry("url", "https://en.wikipedia.org/wiki/OpenAI");
            assertThat(items.get(0)).containsEntry("snippet", "OpenAI is an AI research and deployment company.");
            assertThat(items.get(1)).containsEntry("url", "https://duckduckgo.com/l/?rut=abc&uddg=https://openai.com");
        }
    }

    @Test
    void searchLimitsResults() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);

        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 0; i < 10; i++) {
            html.append("<div class=\"result\"><a class=\"result__a\" href=\"https://example.com/").append(i).append("\">Result ").append(i).append("</a>");
            html.append("<span class=\"result__snippet\">Snippet ").append(i).append("</span></div>");
        }
        html.append("</body></html>");

        Document doc = Jsoup.parse(html.toString(), "https://html.duckduckgo.com/html/");
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("q=many"))).thenReturn(connection);

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);
            var result = tool.execute("{\"query\":\"many\",\"limit\":3}", null, null);

            assertThat(result.success()).isTrue();
            List<Map<String, Object>> items = objectMapper.readValue(result.content(), new TypeReference<>() {});
            assertThat(items).hasSize(3);
        }
    }

    @Test
    void searchReturnsNoResultsMessageWhenEmpty() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);

        Document doc = Jsoup.parse("<html><body></body></html>", "https://html.duckduckgo.com/html/");
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("q=nothing"))).thenReturn(connection);

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);
            var result = tool.execute("{\"query\":\"nothing\",\"limit\":5}", null, null);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("No results found.");
        }
    }

    @Test
    void searchBlocksDisallowedUrl() {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(false);

        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);
        var result = tool.execute("{\"query\":\"blocked\",\"limit\":5}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("URL is not allowed");
        assertThat(result.content()).isEqualTo("");
    }

    @Test
    void searchHandlesIoException() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);

        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenThrow(new IOException("timeout"));

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("q=timeout"))).thenReturn(connection);

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);
            var result = tool.execute("{\"query\":\"timeout\",\"limit\":5}", null, null);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Web search failed").contains("timeout");
        }
    }

    @Test
    void searchRequiresNonBlankQuery() {
        AgentProperties properties = properties();
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);

        assertThat(tool.execute("{\"query\":\"\"}", null, null).success()).isFalse();
        assertThat(tool.execute("{\"query\":\"   \"}", null, null).success()).isFalse();
        assertThat(tool.execute("{\"limit\":5}", null, null).success()).isFalse();
    }

    @Test
    void searchUsesConfiguredLimitWhenLimitNotProvided() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setSearchResults(1);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);

        Document doc = Jsoup.parse("""
            <html><body>
            <div class="result"><a class="result__a" href="https://a.com">A</a><span class="result__snippet">sa</span></div>
            <div class="result"><a class="result__a" href="https://b.com">B</a><span class="result__snippet">sb</span></div>
            </body></html>
            """, "https://html.duckduckgo.com/html/");
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("q=default"))).thenReturn(connection);

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);
            var result = tool.execute("{\"query\":\"default\"}", null, null);

            assertThat(result.success()).isTrue();
            List<Map<String, Object>> items = objectMapper.readValue(result.content(), new TypeReference<>() {});
            assertThat(items).hasSize(1);
        }
    }
}
