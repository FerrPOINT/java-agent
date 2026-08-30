package com.azhukov.agent.client.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEventListener;

import java.util.function.Supplier;

/**
 * Hermes parity (prompt_builder.py:903 + transports/chat_completions.py:526):
 * models whose name CONTAINS "gpt-5" or "codex" (substring, case-insensitive —
 * e.g. "chatgpt-5.6-luna") must receive the system prompt with
 * {@code role:"developer"} instead of {@code role:"system"}. Strict OpenAI
 * deployments reject system messages with "System messages are not allowed"
 * (seen live 2026-08-28 on the chatgpt-5.6-luna fallback).
 * <p>
 * LangChain4j 1.18 has no developer role in its message model
 * (OpenAiUtils.toOpenAiMessage handles System/User/Ai/Tool only — verified by
 * decompilation), so the swap is done at the HTTP boundary: the outgoing JSON
 * body's first system message is rewritten in place. The rewrite is applied
 * on every request (the body is rebuilt each call by the SDK), and skipped
 * entirely when the model name does not match the developer-role policy.
 * <p>
 * The wrapper is transport-agnostic: it decorates any {@link HttpClient}
 * (JDK-based by default) and preserves the SSE overload used by the streaming
 * chat model.
 */
public final class DeveloperRoleHttpClient implements HttpClient {

    private final HttpClient delegate;
    private final java.util.function.Supplier<String> modelName;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeveloperRoleHttpClient(HttpClient delegate,
                                   Supplier<String> modelName) {
        this.delegate = delegate;
        this.modelName = modelName;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        return delegate.execute(rewrite(request));
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventListener listener) {
        delegate.execute(rewrite(request), listener);
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        delegate.execute(rewrite(request), parser, listener);
    }

    private HttpRequest rewrite(HttpRequest request) {
        String body = request.body();
        if (body == null || body.isEmpty()) {
            return request;
        }
        try {
            JsonNode root = mapper.readTree(body);
            // The HTTP client is shared across concurrent sessions. The request body
            // holds the authoritative model; the supplier is only a legacy fallback.
            String requestedModel = root.path("model").asText(null);
            String model = requestedModel == null || requestedModel.isBlank() ? modelName.get() : requestedModel;
            String lower = model == null ? "" : model.toLowerCase();
            boolean developerRole = lower.contains("gpt-5") || lower.contains("codex");
            if (!developerRole) {
                return request;
            }
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray() || messages.isEmpty()) {
                return request;
            }
            JsonNode first = messages.get(0);
            if (!"system".equals(first.path("role").asText(null))) {
                return request;
            }
            ObjectNode firstObj = (ObjectNode) first;
            firstObj.put("role", "developer");
            // Rebuild the request with the rewritten body — HttpRequest is immutable.
            dev.langchain4j.http.client.HttpRequest.Builder b = dev.langchain4j.http.client.HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .body(mapper.writeValueAsString(root))
                .headers(request.headers());
            return b.build();
        } catch (Exception e) {
            // Never let a JSON rewrite failure break the model call — send the
            // original body unchanged.
            return request;
        }
    }
}
