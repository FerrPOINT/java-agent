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
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        try {
            SearchArgs args = ToolHandler.parseJson(arguments, SearchArgs.class);
            String query = args.query();
            if (query == null || query.isBlank()) {
                return jsonFailureResponse("Query is required");
            }

            int limit = Math.min(
                Math.max(1, args.limit() > 0 ? args.limit() : configuredLimit),
                MAX_LIMIT
            );

            List<Map<String, String>> results;
            // Feature 1: Use SearXNG if configured, otherwise fall back to DuckDuckGo
            if (searXngProvider != null && searXngProvider.isAvailable()) {
                results = searXngProvider.search(query, limit);
            } else {
                results = searchDuckDuckGo(query, limit);
            }

            // Hermes parity: return {"data":{"web":[{title,url,description,position}]}}
            // instead of a flat array. Add position field for result ordering.
            List<Map<String, Object>> webResults = new java.util.ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                Map<String, String> src = results.get(i);
                entry.put("title", src.getOrDefault("title", ""));
                entry.put("url", src.getOrDefault("url", ""));
                entry.put("description", src.getOrDefault("description", ""));
                entry.put("position", i + 1);
                webResults.add(entry);
            }
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("success", true);
            response.put("data", java.util.Map.of("web", webResults));
            return ToolResult.ok(redact(objectMapper.writeValueAsString(response)));
        } catch (IOException e) {
            return jsonFailureResponse("Web search failed: " + e.getMessage());
        } catch (Exception e) {
            return jsonFailureResponse("Web search failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ToolResult jsonFailureResponse(String error) {
        String safeError = redact(error);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", safeError);
        try {
            return new ToolResult(false, objectMapper.writeValueAsString(response), safeError);
        } catch (Exception e) {
            return new ToolResult(false, "{\"success\":false,\"error\":\"Web search failed\"}", "Web search failed");
        }
    }

    private String redact(String output) {
        if (redactor == null) {
            return output;
        }
        String redacted = redactor.redact(output);
        return redacted == null ? output : redacted;
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
        String duckDuckGoTarget = decodeDuckDuckGoRedirect(href);
        if (duckDuckGoTarget != null) {
            return duckDuckGoTarget;
        }
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

    private String decodeDuckDuckGoRedirect(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }

        String candidate = href.trim();
        if (candidate.startsWith("//")) {
            candidate = "https:" + candidate;
        } else if (candidate.startsWith("/")) {
            candidate = "https://duckduckgo.com" + candidate;
        }

        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null
            || !isDuckDuckGoHost(host)
            || path == null
            || !path.startsWith("/l/")) {
            return null;
        }

        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = urlDecode(pair.substring(0, equals));
            if (!"uddg".equals(key)) {
                continue;
            }
            String value = urlDecode(pair.substring(equals + 1));
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return value;
            }
        }
        return null;
    }

    private boolean isDuckDuckGoHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("duckduckgo.com") || lower.endsWith(".duckduckgo.com");
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    public record SearchArgs(
        @ToolParam(description = "search query") String query,
        @ToolParam(description = "maximum number of results", required = false) int limit
    ) {}
}
