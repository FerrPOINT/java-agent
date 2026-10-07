package com.azhukov.agent.tools.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Google News' public RSS search feed when the metasearch backend has no results.
 */
@Component
public class GoogleNewsRssSearchProvider {

    private static final String SEARCH_URL = "https://news.google.com/rss/search?q=";

    private final HttpClient httpClient;

    public GoogleNewsRssSearchProvider() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    GoogleNewsRssSearchProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<Map<String, String>> search(String query, int limit) throws IOException {
        List<Map<String, String>> results = searchFeed(query, limit, "en-US", "US", "US:en");
        if (!results.isEmpty() || !containsCyrillic(query)) {
            return results;
        }
        return searchFeed(query, limit, "ru", "RU", "RU:ru");
    }

    private boolean containsCyrillic(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
            == Character.UnicodeScript.CYRILLIC);
    }

    private List<Map<String, String>> searchFeed(String query, int limit, String language, String country,
                                                  String edition) throws IOException {
        String url = SEARCH_URL + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&hl=" + language + "&gl=" + country + "&ceid=" + edition;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/rss+xml, application/xml;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (compatible; JavaAgent/1.0)")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Google News RSS returned HTTP " + response.statusCode());
            }
            return parseResults(response.body(), limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Google News RSS request interrupted", e);
        }
    }

    private List<Map<String, String>> parseResults(String body, int limit) {
        Document document = Jsoup.parse(body, "", Parser.xmlParser());
        List<Map<String, String>> results = new ArrayList<>();
        for (Element item : document.select("item")) {
            String title = item.selectFirst("title") != null ? item.selectFirst("title").text() : "";
            String url = item.selectFirst("link") != null ? item.selectFirst("link").text() : "";
            String description = item.selectFirst("description") != null
                ? Jsoup.parse(item.selectFirst("description").text()).text()
                : "";
            if (title.isBlank() || url.isBlank()) {
                continue;
            }
            Map<String, String> result = new LinkedHashMap<>();
            result.put("title", title);
            result.put("url", url);
            result.put("description", description);
            results.add(result);
            if (results.size() >= limit) {
                break;
            }
        }
        return results;
    }
}
