package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
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

@AgentTool(
    name = "web_search",
    description = "Search the web via DuckDuckGo and return a list of relevant results with titles, URLs, and snippets.",
    toolset = "web"
)
@Component
public class WebSearchTool implements ToolHandler {

    private static final String DUCKDUCKGO_HTML = "https://html.duckduckgo.com/html/";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final int configuredLimit;
    private final ObjectMapper objectMapper;

    public WebSearchTool(AgentProperties agentProperties, ObjectMapper objectMapper) {
        this.configuredLimit = agentProperties.getWeb().getSearchResults();
        this.objectMapper = objectMapper;
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
            List<Map<String, String>> results = search(query, limit);
            if (results.isEmpty()) {
                return ToolResult.ok("No results found.");
            }
            return ToolResult.ok(objectMapper.writeValueAsString(results));
        } catch (IOException e) {
            return ToolResult.fail("Web search failed: " + e.getMessage());
        }
    }

    private List<Map<String, String>> search(String query, int limit) throws IOException {
        String url = DUCKDUCKGO_HTML + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        Document doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (compatible; JavaAgent/1.0)")
            .timeout(30000)
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
            item.put("snippet", snippetEl != null ? snippetEl.text() : "");
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
