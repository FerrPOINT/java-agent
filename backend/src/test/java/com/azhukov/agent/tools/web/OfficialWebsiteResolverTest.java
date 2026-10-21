package com.azhukov.agent.tools.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialWebsiteResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvesDeclaredOfficialWebsiteForBestMatchingEntity() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> searchResponse = response(200, """
            {"search":[{"id":"Q21708200","label":"OpenAI"}]}
            """);
        HttpResponse<String> entityResponse = response(200, """
            {"entities":{"Q21708200":{"claims":{"P856":[
              {"mainsnak":{"datavalue":{"value":"https://openai.com/"}}}
            ]}}}}
            """);
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(searchResponse, entityResponse);
        OfficialWebsiteResolver resolver = new OfficialWebsiteResolver(objectMapper, httpClient);

        assertThat(resolver.resolve("OpenAI")).isEqualTo("https://openai.com/");

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, org.mockito.Mockito.times(2)).send(requests.capture(), any());
        assertThat(requests.getAllValues().get(0).uri().toString()).contains("action=wbsearchentities").contains("search=OpenAI");
        assertThat(requests.getAllValues().get(1).uri().toString()).contains("action=wbgetentities").contains("ids=Q21708200");
    }

    @Test
    void returnsNullWhenMatchingEntityDoesNotDeclareWebsite() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> searchResponse = response(200, """
            {"search":[{"id":"Q1","label":"No website"}]}
            """);
        HttpResponse<String> entityResponse = response(200, """
            {"entities":{"Q1":{"claims":{}}}}
            """);
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(searchResponse, entityResponse);
        OfficialWebsiteResolver resolver = new OfficialWebsiteResolver(objectMapper, httpClient);

        assertThat(resolver.resolve("No website")).isNull();
    }

    @Test
    void failsWithUsefulErrorForNonSuccessfulResponse() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> unavailable = response(503, "{}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
            .thenReturn(unavailable);
        OfficialWebsiteResolver resolver = new OfficialWebsiteResolver(objectMapper, httpClient);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolve("OpenAI"))
            .isInstanceOf(IOException.class)
            .hasMessage("Wikidata returned HTTP 503");
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
