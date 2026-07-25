package com.azhukov.agent.tools.delegate;

import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DelegateTaskToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void returnsTaskResultFromSubAgent() throws Exception {
        String expectedResult = "Task completed successfully";
        String responseJson = MAPPER.writeValueAsString(Map.of("content", expectedResult));

        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(responseJson);

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        DelegateTaskTool tool = new TestableDelegateTaskTool(httpClient);
        Session session = Session.create("user1", "openai", "gpt-4");

        ToolResult result = tool.execute("{\"goal\":\"analyze logs\"}", null, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo(expectedResult);
    }

    @Test
    void failsWhenGoalMissing() {
        DelegateTaskTool tool = new DelegateTaskTool();
        Session session = Session.create("user1", "openai", "gpt-4");

        ToolResult result = tool.execute("{}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("goal is required");
    }

    @Test
    void failsWhenMaxDepthReached() {
        DelegateTaskTool tool = new TestableDelegateTaskTool(null);
        Session session = new Session(UUID.randomUUID(), "user1", null, "openai", "gpt-4", null,
            Map.of("delegation_depth", "3"));

        ToolResult result = tool.execute("{\"goal\":\"nested task\"}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Maximum delegation depth");
    }

    @Test
    void returnsHttpErrorOnNon200Response() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn("Internal Server Error");

        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        DelegateTaskTool tool = new TestableDelegateTaskTool(httpClient);
        Session session = Session.create("user1", "openai", "gpt-4");

        ToolResult result = tool.execute("{\"goal\":\"failing task\"}", null, session);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("Sub-agent HTTP 500");
    }

    private static class TestableDelegateTaskTool extends DelegateTaskTool {
        private final HttpClient httpClient;

        TestableDelegateTaskTool(HttpClient httpClient) {
            super(httpClient);
            this.httpClient = httpClient;
        }

        @Override
        protected HttpClient createHttpClient() {
            return httpClient;
        }
    }
}
