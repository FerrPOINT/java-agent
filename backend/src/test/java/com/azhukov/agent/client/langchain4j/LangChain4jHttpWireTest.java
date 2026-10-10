package com.azhukov.agent.client.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
class LangChain4jHttpWireTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private HttpServer server;
    private volatile String responseBody;
    private volatile String contentType = "application/json";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requestMethod.set(exchange.getRequestMethod());
                requestPath.set(exchange.getRequestURI().getPath());
                requestBody.set(mapper.readTree(exchange.getRequestBody()));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"chatgpt-5.6-luna,developer", "gpt-4o,system"})
    void syncUsesRealWireRolesAndNormalizesLength(String modelName, String expectedRole) {
        responseBody = """
            {"id":"wire-sync","object":"chat.completion","created":1,"model":"fixture",
             "choices":[{"index":0,"message":{"role":"assistant","content":"wire reply"},
                         "finish_reason":"MAX_TOKENS"}],
             "usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}
            """;
        OpenAiChatModel model = OpenAiChatModel.builder()
            .baseUrl(baseUrl()).apiKey("owned-wire-fixture-only").modelName(modelName)
            .maxRetries(0).timeout(Duration.ofSeconds(5)).httpClientBuilder(transport()).build();

        ChatResponse response = model.chat(request());

        assertRequest(modelName, expectedRole, false);
        assertThat(response.aiMessage().text()).isEqualTo("wire reply");
        assertThat(response.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(2);
        assertThat(response.tokenUsage().outputTokenCount()).isEqualTo(3);
    }

    @Test
    void streamingConsumesRealSseTextAndNegativeIndexToolCall() throws Exception {
        contentType = "text/event-stream";
        responseBody = """
            data: {"id":"wire-stream","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{"role":"assistant","content":"wire "},"finish_reason":null}]}

            data: {"id":"wire-stream","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{"content":"stream"},"finish_reason":null}]}

            data: {"id":"wire-stream","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{"tool_calls":[{"index":-1,"id":"call_wire","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":null}]}

            data: {"id":"wire-stream","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{},"finish_reason":"function_call"}]}

            data: [DONE]

            """;
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
            .baseUrl(baseUrl()).apiKey("owned-wire-fixture-only").modelName("codex-mini-latest")
            .timeout(Duration.ofSeconds(5)).httpClientBuilder(transport()).build();
        CompletableFuture<ChatResponse> completed = new CompletableFuture<>();
        List<String> tokens = new CopyOnWriteArrayList<>();

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                tokens.add(token);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                completed.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                completed.completeExceptionally(error);
            }
        });
        ChatResponse response = completed.get(5, TimeUnit.SECONDS);

        assertRequest("codex-mini-latest", "developer", true);
        assertThat(tokens).containsExactly("wire ", "stream");
        assertThat(response.aiMessage().text()).isEqualTo("wire stream");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_EXECUTION);
        assertThat(response.aiMessage().toolExecutionRequests()).hasSize(1);
        var tool = response.aiMessage().toolExecutionRequests().getFirst();
        assertThat(tool.id()).isEqualTo("call_wire");
        assertThat(tool.name()).isEqualTo("lookup");
        assertThat(tool.arguments()).isEqualTo("{}");
    }

    private ChatRequest request() {
        return ChatRequest.builder()
            .messages(List.of(SystemMessage.from("wire system"), UserMessage.from("wire user")))
            .toolSpecifications(List.of(ToolSpecification.builder().name("lookup")
                .description("Fixture lookup").parameters(JsonObjectSchema.builder().build()).build()))
            .build();
    }

    private void assertRequest(String modelName, String expectedRole, boolean streaming) {
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestPath.get()).isEqualTo("/v1/chat/completions");
        JsonNode body = requestBody.get();
        assertThat(body.path("model").asText()).isEqualTo(modelName);
        assertThat(body.path("stream").asBoolean()).isEqualTo(streaming);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo(expectedRole);
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("wire system");
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("wire user");
        assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("lookup");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private HttpClientBuilder transport() {
        JdkHttpClientBuilder delegate = new JdkHttpClientBuilder();
        return new HttpClientBuilder() {
            @Override
            public Duration connectTimeout() { return delegate.connectTimeout(); }

            @Override
            public HttpClientBuilder connectTimeout(Duration timeout) {
                delegate.connectTimeout(timeout);
                return this;
            }

            @Override
            public Duration readTimeout() { return delegate.readTimeout(); }

            @Override
            public HttpClientBuilder readTimeout(Duration timeout) {
                delegate.readTimeout(timeout);
                return this;
            }

            @Override
            public HttpClient build() {
                return new FinishReasonNormalizingHttpClient(
                    new DeveloperRoleHttpClient(delegate.build(), () -> "unused-fixture-model"));
            }
        };
    }
}
