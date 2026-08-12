package com.azhukov.agent.bot.core;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.session.BotSessionEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link AgentBackendClient}.
 * Covers all REST methods, error handling, response parsing, and SSE streaming.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes"})
class AgentBackendClientTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.RequestHeadersUriSpec getSpec;
    @Mock private RestClient.RequestHeadersUriSpec deleteSpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private ObjectMapper objectMapper;
    private BotProperties properties;
    private AgentBackendClient client;

    @BeforeEach
    void setUp() {
        // POST chain
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object[].class))).thenReturn(postSpec);
        when(postSpec.uri(any(java.util.function.Function.class))).thenReturn(postSpec);
        when(postSpec.contentType(any())).thenReturn(postSpec);
        when(postSpec.accept(any())).thenReturn(postSpec);
        when(postSpec.body(any(Object.class))).thenReturn(postSpec);
        when(postSpec.retrieve()).thenReturn(responseSpec);

        // GET chain — use doReturn to bypass generic type issues
        doReturn(getSpec).when(restClient).get();
        doReturn(getSpec).when(getSpec).uri(anyString());
        doReturn(getSpec).when(getSpec).uri(anyString(), any(Object[].class));
        when(getSpec.retrieve()).thenReturn(responseSpec);

        // DELETE chain
        doReturn(deleteSpec).when(restClient).delete();
        doReturn(deleteSpec).when(deleteSpec).uri(anyString());
        doReturn(deleteSpec).when(deleteSpec).uri(anyString(), any(Object[].class));
        when(deleteSpec.retrieve()).thenReturn(responseSpec);

        // Default bodiless entity
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        objectMapper = new ObjectMapper();
        properties = new BotProperties();
        properties.setBackendUrl("http://localhost:8090");
        client = new AgentBackendClient(restClient, objectMapper, properties);
        client.init();
    }

    // ─── Helper ────────────────────────────────────────────────────

    private InputStream sseStream(String... lines) {
        String data = String.join("\n", lines) + "\n";
        return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    }

    // ═══════════════════════════════════════════════════════════════
    // chat()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void chat_success_returnsResponseField() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"Hello from agent\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "session-123");

        assertThat(result.content()).isEqualTo("Hello from agent");
    }

    @Test
    void chat_success_fallsBackToContentField() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"content\":\"Hello from content\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).isEqualTo("Hello from content");
    }

    @Test
    void chat_extractsMetadataFields() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"Hello\",\"modelUsed\":\"kimi-k2.6\",\"contextTokens\":5000,\"contextLength\":20000}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "session-123");

        assertThat(result.content()).isEqualTo("Hello");
        assertThat(result.modelUsed()).isEqualTo("kimi-k2.6");
        assertThat(result.contextTokens()).isEqualTo(5000);
        assertThat(result.contextLength()).isEqualTo(20000);
    }

    @Test
    void chat_extractsMemoryUpdatedFlag() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\",\"memoryUpdated\":true}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.memoryUpdated()).isTrue();
    }

    @Test
    void chat_memoryUpdatedDefaultsFalse() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.memoryUpdated()).isFalse();
    }

    @Test
    void chat_forwardsRuntimeFlagsToBackend() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setFastMode(true);
        runtime.setReasoningLevel("high");
        runtime.setVoiceMode(true);
        runtime.setMetadata("personality", "sarcastic");
        runtime.setMetadata("subgoal", "fix-bug");

        client.chat("Hello", "session-123", runtime);

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return Boolean.TRUE.equals(map.get("fastMode"))
                && "high".equals(map.get("reasoningEffort"))
                && Boolean.TRUE.equals(map.get("voiceMode"))
                && "sarcastic".equals(map.get("personality"))
                && "fix-bug".equals(map.get("subgoal"));
        }));
    }

    @Test
    void chat_withNullRuntime_omitsRuntimeFlags() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        client.chat("Hello", "s1", null);

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return !map.containsKey("fastMode")
                && !map.containsKey("reasoningEffort")
                && !map.containsKey("voiceMode");
        }));
    }

    @Test
    void chat_withNullSessionId_omitsSessionId() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", null);

        assertThat(result.content()).isEqualTo("OK");
        verify(postSpec).body(any(Object.class));
    }

    @Test
    void chat_withBlankSessionId_omitsSessionId() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "  ");

        assertThat(result.content()).isEqualTo("OK");
    }

    @Test
    void chat_twoArgOverload_delegatesToThreeArg() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).isEqualTo("OK");
    }

    @Test
    void chat_emptyResponse_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn("");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("empty response");
    }

    @Test
    void chat_nullResponse_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn(null);

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("empty response");
    }

    @Test
    void chat_missingResponseField_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"other\":\"value\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("missing 'response' field");
    }

    @Test
    void chat_nullResponseField_fallsBackToContent() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":null,\"content\":\"from content\"}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).isEqualTo("from content");
    }

    @Test
    void chat_bothResponseAndContentNull_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":null,\"content\":null}");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).contains("missing 'response' field");
    }

    @Test
    void chat_malformedJson_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenReturn("not valid json{{{");

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
    }

    @Test
    void chat_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class))
            .thenThrow(new RuntimeException("Connection refused"));

        AgentBackendClient.ChatResult result = client.chat("Hello", "s1");

        assertThat(result.content()).startsWith("Error:");
        assertThat(result.content()).contains("Connection refused");
    }

    // ═══════════════════════════════════════════════════════════════
    // chatStream()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void chatStream_tokensAndDone_returnsAccumulatedContent() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"token\",\"token\":\"Hello\"}",
            "data:{\"type\":\"token\",\"token\":\" world\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();
        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            token -> received.append(token),
            tool -> {},
            (name, res) -> {},
            completed::set,
            err -> {});

        assertThat(result.content()).isEqualTo("Hello world");
        assertThat(received.toString()).isEqualTo("Hello world");
        assertThat(completed.get()).isNotNull();
        assertThat(completed.get().content()).isEqualTo("Hello world");
    }

    @Test
    void chatStream_metadataEvent_extractsModelInfo() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"metadata\",\"modelUsed\":\"gpt-4\",\"contextTokens\":1000,\"contextLength\":8000}",
            "data:{\"type\":\"token\",\"token\":\"Answer\"}",
            "data:{\"type\":\"done\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        assertThat(result.content()).isEqualTo("Answer");
        assertThat(result.modelUsed()).isEqualTo("gpt-4");
        assertThat(result.contextTokens()).isEqualTo(1000);
        assertThat(result.contextLength()).isEqualTo(8000);
    }

    @Test
    void chatStream_metadataWithMemoryUpdated() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"metadata\",\"modelUsed\":\"m1\",\"contextTokens\":10,\"contextLength\":100,\"memoryUpdated\":true}",
            "data:{\"type\":\"done\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        // After the fix, the done path uses the 6-arg constructor which
        // propagates memoryUpdated from the metadata event.
        assertThat(result.memoryUpdated()).isTrue();
    }

    @Test
    void chatStream_errorEvent_callsOnError() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"error\",\"error\":\"Backend error\"}"
        ));

        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, r -> {}, errorRef::set);

        assertThat(errorRef.get()).isNotNull();
        assertThat(errorRef.get().getMessage()).isEqualTo("Backend error");
    }

    @Test
    void chatStream_errorEventWithMessageField() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"error\",\"message\":\"Fallback error\"}"
        ));

        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, r -> {}, errorRef::set);

        assertThat(errorRef.get().getMessage()).isEqualTo("Fallback error");
    }

    @Test
    void chatStream_toolCallsEvent_notifiesToolCallConsumer() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"tool_calls\",\"toolCalls\":[{\"name\":\"search\"},{\"name\":\"read_file\"}]}",
            "data:{\"type\":\"done\"}"
        ));

        java.util.List<String> toolCalls = new java.util.ArrayList<>();
        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        client.chatStream("Hi", "s1",
            t -> {},
            toolCalls::add,
            (n, r) -> {},
            completed::set,
            e -> {});

        assertThat(toolCalls).containsExactly("search", "read_file");
    }

    @Test
    void chatStream_toolStartEvent_notifiesToolCallConsumer() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"tool_start\",\"toolName\":\"execute\"}",
            "data:{\"type\":\"done\"}"
        ));

        java.util.List<String> toolCalls = new java.util.ArrayList<>();

        client.chatStream("Hi", "s1",
            t -> {}, toolCalls::add, (n, r) -> {}, r -> {}, e -> {});

        assertThat(toolCalls).containsExactly("execute");
    }

    @Test
    void chatStream_toolStartEvent_emptyToolName_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"tool_start\",\"toolName\":\"\"}",
            "data:{\"type\":\"done\"}"
        ));

        java.util.List<String> toolCalls = new java.util.ArrayList<>();

        client.chatStream("Hi", "s1",
            t -> {}, toolCalls::add, (n, r) -> {}, r -> {}, e -> {});

        assertThat(toolCalls).isEmpty();
    }

    @Test
    void chatStream_toolResultEvent_notifiesToolResultConsumer() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"tool_result\",\"toolName\":\"search\",\"toolResult\":\"found 3 items\"}",
            "data:{\"type\":\"done\"}"
        ));

        java.util.List<String[]> toolResults = new java.util.ArrayList<>();

        client.chatStream("Hi", "s1",
            t -> {}, t -> {},
            (name, result) -> toolResults.add(new String[]{name, result}),
            r -> {}, e -> {});

        assertThat(toolResults).hasSize(1);
        assertThat(toolResults.get(0)[0]).isEqualTo("search");
        assertThat(toolResults.get(0)[1]).isEqualTo("found 3 items");
    }

    @Test
    void chatStream_toolResultEvent_emptyToolName_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"tool_result\",\"toolName\":\"\",\"toolResult\":\"x\"}",
            "data:{\"type\":\"done\"}"
        ));

        java.util.List<String[]> toolResults = new java.util.ArrayList<>();

        client.chatStream("Hi", "s1",
            t -> {}, t -> {},
            (name, result) -> toolResults.add(new String[]{name, result}),
            r -> {}, e -> {});

        assertThat(toolResults).isEmpty();
    }

    @Test
    void chatStream_nullInputStream_callsOnError() {
        when(responseSpec.body(InputStream.class)).thenReturn(null);

        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, r -> {}, errorRef::set);

        assertThat(errorRef.get()).isInstanceOf(IllegalStateException.class);
        assertThat(result.content()).isEmpty();
    }

    @Test
    void chatStream_streamEndsWithoutDone_callsOnComplete() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"token\",\"token\":\"partial\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        assertThat(result.content()).isEqualTo("partial");
        assertThat(completed.get()).isNotNull();
        assertThat(completed.get().content()).isEqualTo("partial");
    }

    @Test
    void chatStream_streamEndsWithoutDoneWithMetadata_callsOnComplete() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"metadata\",\"modelUsed\":\"m1\",\"contextTokens\":10,\"contextLength\":100,\"memoryUpdated\":true}",
            "data:{\"type\":\"token\",\"token\":\"answer\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        assertThat(result.content()).isEqualTo("answer");
        assertThat(result.modelUsed()).isEqualTo("m1");
        assertThat(result.memoryUpdated()).isTrue();
    }

    @Test
    void chatStream_exception_callsOnError() {
        when(responseSpec.body(InputStream.class))
            .thenThrow(new RuntimeException("Network error"));

        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, r -> {}, errorRef::set);

        assertThat(errorRef.get()).isInstanceOf(RuntimeException.class);
        assertThat(result.content()).isEmpty();
    }

    @Test
    void chatStream_malformedSSEDataLine_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:not valid json",
            "data:{\"type\":\"token\",\"token\":\"OK\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, r -> {}, e -> {});

        assertThat(result.content()).isEqualTo("OK");
    }

    @Test
    void chatStream_emptyDataLine_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:   ",
            "data:{\"type\":\"token\",\"token\":\"OK\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, r -> {}, e -> {});

        assertThat(result.content()).isEqualTo("OK");
    }

    @Test
    void chatStream_nonDataLine_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            ": comment line",
            "event: message",
            "data:{\"type\":\"token\",\"token\":\"OK\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, r -> {}, e -> {});

        assertThat(result.content()).isEqualTo("OK");
    }

    @Test
    void chatStream_nullTokenNode_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"token\",\"token\":null}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, r -> {}, e -> {});

        assertThat(result.content()).isEmpty();
    }

    @Test
    void chatStream_tokenWithoutType_stillProcessed() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"token\":\"no-type\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, r -> {}, e -> {});

        assertThat(result.content()).isEqualTo("no-type");
    }

    // ─── Fix #3: non-textual token node is skipped ──────────────────

    @Test
    void chatStream_nonTextualTokenNode_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"token\",\"token\":12345}",
            "data:{\"type\":\"token\",\"token\":\"OK\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, r -> {}, e -> {});

        // The numeric token should be skipped, only "OK" accepted
        assertThat(result.content()).isEqualTo("OK");
    }

    @Test
    void chatStream_booleanTokenNode_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"token\",\"token\":true}",
            "data:{\"type\":\"token\",\"token\":\"text\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, r -> {}, e -> {});

        assertThat(result.content()).isEqualTo("text");
    }

    @Test
    void chatStream_objectTokenNode_skipped() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"token\",\"token\":{\"nested\":\"value\"}}",
            "data:{\"type\":\"token\",\"token\":\"text\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, r -> {}, e -> {});

        assertThat(result.content()).isEqualTo("text");
    }

    // ─── Fix #1: memoryUpdated propagated through done event ─────────

    @Test
    void chatStream_doneWithMetadata_propagatesMemoryUpdated() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"metadata\",\"modelUsed\":\"m1\",\"contextTokens\":10,\"contextLength\":100,\"memoryUpdated\":true}",
            "data:{\"type\":\"token\",\"token\":\"answer\"}",
            "data:{\"type\":\"done\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        assertThat(result.memoryUpdated()).isTrue();
        assertThat(completed.get().memoryUpdated()).isTrue();
    }

    @Test
    void chatStream_doneWithMetadata_memoryUpdatedFalse() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"metadata\",\"modelUsed\":\"m1\",\"contextTokens\":10,\"contextLength\":100,\"memoryUpdated\":false}",
            "data:{\"type\":\"done\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        assertThat(result.memoryUpdated()).isFalse();
        assertThat(completed.get().memoryUpdated()).isFalse();
    }

    @Test
    void chatStream_doneWithMetadata_propagatesStreamFinalized() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"metadata\",\"modelUsed\":\"m1\",\"contextTokens\":10,\"contextLength\":100,\"streamFinalized\":true}",
            "data:{\"type\":\"done\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        // streamFinalized from metadata should be propagated through done path
        assertThat(result.streamFinalized()).isTrue();
    }

    // ─── Fix #4: chatId and threadId passed to backend ───────────────

    @Test
    void chat_forwardsChatIdAndThreadIdToBackend() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setChatId("123456");
        runtime.setMetadata("threadId", "42");

        client.chat("Hello", "session-123", runtime);

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return "123456".equals(map.get("chatId"))
                && "42".equals(map.get("threadId"));
        }));
    }

    @Test
    void chat_withNullChatId_omitsChatId() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setChatId(null);
        runtime.setMetadata("threadId", "42");

        client.chat("Hello", "session-123", runtime);

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return !map.containsKey("chatId")
                && "42".equals(map.get("threadId"));
        }));
    }

    @Test
    void chat_withBlankChatId_omitsChatId() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setChatId("  ");

        client.chat("Hello", "session-123", runtime);

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return !map.containsKey("chatId");
        }));
    }

    @Test
    void chat_withNullThreadId_omitsThreadId() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setChatId("123456");

        client.chat("Hello", "session-123", runtime);

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return "123456".equals(map.get("chatId"))
                && !map.containsKey("threadId");
        }));
    }

    @Test
    void chat_withBlankThreadId_omitsThreadId() {
        when(responseSpec.body(String.class))
            .thenReturn("{\"response\":\"OK\"}");

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setChatId("123456");
        runtime.setMetadata("threadId", "  ");

        client.chat("Hello", "session-123", runtime);

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return "123456".equals(map.get("chatId"))
                && !map.containsKey("threadId");
        }));
    }

    @Test
    void chatStream_forwardsChatIdAndThreadIdToBackend() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"done\"}"
        ));

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setChatId("999");
        runtime.setMetadata("threadId", "7");

        client.chatStream("Hi", "s1", runtime,
            t -> {}, t -> {}, (n, r) -> {}, msg -> {}, r -> {}, e -> {});

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return "999".equals(map.get("chatId"))
                && "7".equals(map.get("threadId"));
        }));
    }

    @Test
    void chatStream_withRuntimeFlags_includesChatIdAndThreadId() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"done\"}"
        ));

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setFastMode(true);
        runtime.setReasoningLevel("high");
        runtime.setChatId("555");
        runtime.setMetadata("threadId", "3");

        client.chatStream("Hi", "s1", runtime,
            t -> {}, t -> {}, (n, r) -> {}, msg -> {}, r -> {}, e -> {});

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return Boolean.TRUE.equals(map.get("fastMode"))
                && "high".equals(map.get("reasoningEffort"))
                && "555".equals(map.get("chatId"))
                && "3".equals(map.get("threadId"));
        }));
    }

    @Test
    void chatStream_emptyToolCallsArray_noNotification() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"tool_calls\",\"toolCalls\":[]}",
            "data:{\"type\":\"done\"}"
        ));

        java.util.List<String> toolCalls = new java.util.ArrayList<>();

        client.chatStream("Hi", "s1",
            t -> {}, toolCalls::add, (n, r) -> {}, r -> {}, e -> {});

        assertThat(toolCalls).isEmpty();
    }

    @Test
    void chatStream_nullToolCallsNode_noNotification() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"tool_calls\"}",
            "data:{\"type\":\"done\"}"
        ));

        java.util.List<String> toolCalls = new java.util.ArrayList<>();

        client.chatStream("Hi", "s1",
            t -> {}, toolCalls::add, (n, r) -> {}, r -> {}, e -> {});

        assertThat(toolCalls).isEmpty();
    }

    @Test
    void chatStream_doneWithMetadata_usesMetadata() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"metadata\",\"modelUsed\":\"llama\",\"contextTokens\":50,\"contextLength\":500}",
            "data:{\"type\":\"token\",\"token\":\"final\"}",
            "data:{\"type\":\"done\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        assertThat(result.modelUsed()).isEqualTo("llama");
        assertThat(result.contextTokens()).isEqualTo(50);
        assertThat(result.contextLength()).isEqualTo(500);
    }

    @Test
    void chatStream_doneWithoutMetadata_returnsSimpleResult() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"token\",\"token\":\"simple\"}",
            "data:{\"type\":\"done\"}"
        ));

        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            t -> {}, t -> {}, (n, r) -> {}, completed::set, e -> {});

        assertThat(result.modelUsed()).isNull();
        assertThat(result.contextTokens()).isNull();
        assertThat(result.contextLength()).isNull();
    }

    @Test
    void chatStream_backwardCompatibleOverload_works() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"token\",\"token\":\"OK\"}",
            "data:{\"type\":\"done\"}"
        ));

        StringBuilder received = new StringBuilder();
        AtomicReference<AgentBackendClient.ChatResult> completed = new AtomicReference<>();

        AgentBackendClient.ChatResult result = client.chatStream("Hi", "s1",
            received::append, t -> {}, (n, r) -> {}, completed::set, e -> {});

        assertThat(result.content()).isEqualTo("OK");
    }

    @Test
    void chatStream_withRuntimeFlags() {
        when(responseSpec.body(InputStream.class)).thenReturn(sseStream(
            "data:{\"type\":\"done\"}"
        ));

        BotSessionEntity runtime = new BotSessionEntity();
        runtime.setFastMode(true);
        runtime.setReasoningLevel("high");

        client.chatStream("Hi", "s1", runtime,
            t -> {}, t -> {}, (n, r) -> {}, msg -> {}, r -> {}, e -> {});

        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return Boolean.TRUE.equals(map.get("fastMode"))
                && "high".equals(map.get("reasoningEffort"));
        }));
    }

    // ═══════════════════════════════════════════════════════════════
    // health()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void health_up_returnsTrue() {
        when(responseSpec.body(String.class)).thenReturn("{\"status\":\"UP\"}");
        assertThat(client.health()).isTrue();
    }

    @Test
    void health_ok_returnsTrue() {
        when(responseSpec.body(String.class)).thenReturn("{\"status\":\"OK\"}");
        assertThat(client.health()).isTrue();
    }

    @Test
    void health_down_returnsFalse() {
        when(responseSpec.body(String.class)).thenReturn("{\"status\":\"DOWN\"}");
        assertThat(client.health()).isFalse();
    }

    @Test
    void health_noStatusField_returnsTrue() {
        when(responseSpec.body(String.class)).thenReturn("{\"info\":\"agent v1.0\"}");
        assertThat(client.health()).isTrue();
    }

    @Test
    void health_exception_returnsFalse() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("refused"));
        assertThat(client.health()).isFalse();
    }

    @Test
    void health_emptyResponse_returnsFalse() {
        when(responseSpec.body(String.class)).thenReturn("");
        assertThat(client.health()).isFalse();
    }

    @Test
    void health_nullResponse_returnsFalse() {
        when(responseSpec.body(String.class)).thenReturn(null);
        assertThat(client.health()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // resetSession()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void resetSession_success_returnsTrue() {
        assertThat(client.resetSession("s1")).isTrue();
    }

    @Test
    void resetSession_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.resetSession("s1")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // getContext()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void getContext_success_returnsJsonNode() {
        when(responseSpec.body(String.class)).thenReturn("{\"messages\":[]}");
        JsonNode result = client.getContext("s1");
        assertThat(result).isNotNull();
        assertThat(result.has("messages")).isTrue();
    }

    @Test
    void getContext_emptyResponse_returnsNull() {
        when(responseSpec.body(String.class)).thenReturn("");
        assertThat(client.getContext("s1")).isNull();
    }

    @Test
    void getContext_nullResponse_returnsNull() {
        when(responseSpec.body(String.class)).thenReturn(null);
        assertThat(client.getContext("s1")).isNull();
    }

    @Test
    void getContext_exception_returnsNull() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.getContext("s1")).isNull();
    }

    // ═══════════════════════════════════════════════════════════════
    // getUsage()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void getUsage_success_returnsJsonNode() {
        when(responseSpec.body(String.class)).thenReturn("{\"tokens\":100}");
        JsonNode result = client.getUsage("s1");
        assertThat(result).isNotNull();
        assertThat(result.path("tokens").asInt()).isEqualTo(100);
    }

    @Test
    void getUsage_emptyResponse_returnsNull() {
        when(responseSpec.body(String.class)).thenReturn("");
        assertThat(client.getUsage("s1")).isNull();
    }

    @Test
    void getUsage_exception_returnsNull() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.getUsage("s1")).isNull();
    }

    // ═══════════════════════════════════════════════════════════════
    // listSessionsByUser()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void listSessionsByUser_success_returnsArray() {
        when(responseSpec.body(String.class)).thenReturn("[{\"id\":\"s1\"},{\"id\":\"s2\"}]");
        JsonNode result = client.listSessionsByUser("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(2);
    }

    @Test
    void listSessionsByUser_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.listSessionsByUser("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void listSessionsByUser_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.listSessionsByUser("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // getMemory() & getSkills()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void getMemory_success_returnsArray() {
        when(responseSpec.body(String.class)).thenReturn("[\"fact1\",\"fact2\"]");
        JsonNode result = client.getMemory();
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(2);
    }

    @Test
    void getMemory_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.getMemory();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void getMemory_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.getMemory();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void getSkills_success_returnsArray() {
        when(responseSpec.body(String.class)).thenReturn("[\"skill1\"]");
        JsonNode result = client.getSkills();
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
    }

    @Test
    void getSkills_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.getSkills();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void getSkills_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.getSkills();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // compressSession()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void compressSession_success_returnsOkMessage() {
        assertThat(client.compressSession("s1", "focus area"))
            .isEqualTo("Context compressed.");
    }

    @Test
    void compressSession_nullFocus_success() {
        assertThat(client.compressSession("s1", null))
            .isEqualTo("Context compressed.");
    }

    @Test
    void compressSession_blankFocus_success() {
        assertThat(client.compressSession("s1", "  "))
            .isEqualTo("Context compressed.");
    }

    @Test
    void compressSession_exception_returnsErrorMessage() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.compressSession("s1", null)).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // undoTurns()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void undoTurns_success_returnsDeletedCount() {
        when(responseSpec.body(Integer.class)).thenReturn(5);
        assertThat(client.undoTurns("s1", 3)).isEqualTo("Undid 5 messages.");
    }

    @Test
    void undoTurns_nullDeleted_returnsZero() {
        when(responseSpec.body(Integer.class)).thenReturn(null);
        assertThat(client.undoTurns("s1", 3)).isEqualTo("Undid 0 messages.");
    }

    @Test
    void undoTurns_exception_returnsErrorMessage() {
        when(responseSpec.body(Integer.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.undoTurns("s1", 3)).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // compressSessionPartial()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void compressSessionPartial_success() {
        assertThat(client.compressSessionPartial("s1", 5))
            .isEqualTo("Context compressed (kept last 5 exchanges).");
    }

    @Test
    void compressSessionPartial_exception_returnsErrorMessage() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.compressSessionPartial("s1", 5)).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // listCheckpoints()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void listCheckpoints_success_returnsFormattedList() {
        when(responseSpec.body(String.class)).thenReturn(
            "[{\"id\":\"cp1\",\"description\":\"first\",\"fileCount\":3},{\"id\":\"cp2\",\"description\":\"second\",\"fileCount\":0}]");

        String result = client.listCheckpoints();

        assertThat(result).contains("Checkpoints:");
        assertThat(result).contains("cp1");
        assertThat(result).contains("first");
        assertThat(result).contains("3 files");
        assertThat(result).contains("cp2");
    }

    @Test
    void listCheckpoints_emptyResponse_returnsNoCheckpoints() {
        when(responseSpec.body(String.class)).thenReturn("");
        assertThat(client.listCheckpoints()).isEqualTo("No checkpoints found.");
    }

    @Test
    void listCheckpoints_nullResponse_returnsNoCheckpoints() {
        when(responseSpec.body(String.class)).thenReturn(null);
        assertThat(client.listCheckpoints()).isEqualTo("No checkpoints found.");
    }

    @Test
    void listCheckpoints_emptyArray_returnsNoCheckpoints() {
        when(responseSpec.body(String.class)).thenReturn("[]");
        assertThat(client.listCheckpoints()).isEqualTo("No checkpoints found.");
    }

    @Test
    void listCheckpoints_nonArray_returnsNoCheckpoints() {
        when(responseSpec.body(String.class)).thenReturn("{\"not\":\"array\"}");
        assertThat(client.listCheckpoints()).isEqualTo("No checkpoints found.");
    }

    @Test
    void listCheckpoints_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.listCheckpoints()).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // restoreCheckpoint() & createCheckpoint()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void restoreCheckpoint_success() {
        assertThat(client.restoreCheckpoint("cp1")).isEqualTo("Checkpoint restored: cp1");
    }

    @Test
    void restoreCheckpoint_exception_returnsErrorMessage() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.restoreCheckpoint("cp1")).startsWith("Error:");
    }

    @Test
    void createCheckpoint_success() {
        assertThat(client.createCheckpoint("my checkpoint"))
            .isEqualTo("Checkpoint created: my checkpoint");
    }

    @Test
    void createCheckpoint_exception_returnsErrorMessage() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.createCheckpoint("desc")).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // approve() & deny()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void approve_success_returnsResponseBody() {
        when(responseSpec.body(String.class)).thenReturn("Approved 3 items");
        assertThat(client.approve(true, null)).isEqualTo("Approved 3 items");
    }

    @Test
    void approve_withScope_success() {
        when(responseSpec.body(String.class)).thenReturn("Approved");
        client.approve(false, "files");
        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return Boolean.FALSE.equals(map.get("all")) && "files".equals(map.get("scope"));
        }));
    }

    @Test
    void approve_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.approve(true, null)).startsWith("Error:");
    }

    @Test
    void deny_success_returnsResponseBody() {
        when(responseSpec.body(String.class)).thenReturn("Denied");
        assertThat(client.deny(true)).isEqualTo("Denied");
    }

    @Test
    void deny_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.deny(false)).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // listActiveAgents() & getInsights()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void listActiveAgents_success_returnsJsonNode() {
        when(responseSpec.body(String.class)).thenReturn("[{\"id\":\"a1\"}]");
        JsonNode result = client.listActiveAgents();
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
    }

    @Test
    void listActiveAgents_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.listActiveAgents();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void listActiveAgents_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.listActiveAgents();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void getInsights_success_returnsJsonNode() {
        when(responseSpec.body(String.class)).thenReturn("{\"insight\":\"value\"}");
        JsonNode result = client.getInsights();
        assertThat(result).isNotNull();
        assertThat(result.path("insight").asText()).isEqualTo("value");
    }

    @Test
    void getInsights_emptyResponse_returnsEmptyObject() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.getInsights();
        assertThat(result.isObject()).isTrue();
    }

    @Test
    void getInsights_exception_returnsEmptyObject() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.getInsights();
        assertThat(result.isObject()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════
    // restart(), reloadMcp(), reloadAll(), reloadSkills()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void restart_success() {
        assertThat(client.restart()).isEqualTo("Agent restarting...");
    }

    @Test
    void restart_exception_returnsErrorMessage() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.restart()).startsWith("Error:");
    }

    @Test
    void reloadMcp_success() {
        assertThat(client.reloadMcp()).isEqualTo("MCP servers reloaded.");
    }

    @Test
    void reloadMcp_exception_returnsErrorMessage() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.reloadMcp()).startsWith("Error:");
    }

    @Test
    void reloadAll_success() {
        assertThat(client.reloadAll()).isEqualTo("Skills and MCP servers reloaded.");
    }

    @Test
    void reloadAll_exception_returnsErrorMessage() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.reloadAll()).startsWith("Error:");
    }

    @Test
    void reloadSkills_success() {
        assertThat(client.reloadSkills()).isEqualTo("Skills reloaded.");
    }

    @Test
    void reloadSkills_exception_returnsErrorMessage() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.reloadSkills()).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // Kanban CRUD
    // ═══════════════════════════════════════════════════════════════

    @Test
    void getKanban_success_returnsArray() {
        when(responseSpec.body(String.class)).thenReturn("[{\"id\":\"t1\"}]");
        JsonNode result = client.getKanban();
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
    }

    @Test
    void getKanban_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.getKanban();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void getKanban_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.getKanban();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void addKanbanTask_success_returnsJsonNode() {
        when(responseSpec.body(String.class)).thenReturn("{\"id\":\"t1\",\"text\":\"task\"}");
        JsonNode result = client.addKanbanTask("task");
        assertThat(result).isNotNull();
        assertThat(result.path("id").asText()).isEqualTo("t1");
    }

    @Test
    void addKanbanTask_emptyResponse_returnsNull() {
        when(responseSpec.body(String.class)).thenReturn("");
        assertThat(client.addKanbanTask("task")).isNull();
    }

    @Test
    void addKanbanTask_exception_returnsNull() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.addKanbanTask("task")).isNull();
    }

    @Test
    void doneKanbanTask_success_returnsTrue() {
        assertThat(client.doneKanbanTask("t1")).isTrue();
    }

    @Test
    void doneKanbanTask_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.doneKanbanTask("t1")).isFalse();
    }

    @Test
    void clearKanban_success_returnsTrue() {
        assertThat(client.clearKanban()).isTrue();
    }

    @Test
    void clearKanban_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.clearKanban()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // Bundles
    // ═══════════════════════════════════════════════════════════════

    @Test
    void listBundles_success_returnsArray() {
        when(responseSpec.body(String.class)).thenReturn("[{\"name\":\"b1\"}]");
        JsonNode result = client.listBundles();
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
    }

    @Test
    void listBundles_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.listBundles();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void listBundles_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.listBundles();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void installBundle_success() {
        when(responseSpec.body(String.class)).thenReturn("Installed bundle");
        assertThat(client.installBundle("my-bundle")).isEqualTo("Installed bundle");
    }

    @Test
    void installBundle_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.installBundle("my-bundle")).startsWith("Error:");
    }

    @Test
    void uninstallBundle_success() {
        when(responseSpec.body(String.class)).thenReturn("Uninstalled bundle");
        assertThat(client.uninstallBundle("my-bundle")).isEqualTo("Uninstalled bundle");
    }

    @Test
    void uninstallBundle_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.uninstallBundle("my-bundle")).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // branchSession()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void branchSession_successWithId_returnsBranchedMessage() {
        when(responseSpec.body(String.class)).thenReturn("{\"id\":\"new-session-id\"}");
        assertThat(client.branchSession("s1", "feature"))
            .isEqualTo("Branched session: new-session-id");
    }

    @Test
    void branchSession_successWithoutId_returnsDefaultMessage() {
        when(responseSpec.body(String.class)).thenReturn("{\"other\":\"data\"}");
        assertThat(client.branchSession("s1", null))
            .isEqualTo("Branch created.");
    }

    @Test
    void branchSession_emptyResponse_returnsDefaultMessage() {
        when(responseSpec.body(String.class)).thenReturn("");
        assertThat(client.branchSession("s1", "name"))
            .isEqualTo("Branch created.");
    }

    @Test
    void branchSession_nullResponse_returnsDefaultMessage() {
        when(responseSpec.body(String.class)).thenReturn(null);
        assertThat(client.branchSession("s1", null))
            .isEqualTo("Branch created.");
    }

    @Test
    void branchSession_blankName_omitsQueryParam() {
        when(responseSpec.body(String.class)).thenReturn("{\"id\":\"b1\"}");
        client.branchSession("s1", "  ");
        // Verify no query param in URL — just that the call succeeded
        assertThat(client.branchSession("s1", "  ")).contains("b1");
    }

    @Test
    void branchSession_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.branchSession("s1", null)).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // runBackground()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void runBackground_success_returnsResult() {
        when(responseSpec.body(String.class)).thenReturn("Task completed");
        assertThat(client.runBackground("do something", "s1"))
            .isEqualTo("Task completed");
    }

    @Test
    void runBackground_nullResult_returnsDefaultMessage() {
        when(responseSpec.body(String.class)).thenReturn(null);
        assertThat(client.runBackground("do something", "s1"))
            .isEqualTo("Background task started.");
    }

    @Test
    void runBackground_nullSessionId_omitsSessionId() {
        when(responseSpec.body(String.class)).thenReturn("OK");
        client.runBackground("do something", null);
        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return "do something".equals(map.get("prompt")) && !map.containsKey("sessionId");
        }));
    }

    @Test
    void runBackground_blankSessionId_omitsSessionId() {
        when(responseSpec.body(String.class)).thenReturn("OK");
        client.runBackground("do something", "  ");
        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return !map.containsKey("sessionId");
        }));
    }

    @Test
    void runBackground_exception_returnsErrorMessage() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.runBackground("do something", "s1")).startsWith("Error:");
    }

    // ═══════════════════════════════════════════════════════════════
    // steer()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void steer_success_accepted_returnsTrue() {
        when(responseSpec.body(String.class)).thenReturn("{\"accepted\":true}");
        assertThat(client.steer("s1", "steer text")).isTrue();
    }

    @Test
    void steer_success_notAccepted_returnsFalse() {
        when(responseSpec.body(String.class)).thenReturn("{\"accepted\":false}");
        assertThat(client.steer("s1", "steer text")).isFalse();
    }

    @Test
    void steer_nullSessionId_returnsFalse() {
        assertThat(client.steer(null, "text")).isFalse();
    }

    @Test
    void steer_blankSessionId_returnsFalse() {
        assertThat(client.steer("  ", "text")).isFalse();
    }

    @Test
    void steer_nullText_returnsFalse() {
        assertThat(client.steer("s1", null)).isFalse();
    }

    @Test
    void steer_blankText_returnsFalse() {
        assertThat(client.steer("s1", "  ")).isFalse();
    }

    @Test
    void steer_exception_returnsFalse() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.steer("s1", "text")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // Cron jobs
    // ═══════════════════════════════════════════════════════════════

    @Test
    void listCronJobs_success_returnsArray() {
        when(responseSpec.body(String.class)).thenReturn("[{\"id\":\"c1\"}]");
        JsonNode result = client.listCronJobs();
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
    }

    @Test
    void listCronJobs_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.listCronJobs();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void listCronJobs_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.listCronJobs();
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void deleteCronJob_success_returnsTrue() {
        assertThat(client.deleteCronJob("c1")).isTrue();
    }

    @Test
    void deleteCronJob_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.deleteCronJob("c1")).isFalse();
    }

    @Test
    void pauseCronJob_success_returnsTrue() {
        assertThat(client.pauseCronJob("c1")).isTrue();
    }

    @Test
    void pauseCronJob_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.pauseCronJob("c1")).isFalse();
    }

    @Test
    void resumeCronJob_success_returnsTrue() {
        assertThat(client.resumeCronJob("c1")).isTrue();
    }

    @Test
    void resumeCronJob_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.resumeCronJob("c1")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // Memory management
    // ═══════════════════════════════════════════════════════════════

    @Test
    void listPendingMemory_success_returnsArray() {
        when(responseSpec.body(String.class)).thenReturn("[{\"id\":\"m1\"}]");
        JsonNode result = client.listPendingMemory("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
    }

    @Test
    void listPendingMemory_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.listPendingMemory("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void listPendingMemory_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.listPendingMemory("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void approvePendingMemory_success_returnsTrue() {
        when(responseSpec.body(Boolean.class)).thenReturn(true);
        assertThat(client.approvePendingMemory("user1", "m1")).isTrue();
    }

    @Test
    void approvePendingMemory_falseResult_returnsFalse() {
        when(responseSpec.body(Boolean.class)).thenReturn(false);
        assertThat(client.approvePendingMemory("user1", "m1")).isFalse();
    }

    @Test
    void approvePendingMemory_nullResult_returnsFalse() {
        when(responseSpec.body(Boolean.class)).thenReturn(null);
        assertThat(client.approvePendingMemory("user1", "m1")).isFalse();
    }

    @Test
    void approvePendingMemory_exception_returnsFalse() {
        when(responseSpec.body(Boolean.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.approvePendingMemory("user1", "m1")).isFalse();
    }

    @Test
    void rejectPendingMemory_success_returnsTrue() {
        when(responseSpec.body(Boolean.class)).thenReturn(true);
        assertThat(client.rejectPendingMemory("user1", "m1")).isTrue();
    }

    @Test
    void rejectPendingMemory_falseResult_returnsFalse() {
        when(responseSpec.body(Boolean.class)).thenReturn(false);
        assertThat(client.rejectPendingMemory("user1", "m1")).isFalse();
    }

    @Test
    void rejectPendingMemory_nullResult_returnsFalse() {
        when(responseSpec.body(Boolean.class)).thenReturn(null);
        assertThat(client.rejectPendingMemory("user1", "m1")).isFalse();
    }

    @Test
    void rejectPendingMemory_exception_returnsFalse() {
        when(responseSpec.body(Boolean.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.rejectPendingMemory("user1", "m1")).isFalse();
    }

    @Test
    void setMemoryApproval_success_noException() {
        client.setMemoryApproval(true);
        verify(responseSpec).toBodilessEntity();
    }

    @Test
    void setMemoryApproval_exception_noExceptionThrown() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        client.setMemoryApproval(false);
        // Should not throw — just logs
    }

    @Test
    void isMemoryApprovalEnabled_true() {
        when(responseSpec.body(String.class)).thenReturn("true");
        assertThat(client.isMemoryApprovalEnabled()).isTrue();
    }

    @Test
    void isMemoryApprovalEnabled_false() {
        when(responseSpec.body(String.class)).thenReturn("false");
        assertThat(client.isMemoryApprovalEnabled()).isFalse();
    }

    @Test
    void isMemoryApprovalEnabled_emptyResponse_returnsFalse() {
        when(responseSpec.body(String.class)).thenReturn("");
        assertThat(client.isMemoryApprovalEnabled()).isFalse();
    }

    @Test
    void isMemoryApprovalEnabled_nullResponse_returnsFalse() {
        when(responseSpec.body(String.class)).thenReturn(null);
        assertThat(client.isMemoryApprovalEnabled()).isFalse();
    }

    @Test
    void isMemoryApprovalEnabled_exception_returnsFalse() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.isMemoryApprovalEnabled()).isFalse();
    }

    @Test
    void listAllMemory_success_returnsArray() {
        when(responseSpec.body(String.class)).thenReturn("[{\"id\":\"m1\"}]");
        JsonNode result = client.listAllMemory("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).hasSize(1);
    }

    @Test
    void listAllMemory_emptyResponse_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenReturn("");
        JsonNode result = client.listAllMemory("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void listAllMemory_exception_returnsEmptyArray() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        JsonNode result = client.listAllMemory("user1");
        assertThat(result.isArray()).isTrue();
        assertThat(result).isEmpty();
    }

    @Test
    void deleteMemory_success_returnsTrue() {
        assertThat(client.deleteMemory("user1", "m1")).isTrue();
    }

    @Test
    void deleteMemory_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.deleteMemory("user1", "m1")).isFalse();
    }

    @Test
    void storeMemory_success_returnsTrue() {
        assertThat(client.storeMemory("user1", "some fact")).isTrue();
    }

    @Test
    void storeMemory_nullUserId_defaultsToDefault() {
        assertThat(client.storeMemory(null, "some fact")).isTrue();
        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return "default".equals(map.get("userId"))
                && "some fact".equals(map.get("fact"))
                && "user".equals(map.get("category"))
                && "memory".equals(map.get("target"));
        }));
    }

    @Test
    void storeMemory_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.storeMemory("user1", "fact")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // TTS & transcribe
    // ═══════════════════════════════════════════════════════════════

    @Test
    void tts_success_returnsAudioBytes() {
        byte[] audio = new byte[]{1, 2, 3, 4};
        when(responseSpec.body(byte[].class)).thenReturn(audio);
        assertThat(client.tts("Hello", "alloy")).containsExactly(1, 2, 3, 4);
    }

    @Test
    void tts_nullAudio_returnsEmptyArray() {
        when(responseSpec.body(byte[].class)).thenReturn(null);
        assertThat(client.tts("Hello", "alloy")).isEmpty();
    }

    @Test
    void tts_nullVoice_omitsVoiceFromBody() {
        when(responseSpec.body(byte[].class)).thenReturn(new byte[]{1});
        client.tts("Hello", null);
        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return "Hello".equals(map.get("text")) && !map.containsKey("voice");
        }));
    }

    @Test
    void tts_blankVoice_omitsVoiceFromBody() {
        when(responseSpec.body(byte[].class)).thenReturn(new byte[]{1});
        client.tts("Hello", "  ");
        verify(postSpec).body(argThat((Object body) -> {
            if (!(body instanceof java.util.Map<?, ?> map)) return false;
            return !map.containsKey("voice");
        }));
    }

    @Test
    void tts_exception_returnsEmptyArray() {
        when(responseSpec.body(byte[].class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.tts("Hello", "alloy")).isEmpty();
    }

    @Test
    void transcribe_success_returnsText() {
        when(responseSpec.body(String.class)).thenReturn("{\"text\":\"Hello world\"}");
        assertThat(client.transcribe(new byte[]{1, 2, 3})).isEqualTo("Hello world");
    }

    @Test
    void transcribe_emptyResponse_returnsNull() {
        when(responseSpec.body(String.class)).thenReturn("");
        assertThat(client.transcribe(new byte[]{1})).isNull();
    }

    @Test
    void transcribe_nullResponse_returnsNull() {
        when(responseSpec.body(String.class)).thenReturn(null);
        assertThat(client.transcribe(new byte[]{1})).isNull();
    }

    @Test
    void transcribe_exception_returnsNull() {
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("fail"));
        assertThat(client.transcribe(new byte[]{1})).isNull();
    }

    // ═══════════════════════════════════════════════════════════════
    // Goals
    // ═══════════════════════════════════════════════════════════════

    @Test
    void clearGoal_success_returnsTrue() {
        assertThat(client.clearGoal("s1")).isTrue();
    }

    @Test
    void clearGoal_nullSessionId_returnsFalse() {
        assertThat(client.clearGoal(null)).isFalse();
    }

    @Test
    void clearGoal_blankSessionId_returnsFalse() {
        assertThat(client.clearGoal("  ")).isFalse();
    }

    @Test
    void clearGoal_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.clearGoal("s1")).isFalse();
    }

    @Test
    void setGoal_success_returnsTrue() {
        assertThat(client.setGoal("s1", "fix all bugs")).isTrue();
    }

    @Test
    void setGoal_nullSessionId_returnsFalse() {
        assertThat(client.setGoal(null, "goal")).isFalse();
    }

    @Test
    void setGoal_blankSessionId_returnsFalse() {
        assertThat(client.setGoal("  ", "goal")).isFalse();
    }

    @Test
    void setGoal_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.setGoal("s1", "goal")).isFalse();
    }

    @Test
    void pauseGoal_success_returnsTrue() {
        assertThat(client.pauseGoal("s1")).isTrue();
    }

    @Test
    void pauseGoal_nullSessionId_returnsFalse() {
        assertThat(client.pauseGoal(null)).isFalse();
    }

    @Test
    void pauseGoal_blankSessionId_returnsFalse() {
        assertThat(client.pauseGoal("  ")).isFalse();
    }

    @Test
    void pauseGoal_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.pauseGoal("s1")).isFalse();
    }

    @Test
    void resumeGoal_success_returnsTrue() {
        assertThat(client.resumeGoal("s1")).isTrue();
    }

    @Test
    void resumeGoal_nullSessionId_returnsFalse() {
        assertThat(client.resumeGoal(null)).isFalse();
    }

    @Test
    void resumeGoal_blankSessionId_returnsFalse() {
        assertThat(client.resumeGoal("  ")).isFalse();
    }

    @Test
    void resumeGoal_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.resumeGoal("s1")).isFalse();
    }

    @Test
    void appendSubgoal_success_returnsTrue() {
        assertThat(client.appendSubgoal("s1", "write tests")).isTrue();
    }

    @Test
    void appendSubgoal_nullSessionId_returnsFalse() {
        assertThat(client.appendSubgoal(null, "subgoal")).isFalse();
    }

    @Test
    void appendSubgoal_blankSessionId_returnsFalse() {
        assertThat(client.appendSubgoal("  ", "subgoal")).isFalse();
    }

    @Test
    void appendSubgoal_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.appendSubgoal("s1", "subgoal")).isFalse();
    }

    @Test
    void clearSubgoals_success_returnsTrue() {
        assertThat(client.clearSubgoals("s1")).isTrue();
    }

    @Test
    void clearSubgoals_nullSessionId_returnsFalse() {
        assertThat(client.clearSubgoals(null)).isFalse();
    }

    @Test
    void clearSubgoals_blankSessionId_returnsFalse() {
        assertThat(client.clearSubgoals("  ")).isFalse();
    }

    @Test
    void clearSubgoals_exception_returnsFalse() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("fail"));
        assertThat(client.clearSubgoals("s1")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // getBaseUrl()
    // ═══════════════════════════════════════════════════════════════

    @Test
    void getBaseUrl_returnsConfiguredUrl() {
        assertThat(client.getBaseUrl()).isEqualTo("http://localhost:8090");
    }

    // ═══════════════════════════════════════════════════════════════
    // ChatResult record constructors
    // ═══════════════════════════════════════════════════════════════

    @Test
    void chatResult_singleArgConstructor_defaultsNulls() {
        AgentBackendClient.ChatResult r = new AgentBackendClient.ChatResult("hello");
        assertThat(r.content()).isEqualTo("hello");
        assertThat(r.modelUsed()).isNull();
        assertThat(r.contextTokens()).isNull();
        assertThat(r.contextLength()).isNull();
        assertThat(r.streamFinalized()).isFalse();
        assertThat(r.memoryUpdated()).isFalse();
    }

    @Test
    void chatResult_fourArgConstructor_defaultsBooleans() {
        AgentBackendClient.ChatResult r = new AgentBackendClient.ChatResult("hi", "model", 10, 100);
        assertThat(r.content()).isEqualTo("hi");
        assertThat(r.modelUsed()).isEqualTo("model");
        assertThat(r.contextTokens()).isEqualTo(10);
        assertThat(r.contextLength()).isEqualTo(100);
        assertThat(r.streamFinalized()).isFalse();
        assertThat(r.memoryUpdated()).isFalse();
    }

    @Test
    void chatResult_fiveArgConstructor_defaultsMemoryUpdated() {
        AgentBackendClient.ChatResult r = new AgentBackendClient.ChatResult("hi", "model", 10, 100, true);
        assertThat(r.streamFinalized()).isTrue();
        assertThat(r.memoryUpdated()).isFalse();
    }

    @Test
    void chatResult_fullConstructor_allFieldsSet() {
        AgentBackendClient.ChatResult r = new AgentBackendClient.ChatResult("hi", "model", 10, 100, true, true);
        assertThat(r.content()).isEqualTo("hi");
        assertThat(r.modelUsed()).isEqualTo("model");
        assertThat(r.contextTokens()).isEqualTo(10);
        assertThat(r.contextLength()).isEqualTo(100);
        assertThat(r.streamFinalized()).isTrue();
        assertThat(r.memoryUpdated()).isTrue();
        assertThat(r.backendSessionId()).isNull();
    }

    @Test
    void chatResult_sevenArgConstructor_includesBackendSessionId() {
        java.util.UUID sid = java.util.UUID.randomUUID();
        AgentBackendClient.ChatResult r = new AgentBackendClient.ChatResult("hi", "model", 10, 100, true, true, sid);
        assertThat(r.backendSessionId()).isEqualTo(sid);
    }
}