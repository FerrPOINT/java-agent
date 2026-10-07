package com.azhukov.agent.tools.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleNewsRssSearchProviderTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> response;

    @Test
    void searchParsesRssItemsAndCapsTheResultCount() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
            <rss><channel>
              <item><title>First &amp; Latest</title><link>https://news.example/first</link>
                <description><![CDATA[<p>First <b>summary</b></p>]]></description></item>
              <item><title>Second</title><link>https://news.example/second</link>
                <description>Second summary</description></item>
            </channel></rss>
            """);
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        GoogleNewsRssSearchProvider provider = new GoogleNewsRssSearchProvider(httpClient);
        List<Map<String, String>> results = provider.search("latest technology", 1);

        assertThat(results).containsExactly(Map.of(
            "title", "First & Latest",
            "url", "https://news.example/first",
            "description", "First summary"));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().uri().toString())
            .contains("https://news.google.com/rss/search?q=latest+technology")
            .contains("hl=en-US");
    }

    @Test
    void searchRetriesCyrillicQueryWithRussianEditionWhenUsFeedIsEmpty() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(
            "<rss><channel></channel></rss>",
            "<rss><channel><item><title>Russian news</title><link>https://news.example/ru</link>"
                + "<description>Summary</description></item></channel></rss>");
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        GoogleNewsRssSearchProvider provider = new GoogleNewsRssSearchProvider(httpClient);

        assertThat(provider.search("новости технологий", 1)).containsExactly(Map.of(
            "title", "Russian news", "url", "https://news.example/ru", "description", "Summary"));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, org.mockito.Mockito.times(2))
            .send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getAllValues().getLast().uri().toString()).contains("hl=ru&gl=RU&ceid=RU:ru");
    }
}
