package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.UrlSafety;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.verify;
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

    private WebSearchTool tool(AgentProperties properties) {
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);
        tool.init();
        return tool;
    }

    private Map<String, Object> errorPayload(ToolResult result) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(result.content(), new TypeReference<>() {});
        assertThat(payload).containsEntry("success", false);
        assertThat(result.error()).isEqualTo(payload.get("error"));
        return payload;
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

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"query\":\"OpenAI\",\"limit\":2}", null, null);

            assertThat(result.success()).isTrue();
            var root = objectMapper.readTree(result.content());
            assertThat(root.path("success").asBoolean()).isTrue();
            var items = root.path("data").path("web");
            assertThat(items).hasSize(2);
            assertThat(items.get(0).path("title").asText()).isEqualTo("OpenAI - Wikipedia");
            assertThat(items.get(0).path("url").asText()).isEqualTo("https://en.wikipedia.org/wiki/OpenAI");
            assertThat(items.get(0).path("description").asText()).isEqualTo("OpenAI is an AI research and deployment company.");
            assertThat(items.get(0).path("position").asInt()).isEqualTo(1);
            assertThat(items.get(1).path("url").asText()).isEqualTo("https://openai.com");
            assertThat(items.get(1).path("position").asInt()).isEqualTo(2);
        }
    }

    @Test
    void searchRedactsSerializedResults() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        when(redactor.redact(anyString())).thenAnswer(inv ->
            ((String) inv.getArgument(0)).replace("sk-abcdefghijklmnopqrst", "[REDACTED]"));

        Document doc = Jsoup.parse("""
            <html><body>
            <div class="result">
              <a class="result__a" href="https://example.com">Example</a>
              <span class="result__snippet">token sk-abcdefghijklmnopqrst leaked</span>
            </div>
            </body></html>
            """, "https://html.duckduckgo.com/html/");
        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenReturn(doc);

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("q=secret"))).thenReturn(connection);

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor);
            tool.init();
            var result = tool.execute("{\"query\":\"secret\",\"limit\":1}", null, null);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).contains("[REDACTED]");
            assertThat(result.content()).doesNotContain("sk-abcdefghijklmnopqrst");
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

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"query\":\"many\",\"limit\":3}", null, null);

            assertThat(result.success()).isTrue();
            var root = objectMapper.readTree(result.content());
            assertThat(root.path("data").path("web")).hasSize(3);
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

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"query\":\"nothing\",\"limit\":5}", null, null);

            assertThat(result.success()).isTrue();
            var root = objectMapper.readTree(result.content());
            assertThat(root.path("success").asBoolean()).isTrue();
            assertThat(root.path("data").path("web")).isEmpty();
        }
    }

    @Test
    void searchBlocksDisallowedUrl() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(false);

        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor); tool.init();
        var result = tool.execute("{\"query\":\"blocked\",\"limit\":5}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(errorPayload(result).get("error").toString()).contains("URL is not allowed");
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

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"query\":\"timeout\",\"limit\":5}", null, null);

            assertThat(result.success()).isFalse();
            assertThat(errorPayload(result).get("error").toString()).contains("Web search failed").contains("timeout");
        }
    }

    @Test
    void emptySearxngNewsQueryFallsBackToGoogleNewsRss() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search("latest technology news", 20)).thenReturn(List.of(Map.of(
            "title", "Technology news", "url", "https://news.example", "description", "Latest technology news")));

        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, "latest technology news");

        ToolResult result = tool.execute("{\"query\":\"latest technology news\",\"limit\":2}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web").get(0).path("title").asText())
            .isEqualTo("Technology news");
        verify(news).search("latest technology news", 20);
    }

    @Test
    void failedSearxngNewsQueryFallsBackToGoogleNewsRss() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search("technology news", 20)).thenReturn(List.of(Map.of(
            "title", "Technology news", "url", "https://news.example", "description", "Latest technology news")));

        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, "technology news");
        SearXngSearchProvider searxng = (SearXngSearchProvider) org.springframework.test.util.ReflectionTestUtils
            .getField(tool, "searXngProvider");
        when(searxng.search("technology news", 20)).thenThrow(new IOException("connection refused"));

        ToolResult result = tool.execute("{\"query\":\"technology news\",\"limit\":2}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
        verify(news).search("technology news", 20);
    }

    @Test
    void failedSearxngNonNewsQueryReportsPrimaryFailureWithoutNewsFallback() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);

        WebSearchTool tool = configuredSearxngTool(properties, news, "Spring Boot reference documentation");
        SearXngSearchProvider searxng = (SearXngSearchProvider) org.springframework.test.util.ReflectionTestUtils
            .getField(tool, "searXngProvider");
        when(searxng.search("Spring Boot reference documentation", 2))
            .thenThrow(new IOException("connection refused"));

        ToolResult result = tool.execute(
            "{\"query\":\"Spring Boot reference documentation\",\"limit\":2}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(errorPayload(result).get("error").toString()).contains("connection refused");
        verify(news, org.mockito.Mockito.never()).search(anyString(), anyInt());
    }

    @Test
    void emptySearxngRussianNewsQueryFallsBackToGoogleNewsRss() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search("последние новости технологий октябрь", 20)).thenReturn(List.of(
            Map.of("title", "Технологии: дайджест", "url", "https://news.example/ru", "description", "Новые технологии"),
            Map.of("title", "Городские новости", "url", "https://news.example/city", "description", "События города")));

        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, "последние новости технологий октябрь");

        ToolResult result = tool.execute(
            "{\"query\":\"последние новости технологий октябрь\",\"limit\":2}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
        assertThat(objectMapper.readTree(result.content()).path("data").path("web").get(0).path("title").asText())
            .contains("Технологии");
        verify(news).search("последние новости технологий октябрь", 20);
    }

    @Test
    void emptySearxngRussianNewsInflectionFallsBackToGoogleNewsRss() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search("новостями технологий", 20)).thenReturn(List.of(Map.of(
            "title", "Новости технологий", "url", "https://news.example/ru", "description", "Дайджест технологий")));

        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, "новостями технологий");

        ToolResult result = tool.execute("{\"query\":\"новостями технологий\",\"limit\":2}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
        verify(news).search("новостями технологий", 20);
    }

    @Test
    void topicalNewsQueryFetchesCandidateWindowThenCapsResults() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor, news);
        tool.init();
        SearXngSearchProvider searxng = mock(SearXngSearchProvider.class);
        when(searxng.isAvailable()).thenReturn(true);
        when(searxng.search("последние новости технологий октябрь", 20)).thenReturn(List.of(
            Map.of("title", "Технология беспилотников", "url", "https://news.example/tech", "description", "Новая технология"),
            Map.of("title", "Т-Технологии: дивиденды", "url", "https://news.example/ticker", "description", "Биржевые новости"),
            Map.of("title", "Городские новости", "url", "https://news.example/city", "description", "События города"),
            Map.of("title", "ИТ-форум", "url", "https://news.example/it", "description", "Технологии")));
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "searXngProvider", searxng);

        ToolResult result = tool.execute(
            "{\"query\":\"последние новости технологий октябрь\",\"limit\":2}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(2);
        verify(searxng).search("последние новости технологий октябрь", 20);
        verify(news, org.mockito.Mockito.never()).search(anyString(), anyInt());
    }

    @Test
    void emptySearxngNonNewsQueryDoesNotFallBackToGoogleNewsRss() throws Exception {
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);

        WebSearchTool tool = configuredSearxngTool(properties, news, "Spring Boot reference documentation");

        ToolResult result = tool.execute(
            "{\"query\":\"Spring Boot reference documentation\",\"limit\":2}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).isEmpty();
        verify(news, org.mockito.Mockito.never()).search(anyString(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
        "января", "январе", "ноября", "ноябре", "2026"
    })
    void newsMonthCannotAdmitAnUnrelatedTopic(String period) throws Exception {
        String query = "technology news " + period;
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search(org.mockito.ArgumentMatchers.eq(query), anyInt())).thenReturn(List.of(
            Map.of("title", "Technology research", "url", "https://news.example/tech", "description", "New chips"),
            Map.of("title", period + " city news", "url", "https://news.example/city", "description", "Local events")));
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, query);

        ToolResult result = tool.execute(objectMapper.writeValueAsString(Map.of("query", query, "limit", 2)), null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
        assertThat(objectMapper.readTree(result.content()).path("data").path("web").get(0).path("title").asText())
            .isEqualTo("Technology research");
    }

    @Test
    void shortNewsTopicDoesNotMatchInsideAnotherWord() throws Exception {
        String query = "AI news";
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search(org.mockito.ArgumentMatchers.eq(query), anyInt())).thenReturn(List.of(
            Map.of("title", "AI chips", "url", "https://news.example/ai", "description", "Machine learning"),
            Map.of("title", "Rail services", "url", "https://news.example/rail", "description", "City transport")));
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, query);

        ToolResult result = tool.execute(objectMapper.writeValueAsString(Map.of("query", query, "limit", 2)), null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
        assertThat(objectMapper.readTree(result.content()).path("data").path("web").get(0).path("title").asText())
            .isEqualTo("AI chips");
    }

    @ParameterizedTest
    @ValueSource(strings = {"is", "are", "you", "can"})
    void genericShortWordsDoNotBecomeNewsTopics(String filler) throws Exception {
        String query = "AI " + filler + " news";
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search(org.mockito.ArgumentMatchers.eq(query), anyInt())).thenReturn(List.of(
            Map.of("title", "AI chips", "url", "https://news.example/ai", "description", "Machine learning"),
            Map.of("title", "This " + filler + " local news", "url", "https://news.example/local", "description", "Community events")));
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, query);

        ToolResult result = tool.execute(objectMapper.writeValueAsString(Map.of("query", query, "limit", 2)), null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
        assertThat(objectMapper.readTree(result.content()).path("data").path("web").get(0).path("title").asText())
            .isEqualTo("AI chips");
    }

    @Test
    void unrelatedSearxngNewsUsesTheFilteredFallback() throws Exception {
        String query = "AI news";
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search(org.mockito.ArgumentMatchers.eq(query), anyInt())).thenReturn(List.of(
            Map.of("title", "AI chips", "url", "https://news.example/ai", "description", "Machine learning")));
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, query);
        SearXngSearchProvider searxng = (SearXngSearchProvider) org.springframework.test.util.ReflectionTestUtils
            .getField(tool, "searXngProvider");
        when(searxng.search(query, 20)).thenReturn(List.of(
            Map.of("title", "Rail services", "url", "https://news.example/rail", "description", "City transport")));

        ToolResult result = tool.execute(objectMapper.writeValueAsString(Map.of("query", query, "limit", 2)), null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
        assertThat(objectMapper.readTree(result.content()).path("data").path("web").get(0).path("title").asText())
            .isEqualTo("AI chips");
        verify(news).search(org.mockito.ArgumentMatchers.eq(query), anyInt());
    }

    @Test
    void longNewsTopicStillMatchesWordExtensions() throws Exception {
        String query = "crypto news";
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search(org.mockito.ArgumentMatchers.eq(query), anyInt())).thenReturn(List.of(
            Map.of("title", "Cryptocurrency research", "url", "https://news.example/crypto", "description", "New tokens")));
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, query);

        ToolResult result = tool.execute(objectMapper.writeValueAsString(Map.of("query", query, "limit", 2)), null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
    }

    @Test
    void technologySemanticAiDoesNotMatchInsideOtherWords() throws Exception {
        String query = "новости технологий";
        AgentProperties properties = properties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search(query, 20)).thenReturn(List.of(
            Map.of("title", "AI chips", "url", "https://news.example/ai", "description", "Machine learning"),
            Map.of("title", "Rail services", "url", "https://news.example/rail", "description", "City transport")));
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = configuredSearxngTool(properties, news, query);

        ToolResult result = tool.execute(objectMapper.writeValueAsString(Map.of("query", query, "limit", 2)), null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).path("data").path("web")).hasSize(1);
        assertThat(objectMapper.readTree(result.content()).path("data").path("web").get(0).path("title").asText())
            .isEqualTo("AI chips");
    }

    private WebSearchTool configuredSearxngTool(AgentProperties properties, GoogleNewsRssSearchProvider news,
                                                String query) throws IOException {
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor, news);
        tool.init();
        SearXngSearchProvider searxng = mock(SearXngSearchProvider.class);
        when(searxng.isAvailable()).thenReturn(true);
        when(searxng.search(org.mockito.ArgumentMatchers.eq(query), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "searXngProvider", searxng);
        return tool;
    }

    @Test
    void searchFailureWithoutExceptionMessageNamesTheFailureType() throws Exception {
        AgentProperties properties = properties();
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);

        Connection connection = mock(Connection.class);
        when(connection.userAgent(anyString())).thenReturn(connection);
        when(connection.timeout(anyInt())).thenReturn(connection);
        when(connection.get()).thenThrow(new java.net.ConnectException());

        try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
            jsoup.when(() -> Jsoup.connect(contains("q=offline"))).thenReturn(connection);

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"query\":\"offline\",\"limit\":5}", null, null);

            assertThat(result.success()).isFalse();
            assertThat(errorPayload(result).get("error").toString())
                .isEqualTo("Web search failed: ConnectException");
        }
    }

    @Test
    void searchRequiresNonBlankQuery() throws Exception {
        AgentProperties properties = properties();
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor); tool.init();

        assertThat(errorPayload(tool.execute("{\"query\":\"\"}", null, null)).get("error").toString()).contains("Query is required");
        assertThat(errorPayload(tool.execute("{\"query\":\"   \"}", null, null)).get("error").toString()).contains("Query is required");
        assertThat(errorPayload(tool.execute("{\"limit\":5}", null, null)).get("error").toString()).contains("Query is required");
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

            WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, redactor); tool.init();
            var result = tool.execute("{\"query\":\"default\"}", null, null);

            assertThat(result.success()).isTrue();
            var root = objectMapper.readTree(result.content());
            assertThat(root.path("data").path("web")).hasSize(1);
        }
    }
}
