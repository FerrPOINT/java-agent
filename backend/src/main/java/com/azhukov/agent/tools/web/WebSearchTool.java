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
import java.util.Set;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;

@AgentTool(
    name = "web_search",
    description = "Search the web for information. Returns up to 5 results by default with titles, URLs, and descriptions. The query is passed through to the configured backend, so operators such as site:domain, filetype:pdf, intitle:word, -term, and \"exact phrase\" may work when the backend supports them.",
    toolset = "web"
)
@Component
public class WebSearchTool implements ToolHandler {

    private static final String DUCKDUCKGO_HTML = "https://html.duckduckgo.com/html/";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 100;
    private static final Set<String> NEWS_STOP_TERMS = Set.of(
        "latest", "news", "headline", "headlines", "последние", "последний", "новости", "новость", "новост",
        "дайджест", "сегодня", "свежие", "свежий", "октябрь", "октября", "октябре", "today", "recent",
        "recently", "october"
    );

    private final AgentProperties agentProperties;
    private int configuredLimit;
    private final ObjectMapper objectMapper;
    private final UrlSafety urlSafety;
    private final Redactor redactor;
    private final WebsitePolicy websitePolicy;
    private final GoogleNewsRssSearchProvider googleNewsRssSearchProvider;
    private SearXngSearchProvider searXngProvider;

    @Autowired
    public WebSearchTool(AgentProperties agentProperties, ObjectMapper objectMapper, UrlSafety urlSafety,
                         Redactor redactor, WebsitePolicy websitePolicy,
                         GoogleNewsRssSearchProvider googleNewsRssSearchProvider) {
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.urlSafety = urlSafety;
        this.redactor = redactor;
        this.websitePolicy = websitePolicy;
        this.googleNewsRssSearchProvider = googleNewsRssSearchProvider;
    }

    WebSearchTool(AgentProperties agentProperties, ObjectMapper objectMapper, UrlSafety urlSafety,
                  Redactor redactor, GoogleNewsRssSearchProvider googleNewsRssSearchProvider) {
        this(agentProperties, objectMapper, urlSafety, redactor, new WebsitePolicy(agentProperties),
            googleNewsRssSearchProvider);
    }

    WebSearchTool(AgentProperties agentProperties, ObjectMapper objectMapper, UrlSafety urlSafety,
                  Redactor redactor) {
        this(agentProperties, objectMapper, urlSafety, redactor, new WebsitePolicy(agentProperties),
            new GoogleNewsRssSearchProvider());
    }

    @PostConstruct
    void init() {
        configuredLimit = agentProperties.getWeb().getSearchResults();
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
            // Feature 1: Use SearXNG if configured, otherwise fall back to DuckDuckGo.
            // DDG html endpoint intermittently drops the TLS handshake from datacenter
            // IPs (observed 2-fails-then-ok in a row, session 8206abc2 "Remote host
            // terminated the handshake"). Retry transient IOExceptions with backoff
            // before surfacing the failure to the model.
            if (searXngProvider != null && searXngProvider.isAvailable()) {
                results = searchConfiguredSearxng(query, limit);
            } else {
                results = sanitizeResults(searchDuckDuckGoWithRetry(query, limit));
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
            return jsonFailureResponse("Web search failed: " + failureDetail(e));
        } catch (Exception e) {
            return jsonFailureResponse("Web search failed: " + failureDetail(e));
        }
    }

    private List<Map<String, String>> sanitizeResults(List<Map<String, String>> results) {
        List<Map<String, String>> safeResults = new ArrayList<>();
        for (Map<String, String> result : results) {
            String url = result.getOrDefault("url", "");
            if (!urlSafety.isUrlAllowed(url) || websitePolicy.checkAccess(url) != null) {
                continue;
            }
            safeResults.add(result);
        }
        return safeResults;
    }

    private List<Map<String, String>> searchConfiguredSearxng(String query, int limit) throws IOException {
        try {
            List<Map<String, String>> results = sanitizeResults(searXngProvider.search(query, limit));
            if (isNewsQuery(query)) {
                results = filterQueryRelevance(query, results);
            }
            return results.isEmpty() && isNewsQuery(query)
                ? searchNewsFallback(query, limit)
                : results;
        } catch (IOException searxngFailure) {
            if (!isNewsQuery(query)) {
                throw searxngFailure;
            }
            try {
                return searchNewsFallback(query, limit);
            } catch (IOException newsFailure) {
                searxngFailure.addSuppressed(newsFailure);
                throw searxngFailure;
            }
        }
    }

    private List<Map<String, String>> searchNewsFallback(String query, int limit) throws IOException {
        return filterQueryRelevance(query, sanitizeResults(googleNewsRssSearchProvider.search(query, limit)));
    }

    private List<Map<String, String>> filterQueryRelevance(String query, List<Map<String, String>> results) {
        List<String> topicTerms = queryTopicTerms(query);
        if (topicTerms.isEmpty()) {
            return results;
        }
        return results.stream().filter(result -> containsAnyTopicTerm(result, topicTerms)).toList();
    }

    private List<String> queryTopicTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 4 && !NEWS_STOP_TERMS.contains(token) && !token.startsWith("новост")) {
                terms.add(token.startsWith("технолог") ? "технолог" : token);
            }
        }
        return terms;
    }

    private boolean containsAnyTopicTerm(Map<String, String> result, List<String> topicTerms) {
        String text = (result.getOrDefault("title", "") + " "
            + result.getOrDefault("description", "")).toLowerCase(Locale.ROOT);
        return topicTerms.stream().anyMatch(text::contains);
    }

    private static boolean isNewsTerm(String token) {
        return token.equals("news") || token.equals("headline") || token.equals("headlines")
            || token.equals("новости") || token.equals("новость") || token.equals("дайджест")
            || token.startsWith("новост");
    }

    private static boolean isNewsQuery(String query) {
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (isNewsTerm(token)) {
                return true;
            }
        }
        return false;
    }

    private static String failureDetail(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName() + ": " + message;
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

    private static final int SEARCH_IO_RETRIES = 3;

    private List<Map<String, String>> searchDuckDuckGoWithRetry(String query, int limit) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= SEARCH_IO_RETRIES; attempt++) {
            try {
                return searchDuckDuckGo(query, limit);
            } catch (IOException e) {
                last = e;
                if (attempt < SEARCH_IO_RETRIES) {
                    try {
                        Thread.sleep(700L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
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
