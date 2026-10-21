package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@AgentTool(
    name = "web_search",
    description = "Search the web for information. Returns up to 5 results by default with titles, URLs, and descriptions. The query is passed through to configured search providers, so operators such as site:domain, filetype:pdf, intitle:word, -term, and \"exact phrase\" may work when the provider supports them.",
    toolset = "web"
)
@Component
public class WebSearchTool implements ToolHandler {

    private static final String DUCKDUCKGO_HTML = "https://html.duckduckgo.com/html/";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 100;
    private static final int SEARCH_IO_RETRIES = 3;
    private static final Set<String> SHORT_NEWS_TOPIC_TERMS = Set.of("ai");
    private static final Set<String> QUERY_STOP_TERMS = Set.of(
        "the", "and", "for", "from", "with", "about", "into", "that", "this", "find", "search", "web", "internet",
        "найди", "найти", "поиск", "поиске", "веб", "интернет", "интернете", "сайт", "сайте", "это", "этот", "эта",
        "эти", "что", "как", "для", "про", "или", "компания", "проект", "никнейм", "official", "site", "website"
    );
    private static final Pattern OFFICIAL_SITE_QUERY = Pattern.compile(
        "(?:official|официальн(?:ый|ая|ое|ые|ого|ому|ым|ом|ых|ыми)?|офиц)\\s+"
            + "(?:site|website|web\\s*site|сайт|сайта|сайте|сайты)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Set<String> NEWS_STOP_TERMS = Set.of(
        "latest", "news", "headline", "headlines", "последние", "последний", "новости", "новость", "новост",
        "дайджест", "сегодня", "свежие", "свежий", "today", "recent", "recently", "last",
        "the", "and", "for", "from", "with", "about", "in", "on", "of", "to", "this", "year", "years",
        "этот", "эта", "эти", "этого", "этой", "этому", "этим", "этом", "эту", "этих", "этими",
        "года", "году", "годом", "годе", "годы", "годов", "годах",
        "по", "об", "про", "из", "за", "на", "для", "новые", "новый", "новая", "самые", "самый",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "январь", "января", "январе", "февраль", "февраля", "феврале", "март", "марта", "марте",
        "апрель", "апреля", "апреле", "май", "мая", "мае", "июнь", "июня", "июне",
        "июль", "июля", "июле", "август", "августа", "августе", "сентябрь", "сентября", "сентябре",
        "октябрь", "октября", "октябре", "ноябрь", "ноября", "ноябре", "декабрь", "декабря", "декабре"
    );
    private static final Pattern TECHNOLOGY_RESULT = Pattern.compile(
        "технолог(?:ии|ий|ия|ию|иями|иях)?|(?<!\\p{L})ит(?!\\p{L})|телеком|data|(?<![\\p{L}\\p{N}])ai(?![\\p{L}\\p{N}])|цифров|беспилот|электробатар|программ"
    );

    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final UrlSafety urlSafety;
    private final Redactor redactor;
    private final WebsitePolicy websitePolicy;
    private final GoogleNewsRssSearchProvider googleNewsRssSearchProvider;
    private final OfficialWebsiteResolver officialWebsiteResolver;
    private final Map<String, Instant> providerCooldowns = new ConcurrentHashMap<>();
    private int configuredLimit;
    private SearXngSearchProvider searXngProvider;
    private TavilySearchProvider tavilyProvider;
    private ParallelSearchProvider parallelProvider;
    private ExaSearchProvider exaProvider;

    @Autowired
    public WebSearchTool(AgentProperties agentProperties, ObjectMapper objectMapper, UrlSafety urlSafety,
                         Redactor redactor, WebsitePolicy websitePolicy,
                         GoogleNewsRssSearchProvider googleNewsRssSearchProvider,
                         OfficialWebsiteResolver officialWebsiteResolver) {
        this.agentProperties = agentProperties;
        this.objectMapper = objectMapper;
        this.urlSafety = urlSafety;
        this.redactor = redactor;
        this.websitePolicy = websitePolicy;
        this.googleNewsRssSearchProvider = googleNewsRssSearchProvider;
        this.officialWebsiteResolver = officialWebsiteResolver;
    }

    WebSearchTool(AgentProperties agentProperties, ObjectMapper objectMapper, UrlSafety urlSafety,
                  Redactor redactor, GoogleNewsRssSearchProvider googleNewsRssSearchProvider) {
        this(agentProperties, objectMapper, urlSafety, redactor, new WebsitePolicy(agentProperties),
            googleNewsRssSearchProvider, new OfficialWebsiteResolver(objectMapper));
    }

