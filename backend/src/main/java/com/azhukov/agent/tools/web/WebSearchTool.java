package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.core.security.Redactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import jakarta.annotation.PostConstruct;

@AgentTool(
    name = "web_search",
    description = "Search the web for information. Returns up to 5 results by default with titles, URLs, and descriptions. The query is passed through to the configured backend, so operators such as site:domain, filetype:pdf, intitle:word, -term, and \"exact phrase\" may work when the backend supports them.",
    toolset = "web"
)
@Component
@RequiredArgsConstructor
public class WebSearchTool implements ToolHandler {

    private static final String DUCKDUCKGO_HTML = "https://html.duckduckgo.com/html/";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 100;

    private final AgentProperties agentProperties;
    private int configuredLimit;
    private final ObjectMapper objectMapper;
    private final UrlSafety urlSafety;
    private final Redactor redactor;
    private SearXngSearchProvider searXngProvider;

    @PostConstruct
    void init() {
        configuredLimit = agentProperties.getWeb().getSearchResults();
        // Feature 1: SearXNG provider — if searxng-url is set, use it; fall back to DuckDuckGo
        String searxngUrl = agentProperties.getWeb().getSearxngUrl();
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            searXngProvider = new SearXngSearchProvider(searxngUrl, urlSafety);
        }
    }
    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SearchArgs args = ToolHandler.parseJson(arguments, SearchArgs.class);
        String query = args.query();
        if (query == null || query.isBlank()) {
            return ToolResult.fail("Query is required");
        }

        int limit = Math.min(
            Math.max(1, args.limit() > 0 ? args.limit() : configuredLimit),
            MAX_LIMIT
        );

        try {
            List<Map<String, String>> results;
            // Feature 1: Use SearXNG if configured, otherwise fall back to DuckDuckGo
            if (searXngProvider != null && searXngProvider.isAvailable()) {
                results = searXngProvider.search(query, limit);
            } else {
                results = searchDuckDuckGo(query, limit);
            }
            if (results.isEmpty()) {
                return ToolResult.ok("No results found.");
            }
            return ToolResult.ok(objectMapper.writeValueAsString(results));
        } catch (IOException e) {
            return ToolResult.fail("Web search failed: " + e.getMessage());
        }
    }

    private List<Map<String, String>> searchDuckDuckGo(String query, int limit) throws IOException {
        String url = DUCKDUCKGO_HTML + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        if (!urlSafety.isUrlAllowed(url)) {
            throw new IOException("URL is not allowed by safety policy: " + url);
        }
        Document doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; JavaAgent/1.0)")
            .timeout(120000)
            .get();

        List<Map<String, String>> out = new ArrayList<>();
        for (Element result : doc.select(".result")) {
            Element titleLink = result.selectFirst(".result__a");
            Element snippetEl = result.selectFirst(".result__snippet");
            if (titleLink == null) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", titleLink.text());
            item.put("url", absUrl(titleLink));
            item.put("description", snippetEl != null ? snippetEl.text() : "");
            out.add(item);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private String absUrl(Element link) {
        String href = link.attr("href");
        if (href.startsWith("http")) {
            return href;
        }
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        if (href.startsWith("/")) {
            return "https://duckduckgo.com" + href;
        }
        return href;
    }

    public record SearchArgs(
        @ToolParam(description = "search query") String query,
        @ToolParam(description = "maximum number of results", required = false) int limit
    ) {}
}