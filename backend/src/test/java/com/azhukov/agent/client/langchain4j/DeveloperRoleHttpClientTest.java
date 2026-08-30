package com.azhukov.agent.client.langchain4j;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hermes parity regression: models whose name CONTAINS "gpt-5" or "codex"
 * (e.g. "chatgpt-5.6-luna") must receive the first system message with
 * role:"developer" on the wire — prompt_builder.py:903 matches by substring,
 * and strict deployments reject role:"system" ("System messages are not
 * allowed", live incident 2026-08-28).
 */
class DeveloperRoleHttpClientTest {

    private HttpRequest.Builder request(String body) {
        return dev.langchain4j.http.client.HttpRequest.builder()
            .method(HttpMethod.POST)
            .url("http://proxy/v1/chat/completions")
            .body(body);
    }

    @Test
    void rewritesSystemToDeveloperForGpt5SubstringModels() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.execute(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return mock(SuccessfulHttpResponse.class);
        });

        DeveloperRoleHttpClient client = new DeveloperRoleHttpClient(delegate,
            () -> "chatgpt-5.6-luna");

        client.execute(request(
            "{\"model\":\"chatgpt-5.6-luna\",\"messages\":[{\"role\":\"system\",\"content\":\"sys\"},{\"role\":\"user\",\"content\":\"hi\"}]}")
            .build());

        String body = captured.get().body();
        assertThat(body).contains("\"role\":\"developer\"");
        assertThat(body).doesNotContain("\"role\":\"system\"");
        // user message untouched
        assertThat(body).contains("{\"role\":\"user\",\"content\":\"hi\"}");
    }

    @Test
    void leavesOtherModelsAndBodiesUnchanged() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.execute(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return mock(SuccessfulHttpResponse.class);
        });

        String original = "{\"model\":\"app-test\",\"messages\":[{\"role\":\"system\",\"content\":\"sys\"},{\"role\":\"user\",\"content\":\"hi\"}]}";

        DeveloperRoleHttpClient client = new DeveloperRoleHttpClient(delegate, () -> "app-test");
        client.execute(request(original).build());
        assertThat(captured.get().body()).isEqualTo(original);

        // null / blank model names also pass through
        client.execute(request(original).build());
        assertThat(captured.get().body()).isEqualTo(original);
    }

    @Test
    void onlyFirstSystemMessageIsRewritten() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.execute(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return mock(SuccessfulHttpResponse.class);
        });

        DeveloperRoleHttpClient client = new DeveloperRoleHttpClient(delegate,
            () -> "codex-mini-latest");

        // First message is a user message — nothing to rewrite even though the
        // model matches the developer-role policy.
        String original = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        client.execute(request(original).build());
        assertThat(captured.get().body()).isEqualTo(original);
    }

    @Test
    void requestModelWinsOverSharedSupplier() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.execute(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return mock(SuccessfulHttpResponse.class);
        });

        DeveloperRoleHttpClient client = new DeveloperRoleHttpClient(delegate, () -> "gpt-5.6");
        client.execute(request(
            "{\"model\":\"app-test\",\"messages\":[{\"role\":\"system\",\"content\":\"sys\"}]}"
        ).build());

        assertThat(captured.get().body()).contains("\"role\":\"system\"");
        assertThat(captured.get().body()).doesNotContain("\"role\":\"developer\"");
    }

    @Test
    void invalidJsonFallsBackToOriginalBody() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.execute(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return mock(SuccessfulHttpResponse.class);
        });

        DeveloperRoleHttpClient client = new DeveloperRoleHttpClient(delegate,
            () -> "gpt-5.6");
        String original = "not-json{{{";
        client.execute(request(original).build());
        assertThat(captured.get().body()).isEqualTo(original);
    }
}
