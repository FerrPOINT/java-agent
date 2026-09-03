package com.azhukov.agent.tools.web;

import com.azhukov.agent.core.security.UrlSafety;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearXngSearchProviderTest {

    @Mock
    private UrlSafety urlSafety;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> response;

    @Test
    void searchAllowsConfiguredLocalSearxngEvenWhenUrlSafetyWouldBlockIt() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
            {"results":[
              {"title":"Low","url":"https://low.example","content":"Low desc","score":0.1},
              {"title":"High","url":"https://high.example","content":"High desc","score":0.9}
            ]}
            """);
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(response);

        SearXngSearchProvider provider = new SearXngSearchProvider(
            "http://localhost:8080/",
            urlSafety,
            httpClient
        );

        List<Map<String, String>> results = provider.search("test query", 1);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst()).containsEntry("title", "High");
        assertThat(results.getFirst()).containsEntry("url", "https://high.example");
        assertThat(results.getFirst()).containsEntry("description", "High desc");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(request.getValue().uri().toString())
            .isEqualTo("http://localhost:8080/search?q=test+query&format=json&pageno=1");
        verifyNoInteractions(urlSafety);
    }
}