    WebSearchTool(AgentProperties agentProperties, ObjectMapper objectMapper, UrlSafety urlSafety,
                  Redactor redactor, GoogleNewsRssSearchProvider googleNewsRssSearchProvider,
                  OfficialWebsiteResolver officialWebsiteResolver) {
        this(agentProperties, objectMapper, urlSafety, redactor, new WebsitePolicy(agentProperties),
            googleNewsRssSearchProvider, officialWebsiteResolver);
    }

    WebSearchTool(AgentProperties agentProperties, ObjectMapper objectMapper, UrlSafety urlSafety,
                  Redactor redactor) {
        this(agentProperties, objectMapper, urlSafety, redactor, new WebsitePolicy(agentProperties),
            new GoogleNewsRssSearchProvider(), new OfficialWebsiteResolver(objectMapper));
    }

    @PostConstruct
    void init() {
        configuredLimit = agentProperties.getWeb().getSearchResults();
        String searxngUrl = agentProperties.getWeb().getSearxngUrl();
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            searXngProvider = new SearXngSearchProvider(searxngUrl, urlSafety);
        }
        if (agentProperties.getWeb().isKeylessFallbackEnabled()) {
            tavilyProvider = new TavilySearchProvider(agentProperties.getWeb(), objectMapper);
            if (agentProperties.getWeb().isParallelKeylessEnabled()) {
                parallelProvider = new ParallelSearchProvider(objectMapper, agentProperties.getWeb().getSearchTimeoutSeconds());
            }
            if (agentProperties.getWeb().isExaKeylessEnabled()) {
                exaProvider = new ExaSearchProvider(objectMapper, agentProperties.getWeb().getSearchTimeoutSeconds());
            }
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
            int limit = Math.min(Math.max(1, args.limit() > 0 ? args.limit() : configuredLimit), MAX_LIMIT);
            List<Map<String, String>> results = isNewsQuery(query)
                ? searchNews(query, limit)
                : searchGeneralWeb(query, limit);
            results = preferVerifiedOfficialSite(query, results);
            return jsonSuccessResponse(results, limit);
        } catch (IOException e) {
            return jsonFailureResponse("Web search failed: " + failureDetail(e));
        } catch (Exception e) {
            return jsonFailureResponse("Web search failed: " + failureDetail(e));
        }
    }

    private List<Map<String, String>> searchGeneralWeb(String query, int limit) throws IOException {
        IOException primaryFailure = null;
        List<Map<String, String>> primaryResults = List.of();
        try {
            List<Map<String, String>> rawPrimaryResults = searchPrimary(query, limit);
            primaryResults = isGeneralFallbackEnabled()
                ? filterGeneralRelevance(query, rawPrimaryResults)
                : rawPrimaryResults;
        } catch (IOException failure) {
            primaryFailure = failure;
        }
        if (!primaryResults.isEmpty()) {
            return primaryResults;
        }

        List<Map<String, String>> fallbackResults = searchFallbacks(query, limit);
        if (fallbackResults.isEmpty() && primaryFailure != null) {
            throw primaryFailure;
        }
        return fallbackResults;
    }

    private List<Map<String, String>> searchFallbacks(String query, int limit) {
        for (WebSearchProvider provider : fallbackProviders()) {
            if (isCoolingDown(provider)) {
                continue;
            }
            try {
                List<Map<String, String>> results = filterGeneralRelevance(query, searchProvider(provider, query, limit));
                if (!results.isEmpty()) {
                    return results;
                }
            } catch (IOException failure) {
                coolDown(provider, failure);
            }
        }
        return List.of();
    }

    private List<WebSearchProvider> fallbackProviders() {
        List<WebSearchProvider> providers = new ArrayList<>();
        for (String configuredName : agentProperties.getWeb().getSearchProviderOrder()) {
            WebSearchProvider provider = switch (configuredName.toLowerCase(Locale.ROOT)) {
                case "tavily" -> tavilyProvider;
                case "parallel" -> parallelProvider;
                case "exa" -> exaProvider;
                default -> null;
            };
            if (provider != null && provider.isAvailable() && isProviderEnabled(configuredName)) {
                providers.add(provider);
            }
        }
        return providers;
    }

    private boolean isProviderEnabled(String providerName) {
        if (!agentProperties.getWeb().isKeylessFallbackEnabled()) {
            return false;
        }
        if ("parallel".equalsIgnoreCase(providerName) && !agentProperties.getWeb().isParallelKeylessEnabled()) {
            return false;
        }
        if ("exa".equalsIgnoreCase(providerName) && !agentProperties.getWeb().isExaKeylessEnabled()) {
            return false;
        }
        return agentProperties.getWeb().getSearchProviderOrder().stream().anyMatch(providerName::equalsIgnoreCase);
    }

    private boolean isGeneralFallbackEnabled() {
        return !fallbackProviders().isEmpty();
    }

    private List<Map<String, String>> searchPrimary(String query, int limit) throws IOException {
        if (searXngProvider != null && searXngProvider.isAvailable()) {
            return searchProvider(searXngProvider, query, limit);
        }
        return sanitizeResults(searchDuckDuckGoWithRetry(query, limit));
    }

    private List<Map<String, String>> searchProvider(WebSearchProvider provider, String query, int limit) throws IOException {
        return sanitizeResults(provider.search(query, limit));
    }

    private boolean isTavilyEnabled() {
        return agentProperties.getWeb().isKeylessFallbackEnabled()
            && tavilyProvider != null
            && tavilyProvider.isAvailable()
            && agentProperties.getWeb().getSearchProviderOrder().stream().anyMatch("tavily"::equalsIgnoreCase);
    }

    private boolean isCoolingDown(WebSearchProvider provider) {
        Instant until = providerCooldowns.get(provider.name());
        return until != null && until.isAfter(Instant.now());
    }

    private void coolDown(WebSearchProvider provider, IOException failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (message.contains("HTTP 429") || message.matches(".*HTTP 5\\d\\d.*")) {
            providerCooldowns.put(provider.name(), Instant.now().plusSeconds(agentProperties.getWeb().getSearchProviderCooldownSeconds()));
        }
    }

    private List<Map<String, String>> searchNews(String query, int limit) throws IOException {
        int providerLimit = expandedProviderLimit(query, limit);
        try {
            List<Map<String, String>> results = filterQueryRelevance(query, searchPrimary(query, providerLimit));
            return results.isEmpty() ? searchNewsFallback(query, limit) : results;
        } catch (IOException primaryFailure) {
            try {
                return searchNewsFallback(query, limit);
            } catch (IOException newsFailure) {
                primaryFailure.addSuppressed(newsFailure);
                throw primaryFailure;
            }
        }
    }

    private List<Map<String, String>> preferVerifiedOfficialSite(String query, List<Map<String, String>> results) {
        if (!OFFICIAL_SITE_QUERY.matcher(query).find()) {
            return results;
        }
        String entityName = OFFICIAL_SITE_QUERY.matcher(query).replaceFirst(" ").strip().replaceAll("\\s+", " ");
        if (entityName.isBlank()) {
            return results;
        }
        try {
            String officialUrl = officialWebsiteResolver.resolve(entityName);
            if (officialUrl == null || !urlSafety.isUrlAllowed(officialUrl) || websitePolicy.checkAccess(officialUrl) != null) {
                return results;
            }
            List<Map<String, String>> ranked = new ArrayList<>();
            ranked.add(Map.of("title", entityName + " - Official website", "url", officialUrl,
                "description", "Official website, verified from Wikidata."));
            results.stream().filter(result -> !officialUrl.equals(result.get("url"))).forEach(ranked::add);
            return ranked;
        } catch (IOException | RuntimeException ignored) {
            return results;
        }
    }

    private ToolResult jsonSuccessResponse(List<Map<String, String>> results, int limit) throws IOException {
        List<Map<String, Object>> webResults = new ArrayList<>();
        for (int i = 0; i < results.size() && i < limit; i++) {
            Map<String, String> source = results.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", source.getOrDefault("title", ""));
            entry.put("url", source.getOrDefault("url", ""));
            entry.put("description", source.getOrDefault("description", ""));
            entry.put("position", i + 1);
            webResults.add(entry);
        }
        return ToolResult.ok(redact(objectMapper.writeValueAsString(Map.of("success", true, "data", Map.of("web", webResults)))));
    }

    private int expandedProviderLimit(String query, int requestedLimit) {
        if (!isNewsQuery(query) || queryTopicTerms(query).isEmpty()) {
            return requestedLimit;
        }
        return Math.min(MAX_LIMIT, requestedLimit * 10);
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

    private List<Map<String, String>> searchNewsFallback(String query, int limit) throws IOException {
        return filterQueryRelevance(query, sanitizeResults(
            googleNewsRssSearchProvider.search(query, expandedProviderLimit(query, limit))));
    }

    private List<Map<String, String>> filterGeneralRelevance(String query, List<Map<String, String>> results) {
        List<String> queryTerms = generalQueryTerms(query);
        if (queryTerms.isEmpty()) {
            return results;
        }
        return results.stream().filter(result -> containsAnyTerm(result, queryTerms)).toList();
    }

    private List<String> generalQueryTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 3 && token.codePoints().anyMatch(Character::isLetter) && !QUERY_STOP_TERMS.contains(token)) {
                terms.add(token);
            }
        }
        return terms;
    }

    private boolean containsAnyTerm(Map<String, String> result, List<String> terms) {
        String text = (result.getOrDefault("title", "") + " " + result.getOrDefault("description", "") + " "
            + result.getOrDefault("url", "")).toLowerCase(Locale.ROOT);
        for (String term : terms) {
            for (String token : text.split("[^\\p{L}\\p{N}]+")) {
                if (token.equals(term) || (term.length() >= 5 && token.startsWith(term))) {
                    return true;
                }
            }
        }
        return false;
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
            if ((token.length() >= 4 || SHORT_NEWS_TOPIC_TERMS.contains(token))
                && token.codePoints().anyMatch(Character::isLetter)
                && !NEWS_STOP_TERMS.contains(token) && !token.startsWith("новост")) {
                terms.add(token.startsWith("технолог") ? "технолог" : token);
            }
        }
        return terms;
    }

    private boolean containsAnyTopicTerm(Map<String, String> result, List<String> topicTerms) {
        String text = (result.getOrDefault("title", "") + " " + result.getOrDefault("description", "")).toLowerCase(Locale.ROOT);
        return topicTerms.stream().anyMatch(term -> matchesTopicTerm(text, term));
    }

    private boolean matchesTopicTerm(String text, String term) {
        if (!"технолог".equals(term)) {
            for (String token : text.split("[^\\p{L}\\p{N}]+")) {
                if (term.length() >= 4 ? token.startsWith(term) : token.equals(term)) {
                    return true;
                }
            }
            return false;
        }
        return !text.contains("т-технологии") && TECHNOLOGY_RESULT.matcher(text).find();
    }

    private static boolean isNewsQuery(String query) {
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.equals("news") || token.equals("headline") || token.equals("headlines") || token.equals("новости")
                || token.equals("новость") || token.equals("дайджест") || token.startsWith("новост")) {
                return true;
            }
        }
        return false;
    }

    private static String failureDetail(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName()
            : failure.getClass().getSimpleName() + ": " + message;
    }

    private ToolResult jsonFailureResponse(String error) {
        String safeError = redact(error);
        try {
            return new ToolResult(false, objectMapper.writeValueAsString(Map.of("success", false, "error", safeError)), safeError);
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
                    } catch (InterruptedException interrupted) {
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
        Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (compatible; JavaAgent/1.0)").timeout(120000).get();
        List<Map<String, String>> out = new ArrayList<>();
        for (Element result : doc.select(".result")) {
            Element titleLink = result.selectFirst(".result__a");
            Element snippet = result.selectFirst(".result__snippet");
            if (titleLink == null) {
                continue;
            }
            out.add(Map.of("title", titleLink.text(), "url", absUrl(titleLink),
                "description", snippet == null ? "" : snippet.text()));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private String absUrl(Element link) {
        String href = link.attr("href");
        String target = decodeDuckDuckGoRedirect(href);
        if (target != null) {
            return target;
        }
        if (href.startsWith("http")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) return "https://duckduckgo.com" + href;
        return href;
    }

    private String decodeDuckDuckGoRedirect(String href) {
        if (href == null || href.isBlank()) return null;
        String candidate = href.trim();
        if (candidate.startsWith("//")) candidate = "https:" + candidate;
        else if (candidate.startsWith("/")) candidate = "https://duckduckgo.com" + candidate;
        try {
            URI uri = URI.create(candidate);
            if (uri.getHost() == null || !isDuckDuckGoHost(uri.getHost()) || uri.getPath() == null || !uri.getPath().startsWith("/l/")) {
                return null;
            }
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) return null;
            for (String pair : query.split("&")) {
                int equals = pair.indexOf('=');
                if (equals >= 0 && "uddg".equals(urlDecode(pair.substring(0, equals)))) {
                    String value = urlDecode(pair.substring(equals + 1));
                    return value.startsWith("http://") || value.startsWith("https://") ? value : null;
                }
            }
        } catch (IllegalArgumentException ignored) {
            return null;
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
