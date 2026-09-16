package com.azhukov.agent.client.langchain4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upstream parity (can1357/oh-my-pi#9566 via Hermes 2598247ff5): uppercase /
 * aliased finish_reason values fold to the canonical lowercase OpenAI
 * contract at wire intake, so stop handling and LENGTH recovery never skip.
 */
class FinishReasonNormalizingHttpClientTest {

    private final FinishReasonNormalizingHttpClient client =
        new FinishReasonNormalizingHttpClient(null);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizeFoldsUppercaseAndAliases() {
        assertThat(FinishReasonNormalizingHttpClient.normalize("STOP")).isEqualTo("stop");
        assertThat(FinishReasonNormalizingHttpClient.normalize("MAX_TOKENS")).isEqualTo("length");
        assertThat(FinishReasonNormalizingHttpClient.normalize("max_tokens")).isEqualTo("length");
        assertThat(FinishReasonNormalizingHttpClient.normalize("End")).isEqualTo("stop");
        assertThat(FinishReasonNormalizingHttpClient.normalize("function_call")).isEqualTo("tool_calls");
        assertThat(FinishReasonNormalizingHttpClient.normalize("CONTENT_FILTER")).isEqualTo("content_filter");
        // contract values pass through byte-identical
        assertThat(FinishReasonNormalizingHttpClient.normalize("stop")).isEqualTo("stop");
        assertThat(FinishReasonNormalizingHttpClient.normalize("length")).isEqualTo("length");
        // unknown and empty pass through (callers keep their defaults)
        assertThat(FinishReasonNormalizingHttpClient.normalize("")).isEmpty();
        assertThat(FinishReasonNormalizingHttpClient.normalize(null)).isNull();
        assertThat(FinishReasonNormalizingHttpClient.normalize("poolside_int")).isEqualTo("poolside_int");
    }

    @Test
    void rewritesNonStreamingResponseBody() throws Exception {
        String body = """
            {"id":"1","choices":[{"index":0,"finish_reason":"MAX_TOKENS","message":{"role":"assistant","content":"hi"}}]}""";
        String rewritten = client.rewriteJson(body);
        assertThat(mapper.readTree(rewritten).path("choices").get(0).path("finish_reason").asText())
            .isEqualTo("length");
    }

    @Test
    void leavesCleanBodiesUntouched() {
        String body = """
            {"id":"1","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hi"}}]}""";
        assertThat(client.rewriteJson(body)).isSameAs(body);
        assertThat(client.rewriteJson(null)).isNull();
        assertThat(client.rewriteJson("")).isEmpty();
        assertThat(client.rewriteJson("not json")).isEqualTo("not json");
    }

    @Test
    void nonStringFinishReasonPassesThrough() {
        String body = """
            {"choices":[{"index":0,"finish_reason":null,"message":{}}]}""";
        assertThat(client.rewriteJson(body)).isSameAs(body);
    }
}
