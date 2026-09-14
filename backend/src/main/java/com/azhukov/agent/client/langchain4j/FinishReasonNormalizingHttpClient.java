package com.azhukov.agent.client.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;

import java.util.Locale;

/**
 * Hermes parity (agent/message_sanitization.py {@code normalize_finish_reason},
 * port of can1357/oh-my-pi#9566): some OpenAI-compatible gateways fronting
 * Gemini backends emit uppercase (STOP, MAX_TOKENS) or aliased (end,
 * function_call) finish reasons. LangChain4j maps wire strings to its enum
 * CASE-SENSITIVELY (lowercase-only lookupswitch in OpenAiUtils), so those
 * values map to {@code null} and silently skip stop handling and LENGTH
 * recovery downstream.
 *
 * <p>This client wraps the transport and folds finish_reason to the canonical
 * lowercase OpenAI contract at WIRE INTAKE — both the non-streaming JSON
 * response body and every streamed SSE {@code data:} chunk — before the SDK
 * ever parses them. Non-string and empty values pass through unchanged so
 * existing {@code or "stop"} defaults keep their behavior.
 *
 * <p>Alias map (Hermes {@code _FINISH_REASON_ALIASES}):
 * {@code max_tokens→length} (Gemini/Anthropic cap reason),
 * {@code end→stop} (some gateways' clean-completion spelling),
 * {@code function_call→tool_calls} (OpenAI legacy pre-tools spelling).
 */
public final class FinishReasonNormalizingHttpClient implements HttpClient {

    private final HttpClient delegate;
    private final ObjectMapper mapper = new ObjectMapper();

    public FinishReasonNormalizingHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException, RuntimeException {
        SuccessfulHttpResponse response = delegate.execute(request);
        String body = response.body();
        String rewritten = rewriteJson(body);
        if (rewritten == null || rewritten.equals(body)) {
            return response;
        }
        return SuccessfulHttpResponse.builder()
            .statusCode(response.statusCode())
            .headers(response.headers())
            .body(rewritten)
            .build();
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventListener listener) {
        delegate.execute(request, new NormalizingSseListener(listener));
    }

    @Override
    public void execute(HttpRequest request,
                        dev.langchain4j.http.client.sse.ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        delegate.execute(request, parser, new NormalizingSseListener(listener));
    }

    /** Normalize a finish_reason field on a parsed JSON node, in place. */
    private boolean normalizeNode(ObjectNode node) {
        JsonNode fr = node.get("finish_reason");
        if (fr == null || !fr.isTextual()) {
            return false;
        }
        String raw = fr.asText();
        String normalized = normalize(raw);
        if (normalized.equals(raw)) {
            return false;
        }
        node.put("finish_reason", normalized);
        return true;
    }

    /** Rewrite every choices[].finish_reason in a chat completion body. */
    String rewriteJson(String body) {
        if (body == null || body.isEmpty() || !body.contains("finish_reason")) {
            return body;
        }
        try {
            JsonNode root = mapper.readTree(body);
            boolean changed = false;
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray()) {
                for (JsonNode choice : choices) {
                    if (choice instanceof ObjectNode choiceObj) {
                        changed |= normalizeNode(choiceObj);
                    }
                }
            }
            return changed ? mapper.writeValueAsString(root) : body;
        } catch (Exception e) {
            return body; // never break the model call on a rewrite failure
        }
    }

    /** Fold a wire finish_reason to the canonical lowercase OpenAI value. */
    static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "max_tokens" -> "length";
            case "end" -> "stop";
            case "function_call" -> "tool_calls";
            default -> raw.trim().toLowerCase(Locale.ROOT);
        };
    }

    private final class NormalizingSseListener implements ServerSentEventListener {
        private final ServerSentEventListener delegateListener;

        NormalizingSseListener(ServerSentEventListener delegateListener) {
            this.delegateListener = delegateListener;
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            delegateListener.onEvent(rewriteEvent(event), context);
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            delegateListener.onEvent(rewriteEvent(event));
        }

        @Override
        public void onError(Throwable error) {
            delegateListener.onError(error);
        }

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            delegateListener.onOpen(response);
        }

        @Override
        public void onClose() {
            delegateListener.onClose();
        }

        private ServerSentEvent rewriteEvent(ServerSentEvent event) {
            String data = event.data();
            if (data == null || data.isEmpty() || !data.contains("finish_reason")) {
                return event;
            }
            try {
                JsonNode root = mapper.readTree(data);
                boolean changed = false;
                JsonNode choices = root.get("choices");
                if (choices != null && choices.isArray()) {
                    for (JsonNode choice : choices) {
                        if (choice instanceof ObjectNode choiceObj) {
                            changed |= normalizeNode(choiceObj);
                        }
                    }
                }
                if (!changed) {
                    return event;
                }
                return new ServerSentEvent(event.event(), mapper.writeValueAsString(root));
            } catch (Exception e) {
                return event; // never break the stream on a rewrite failure
            }
        }
    }
}
