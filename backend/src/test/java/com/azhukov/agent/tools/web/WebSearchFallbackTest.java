package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.UrlSafety;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSearchFallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void irrelevantPrimaryResultUsesUniversalFallbackWithoutChangingTheQuery() throws Exception {
        AgentProperties properties = properties();
        UrlSafety urlSafety = mock(UrlSafety.class);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, mock(Redactor.class));
        tool.init();

        SearXngSearchProvider primary = mock(SearXngSearchProvider.class);
        when(primary.name()).thenReturn("searxng");
        when(primary.isAvailable()).thenReturn(true);
        when(primary.search("obscureproject", 5)).thenReturn(List.of(
            Map.of("title", "Unrelated page", "url", "https://example.com", "description", "Nothing matching")));
        TavilySearchProvider fallback = mock(TavilySearchProvider.class);
        when(fallback.name()).thenReturn("tavily");
        when(fallback.isAvailable()).thenReturn(true);
        when(fallback.search("obscureproject", 5)).thenReturn(List.of(
            Map.of("title", "ObscureProject documentation", "url", "https://docs.example/obscureproject", "description", "Project home")));
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "searXngProvider", primary);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "tavilyProvider", fallback);

        var result = tool.execute("{\"query\":\"obscureproject\",\"limit\":5}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).at("/data/web/0/url").asText())
            .isEqualTo("https://docs.example/obscureproject");
        verify(primary).search("obscureproject", 5);
        verify(fallback).search("obscureproject", 5);
    }

    @Test
    void relevantPrimaryResultDoesNotCallFallback() throws Exception {
        AgentProperties properties = properties();
        UrlSafety urlSafety = mock(UrlSafety.class);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, mock(Redactor.class));
        tool.init();

        SearXngSearchProvider primary = mock(SearXngSearchProvider.class);
        when(primary.name()).thenReturn("searxng");
        when(primary.isAvailable()).thenReturn(true);
        when(primary.search("distributed systems", 5)).thenReturn(List.of(
            Map.of("title", "Distributed systems guide", "url", "https://example.com/distributed", "description", "Architecture guide")));
        TavilySearchProvider fallback = mock(TavilySearchProvider.class);
        when(fallback.name()).thenReturn("tavily");
        when(fallback.isAvailable()).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "searXngProvider", primary);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "tavilyProvider", fallback);

        var result = tool.execute("{\"query\":\"distributed systems\",\"limit\":5}", null, null);

        assertThat(result.success()).isTrue();
        verify(fallback, never()).search(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void nonNewsFallbackFailureReturnsHonestEmptyResult() throws Exception {
        AgentProperties properties = properties();
        UrlSafety urlSafety = mock(UrlSafety.class);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, mock(Redactor.class));
        tool.init();

        SearXngSearchProvider primary = mock(SearXngSearchProvider.class);
        when(primary.name()).thenReturn("searxng");
        when(primary.isAvailable()).thenReturn(true);
        when(primary.search("rareterm", 5)).thenReturn(List.of());
        TavilySearchProvider fallback = mock(TavilySearchProvider.class);
        when(fallback.name()).thenReturn("tavily");
        when(fallback.isAvailable()).thenReturn(true);
        when(fallback.search("rareterm", 5)).thenThrow(new IOException("Tavily returned HTTP 429"));
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "searXngProvider", primary);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "tavilyProvider", fallback);

        var result = tool.execute("{\"query\":\"rareterm\",\"limit\":5}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).at("/data/web")).isEmpty();
    }

    @Test
    void fallsThroughToAnotherUniversalProviderWhenEarlierFallbackIsEmpty() throws Exception {
        AgentProperties properties = properties();
        UrlSafety urlSafety = mock(UrlSafety.class);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, mock(Redactor.class));
        tool.init();

        SearXngSearchProvider primary = mock(SearXngSearchProvider.class);
        when(primary.isAvailable()).thenReturn(true);
        when(primary.search("rareterm", 5)).thenReturn(List.of());
        TavilySearchProvider tavily = mock(TavilySearchProvider.class);
        when(tavily.name()).thenReturn("tavily");
        when(tavily.isAvailable()).thenReturn(true);
        when(tavily.search("rareterm", 5)).thenReturn(List.of());
        ParallelSearchProvider parallel = mock(ParallelSearchProvider.class);
        when(parallel.name()).thenReturn("parallel");
        when(parallel.isAvailable()).thenReturn(true);
        when(parallel.search("rareterm", 5)).thenReturn(List.of(
            Map.of("title", "RareTerm reference", "url", "https://example.com/rareterm", "description", "Reference")));
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "searXngProvider", primary);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "tavilyProvider", tavily);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "parallelProvider", parallel);

        var result = tool.execute("{\"query\":\"rareterm\",\"limit\":5}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).at("/data/web/0/url").asText())
            .isEqualTo("https://example.com/rareterm");
        verify(tavily).search("rareterm", 5);
        verify(parallel).search("rareterm", 5);
    }

    @Test
    void newsSearchKeepsNewsRssSeparateFromGeneralFallback() throws Exception {
        AgentProperties properties = properties();
        UrlSafety urlSafety = mock(UrlSafety.class);
        when(urlSafety.isUrlAllowed(anyString())).thenReturn(true);
        GoogleNewsRssSearchProvider news = mock(GoogleNewsRssSearchProvider.class);
        when(news.search("systems news", 20)).thenReturn(List.of(
            Map.of("title", "Systems news", "url", "https://news.example/systems", "description", "Systems update")));
        WebSearchTool tool = new WebSearchTool(properties, objectMapper, urlSafety, mock(Redactor.class), news);
        tool.init();

        SearXngSearchProvider primary = mock(SearXngSearchProvider.class);
        when(primary.name()).thenReturn("searxng");
        when(primary.isAvailable()).thenReturn(true);
        when(primary.search("systems news", 20)).thenReturn(List.of());
        TavilySearchProvider fallback = mock(TavilySearchProvider.class);
        when(fallback.name()).thenReturn("tavily");
        when(fallback.isAvailable()).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "searXngProvider", primary);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "tavilyProvider", fallback);

        var result = tool.execute("{\"query\":\"systems news\",\"limit\":2}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(objectMapper.readTree(result.content()).at("/data/web/0/url").asText())
            .isEqualTo("https://news.example/systems");
        verify(fallback, never()).search(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private AgentProperties properties() {
        AgentProperties properties = new AgentProperties();
        properties.getWeb().setSearxngUrl("http://searxng.local");
        properties.getWeb().setKeylessFallbackEnabled(true);
        properties.getWeb().setParallelKeylessEnabled(true);
        properties.getWeb().setTavilyUrl("https://api.tavily.com/search");
        return properties;
    }
}
