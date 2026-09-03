package com.azhukov.agent.api.dto;

import com.azhukov.agent.core.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for all API DTO records: construction, accessor methods,
 * equals/hashCode, toString, and Jackson JSON round-trip serialization.
 * <p>
 * Nested records from OpenAiChatRequest, OpenAiChatResponse, and OpenAiStreamChunk
 * share simple names (Choice, Function, ToolCall, etc.), so fully-qualified
 * names are used throughout to avoid ambiguity.
 */
class ApiDtoObjectsTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUpMapper() {
        mapper = new ObjectMapper();
    }

    // ──────────────────────────────────────────────
    // ChatRequest
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("ChatRequest")
    class ChatRequestTest {

        @Test
        void constructionAndAccessors() {
            UUID sessionId = UUID.randomUUID();
            ChatRequest req = ChatRequest.simple(sessionId, "hello", 2, 5000L);

            assertThat(req.sessionId()).isEqualTo(sessionId);
            assertThat(req.message()).isEqualTo("hello");
            assertThat(req.delegationDepth()).isEqualTo(2);
            assertThat(req.timeoutMs()).isEqualTo(5000L);
        }

        @Test
        void nullablesAreAllowed() {
            ChatRequest req = ChatRequest.simple(null, "msg", null, null);
            assertThat(req.sessionId()).isNull();
            assertThat(req.delegationDepth()).isNull();
            assertThat(req.timeoutMs()).isNull();
        }

        @Test
        void equalsHashCodeAndToString() {
            UUID sid = UUID.randomUUID();
            ChatRequest a = ChatRequest.simple(sid, "msg", 1, 100L);
            ChatRequest b = ChatRequest.simple(sid, "msg", 1, 100L);
            ChatRequest c = ChatRequest.simple(sid, "diff", 1, 100L);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a.toString()).contains("msg");
        }

        @Test
        void jsonRoundTrip() throws Exception {
            UUID sid = UUID.randomUUID();
            ChatRequest original = ChatRequest.simple(sid, "hello world", 3, 10000L);

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"message\":\"hello world\"");

            ChatRequest deserialized = mapper.readValue(json, ChatRequest.class);
            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        void jsonRoundTripWithNulls() throws Exception {
            ChatRequest original = ChatRequest.simple(null, "msg", null, null);
            ChatRequest deserialized = mapper.readValue(
                    mapper.writeValueAsString(original), ChatRequest.class);
            assertThat(deserialized).isEqualTo(original);
        }
    }

    // ──────────────────────────────────────────────
    // ChatResponseDto
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("ChatResponseDto")
    class ChatResponseDtoTest {

        @Test
        void constructionAndAccessors() {
            UUID sid = UUID.randomUUID();
            ChatResponseDto dto = new ChatResponseDto(sid, "response text",
                    List.of("toolA", "toolB"), true);

            assertThat(dto.sessionId()).isEqualTo(sid);
            assertThat(dto.content()).isEqualTo("response text");
            assertThat(dto.toolCalls()).containsExactly("toolA", "toolB");
            assertThat(dto.completed()).isTrue();
        }

        @Test
        void emptyToolCallsList() {
            ChatResponseDto dto = new ChatResponseDto(null, "text", List.of(), false);
            assertThat(dto.toolCalls()).isEmpty();
            assertThat(dto.completed()).isFalse();
        }

        @Test
        void nullToolCallsList() {
            ChatResponseDto dto = new ChatResponseDto(null, "text", null, false);
            assertThat(dto.toolCalls()).isNull();
        }

        @Test
        void jsonRoundTrip() throws Exception {
            UUID sid = UUID.randomUUID();
            ChatResponseDto original = new ChatResponseDto(sid, "content",
                    List.of("read_file", "write_file"), true);

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"completed\":true");

            ChatResponseDto deserialized = mapper.readValue(json, ChatResponseDto.class);
            assertThat(deserialized).isEqualTo(original);
        }
    }

    // ──────────────────────────────────────────────
    // OpenAiChatRequest and nested records
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("OpenAiChatRequest")
    class OpenAiChatRequestTest {

        @Test
        void fullConstructionAndAccessors() {
            var function = new OpenAiChatRequest.OpenAiFunction("getWeather",
                    "Get weather info", Map.of("type", "object"));
            var tool = new OpenAiChatRequest.OpenAiTool("function", function);
            var funcCall = new OpenAiChatRequest.OpenAiFunctionCall("getWeather", "{\"city\":\"NYC\"}");
            var toolCall = new OpenAiChatRequest.OpenAiToolCall("call_123", "function", funcCall);
            var message = new OpenAiChatRequest.OpenAiMessage("user", "What's the weather?",
                    List.of(toolCall), null);
            OpenAiChatRequest request = new OpenAiChatRequest("gpt-4",
                    List.of(message), List.of(tool), 0.7, 4096, true);

            assertThat(request.model()).isEqualTo("gpt-4");
            assertThat(request.messages()).hasSize(1);
            assertThat(request.tools()).hasSize(1);
            assertThat(request.temperature()).isEqualTo(0.7);
            assertThat(request.maxTokens()).isEqualTo(4096);
            assertThat(request.stream()).isTrue();

            var msg = request.messages().get(0);
            assertThat(msg.role()).isEqualTo("user");
            assertThat(msg.content()).isEqualTo("What's the weather?");
            assertThat(msg.toolCallId()).isNull();
            assertThat(msg.toolCalls()).hasSize(1);
            assertThat(msg.toolCalls().get(0).id()).isEqualTo("call_123");
            assertThat(msg.toolCalls().get(0).function().name()).isEqualTo("getWeather");
            assertThat(msg.toolCalls().get(0).function().arguments()).isEqualTo("{\"city\":\"NYC\"}");

            var t = request.tools().get(0);
            assertThat(t.type()).isEqualTo("function");
            assertThat(t.function().name()).isEqualTo("getWeather");
            assertThat(t.function().description()).isEqualTo("Get weather info");
            assertThat(t.function().parameters()).containsEntry("type", "object");
        }

        @Test
        void nullOptionalsAreAllowed() {
            var msg = new OpenAiChatRequest.OpenAiMessage("assistant", "hi", null, null);
            OpenAiChatRequest req = new OpenAiChatRequest("gpt-4",
                    List.of(msg), null, null, null, null);

            assertThat(req.tools()).isNull();
            assertThat(req.temperature()).isNull();
            assertThat(req.maxTokens()).isNull();
            assertThat(req.stream()).isNull();
        }

        @Test
        void nestedRecordEqualsHashCode() {
            var f1 = new OpenAiChatRequest.OpenAiFunction("name", "desc", Map.of());
            var f2 = new OpenAiChatRequest.OpenAiFunction("name", "desc", Map.of());
            var f3 = new OpenAiChatRequest.OpenAiFunction("other", "desc", Map.of());

            assertThat(f1).isEqualTo(f2).hasSameHashCodeAs(f2);
            assertThat(f1).isNotEqualTo(f3);

            var tc1 = new OpenAiChatRequest.OpenAiToolCall("id1", "function",
                    new OpenAiChatRequest.OpenAiFunctionCall("fn", "{}"));
            var tc2 = new OpenAiChatRequest.OpenAiToolCall("id1", "function",
                    new OpenAiChatRequest.OpenAiFunctionCall("fn", "{}"));
            assertThat(tc1).isEqualTo(tc2).hasSameHashCodeAs(tc2);
        }

        @Test
        void jsonRoundTrip() throws Exception {
            var function = new OpenAiChatRequest.OpenAiFunction("search",
                    "Search the web", Map.of("q", "string"));
            var tool = new OpenAiChatRequest.OpenAiTool("function", function);
            var msg = new OpenAiChatRequest.OpenAiMessage("user", "search for cats",
                    null, null);
            OpenAiChatRequest original = new OpenAiChatRequest("gpt-4o",
                    List.of(msg), List.of(tool), 0.5, 2048, false);

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"model\":\"gpt-4o\"");
            assertThat(json).contains("\"stream\":false");

            OpenAiChatRequest deserialized = mapper.readValue(json, OpenAiChatRequest.class);
            assertThat(deserialized).isEqualTo(original);
            assertThat(deserialized.tools().get(0).function().parameters())
                    .containsEntry("q", "string");
        }

        @Test
        void jsonRoundTripMinimal() throws Exception {
            var msg = new OpenAiChatRequest.OpenAiMessage("system", "You are helpful",
                    null, null);
            OpenAiChatRequest original = new OpenAiChatRequest("gpt-4",
                    List.of(msg), null, null, null, null);

            String json = mapper.writeValueAsString(original);
            OpenAiChatRequest deserialized = mapper.readValue(json, OpenAiChatRequest.class);
            assertThat(deserialized).isEqualTo(original);
        }
    }

    // ──────────────────────────────────────────────
    // OpenAiChatResponse and nested records
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("OpenAiChatResponse")
    class OpenAiChatResponseTest {

        @Test
        void fullConstructionAndAccessors() {
            var function = new OpenAiChatResponse.Function("getWeather", "{\"city\":\"NYC\"}");
            var toolCall = new OpenAiChatResponse.ToolCall("call_456", "function", function);
            var message = new OpenAiChatResponse.Message("assistant", "The weather is sunny",
                    List.of(toolCall));
            var choice = new OpenAiChatResponse.Choice(0, message, "stop");
            var usage = new OpenAiChatResponse.Usage(50, 100, 150);
            OpenAiChatResponse response = new OpenAiChatResponse("chatcmpl-123",
                    "chat.completion", 1699999999L, "gpt-4",
                    List.of(choice), usage);

            assertThat(response.id()).isEqualTo("chatcmpl-123");
            assertThat(response.object()).isEqualTo("chat.completion");
            assertThat(response.created()).isEqualTo(1699999999L);
            assertThat(response.model()).isEqualTo("gpt-4");
            assertThat(response.choices()).hasSize(1);
            assertThat(response.usage()).isNotNull();

            var c = response.choices().get(0);
            assertThat(c.index()).isZero();
            assertThat(c.finishReason()).isEqualTo("stop");
            assertThat(c.message().role()).isEqualTo("assistant");
            assertThat(c.message().content()).isEqualTo("The weather is sunny");
            assertThat(c.message().toolCalls()).hasSize(1);

            var tc = c.message().toolCalls().get(0);
            assertThat(tc.id()).isEqualTo("call_456");
            assertThat(tc.type()).isEqualTo("function");
            assertThat(tc.function().name()).isEqualTo("getWeather");
            assertThat(tc.function().arguments()).isEqualTo("{\"city\":\"NYC\"}");

            var u = response.usage();
            assertThat(u.promptTokens()).isEqualTo(50);
            assertThat(u.completionTokens()).isEqualTo(100);
            assertThat(u.totalTokens()).isEqualTo(150);
        }

        @Test
        void usageRecordEqualsHashCode() {
            var u1 = new OpenAiChatResponse.Usage(1, 2, 3);
            var u2 = new OpenAiChatResponse.Usage(1, 2, 3);
            var u3 = new OpenAiChatResponse.Usage(1, 2, 4);

            assertThat(u1).isEqualTo(u2).hasSameHashCodeAs(u2);
            assertThat(u1).isNotEqualTo(u3);
        }

        @Test
        void jsonRoundTrip() throws Exception {
            var fn = new OpenAiChatResponse.Function("fn1", "{}");
            var tc = new OpenAiChatResponse.ToolCall("tc1", "function", fn);
            var msg = new OpenAiChatResponse.Message("assistant", "result", List.of(tc));
            var choice = new OpenAiChatResponse.Choice(0, msg, "tool_calls");
            var usage = new OpenAiChatResponse.Usage(10, 20, 30);
            OpenAiChatResponse original = new OpenAiChatResponse("resp-1",
                    "chat.completion", 1234567890L, "gpt-4o",
                    List.of(choice), usage);

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"id\":\"resp-1\"");
            assertThat(json).contains("\"finish_reason\":\"tool_calls\"");

            OpenAiChatResponse deserialized = mapper.readValue(json, OpenAiChatResponse.class);
            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        void jsonRoundTripWithNullToolCalls() throws Exception {
            var msg = new OpenAiChatResponse.Message("assistant", "text", null);
            var choice = new OpenAiChatResponse.Choice(0, msg, "stop");
            OpenAiChatResponse original = new OpenAiChatResponse("resp-2",
                    "chat.completion", 1L, "gpt-4", List.of(choice), null);

            String json = mapper.writeValueAsString(original);
            OpenAiChatResponse deserialized = mapper.readValue(json, OpenAiChatResponse.class);
            assertThat(deserialized).isEqualTo(original);
            assertThat(deserialized.usage()).isNull();
            assertThat(deserialized.choices().get(0).message().toolCalls()).isNull();
        }
    }

    // ──────────────────────────────────────────────
    // OpenAiStreamChunk and nested records
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("OpenAiStreamChunk")
    class OpenAiStreamChunkTest {

        @Test
        void fullConstructionAndAccessors() {
            var function = new OpenAiStreamChunk.Function("getWeather", "{\"city\":\"LA\"}");
            var toolCall = new OpenAiStreamChunk.ToolCall("call_789", "function", function);
            var delta = new OpenAiStreamChunk.Delta("assistant", "Hello", List.of(toolCall));
            var choice = new OpenAiStreamChunk.Choice(0, delta, "stop");
            OpenAiStreamChunk chunk = new OpenAiStreamChunk("chatcmpl-chunk-1",
                    "chat.completion.chunk", 1699999999L, "gpt-4",
                    List.of(choice),
            null);

            assertThat(chunk.id()).isEqualTo("chatcmpl-chunk-1");
            assertThat(chunk.object()).isEqualTo("chat.completion.chunk");
            assertThat(chunk.created()).isEqualTo(1699999999L);
            assertThat(chunk.model()).isEqualTo("gpt-4");
            assertThat(chunk.choices()).hasSize(1);

            var c = chunk.choices().get(0);
            assertThat(c.index()).isZero();
            assertThat(c.finishReason()).isEqualTo("stop");
            assertThat(c.delta().role()).isEqualTo("assistant");
            assertThat(c.delta().content()).isEqualTo("Hello");
            assertThat(c.delta().toolCalls()).hasSize(1);

            var tc = c.delta().toolCalls().get(0);
            assertThat(tc.id()).isEqualTo("call_789");
            assertThat(tc.type()).isEqualTo("function");
            assertThat(tc.function().name()).isEqualTo("getWeather");
            assertThat(tc.function().arguments()).isEqualTo("{\"city\":\"LA\"}");
        }

        @Test
        void deltaWithOnlyContent() {
            var delta = new OpenAiStreamChunk.Delta(null, "world", null);
            var choice = new OpenAiStreamChunk.Choice(0, delta, null);
            OpenAiStreamChunk chunk = new OpenAiStreamChunk("id",
                    "chat.completion.chunk", 1L, "gpt-4", List.of(choice),
            null);

            assertThat(chunk.choices().get(0).delta().role()).isNull();
            assertThat(chunk.choices().get(0).delta().content()).isEqualTo("world");
            assertThat(chunk.choices().get(0).delta().toolCalls()).isNull();
            assertThat(chunk.choices().get(0).finishReason()).isNull();
        }

        @Test
        void jsonRoundTrip() throws Exception {
            var fn = new OpenAiStreamChunk.Function("fn", "{}");
            var tc = new OpenAiStreamChunk.ToolCall("tc1", "function", fn);
            var delta = new OpenAiStreamChunk.Delta("assistant", "Hi", List.of(tc));
            var choice = new OpenAiStreamChunk.Choice(0, delta, "length");
            OpenAiStreamChunk original = new OpenAiStreamChunk("chunk-1",
                    "chat.completion.chunk", 999999L, "gpt-4o",
                    List.of(choice),
            null);

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"object\":\"chat.completion.chunk\"");

            OpenAiStreamChunk deserialized = mapper.readValue(json, OpenAiStreamChunk.class);
            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        void jsonRoundTripEmptyChoices() throws Exception {
            OpenAiStreamChunk original = new OpenAiStreamChunk("chunk-2",
                    "chat.completion.chunk", 1L, "gpt-4", List.of(),
            null);

            OpenAiStreamChunk deserialized = mapper.readValue(
                    mapper.writeValueAsString(original), OpenAiStreamChunk.class);
            assertThat(deserialized).isEqualTo(original);
            assertThat(deserialized.choices()).isEmpty();
        }

        @Test
        void nestedRecordsEqualsAndHashCode() {
            var f1 = new OpenAiStreamChunk.Function("a", "b");
            var f2 = new OpenAiStreamChunk.Function("a", "b");
            assertThat(f1).isEqualTo(f2).hasSameHashCodeAs(f2);

            var d1 = new OpenAiStreamChunk.Delta("role", "content", null);
            var d2 = new OpenAiStreamChunk.Delta("role", "content", null);
            assertThat(d1).isEqualTo(d2).hasSameHashCodeAs(d2);
        }
    }

    // ──────────────────────────────────────────────
    // OpenAiStreamError
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("OpenAiStreamError")
    class OpenAiStreamErrorTest {

        @Test
        void constructionAndAccessors() {
            OpenAiStreamError error = new OpenAiStreamError("rate_limit_exceeded",
                    "Too many requests");

            assertThat(error.type()).isEqualTo("rate_limit_exceeded");
            assertThat(error.message()).isEqualTo("Too many requests");
        }

        @Test
        void equalsHashCodeAndToString() {
            OpenAiStreamError a = new OpenAiStreamError("error", "msg");
            OpenAiStreamError b = new OpenAiStreamError("error", "msg");
            OpenAiStreamError c = new OpenAiStreamError("error", "different");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a.toString()).contains("error").contains("msg");
        }

        @Test
        void jsonRoundTrip() throws Exception {
            OpenAiStreamError original = new OpenAiStreamError("server_error",
                    "Internal error");

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"type\":\"server_error\"");

            OpenAiStreamError deserialized = mapper.readValue(json, OpenAiStreamError.class);
            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        void jsonRoundTripWithNulls() throws Exception {
            OpenAiStreamError original = new OpenAiStreamError(null, null);

            OpenAiStreamError deserialized = mapper.readValue(
                    mapper.writeValueAsString(original), OpenAiStreamError.class);
            assertThat(deserialized).isEqualTo(original);
        }
    }

    // ──────────────────────────────────────────────
    // SessionSummaryDto
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("SessionSummaryDto")
    class SessionSummaryDtoTest {

        @Test
        void constructionAndAccessors() {
            UUID id = UUID.randomUUID();
            Instant created = Instant.parse("2025-01-15T10:30:00Z");
            Instant updated = Instant.parse("2025-01-15T11:00:00Z");
            SessionSummaryDto dto = new SessionSummaryDto(id, "user1", "My Session",
                    "openai", "gpt-4", created, updated);

            assertThat(dto.id()).isEqualTo(id);
            assertThat(dto.userId()).isEqualTo("user1");
            assertThat(dto.title()).isEqualTo("My Session");
            assertThat(dto.modelProvider()).isEqualTo("openai");
            assertThat(dto.modelName()).isEqualTo("gpt-4");
            assertThat(dto.createdAt()).isEqualTo(created);
            assertThat(dto.updatedAt()).isEqualTo(updated);
        }

        @Test
        void equalsHashCodeAndToString() {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            SessionSummaryDto a = new SessionSummaryDto(id, "u", "t", "p", "m", now, now);
            SessionSummaryDto b = new SessionSummaryDto(id, "u", "t", "p", "m", now, now);
            SessionSummaryDto c = new SessionSummaryDto(id, "u", "different", "p", "m", now, now);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a.toString()).contains(id.toString());
        }

        @Test
        void jsonRoundTripWithNullInstants() throws Exception {
            SessionSummaryDto original = new SessionSummaryDto(null, "u", "t",
                    "p", "m", null, null);

            String json = mapper.writeValueAsString(original);
            SessionSummaryDto deserialized = mapper.readValue(json, SessionSummaryDto.class);
            assertThat(deserialized).isEqualTo(original);
            assertThat(deserialized.createdAt()).isNull();
            assertThat(deserialized.updatedAt()).isNull();
        }
    }

    // ──────────────────────────────────────────────
    // StreamEvent
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("StreamEvent")
    class StreamEventTest {

        @Test
        void constructionAndAccessors() {
            ToolCall toolCall = new ToolCall("tc-1", "read_file", "{\"path\":\"/tmp\"}");
            StreamEvent event = new StreamEvent("token", "Hello",
                    List.of(toolCall), null);

            assertThat(event.type()).isEqualTo("token");
            assertThat(event.token()).isEqualTo("Hello");
            assertThat(event.toolCalls()).hasSize(1);
            assertThat(event.toolCalls().get(0).id()).isEqualTo("tc-1");
            assertThat(event.toolCalls().get(0).name()).isEqualTo("read_file");
            assertThat(event.error()).isNull();
        }

        @Test
        void errorEvent() {
            StreamEvent event = new StreamEvent("error", null, null,
                    "Something went wrong");

            assertThat(event.type()).isEqualTo("error");
            assertThat(event.token()).isNull();
            assertThat(event.toolCalls()).isNull();
            assertThat(event.error()).isEqualTo("Something went wrong");
        }

        @Test
        void doneEvent() {
            StreamEvent event = new StreamEvent("done", null, null, null);

            assertThat(event.type()).isEqualTo("done");
            assertThat(event.token()).isNull();
        }

        @Test
        void jsonRoundTrip() throws Exception {
            ToolCall toolCall = new ToolCall("tc-2", "write_file", "{\"path\":\"/a\"}");
            StreamEvent original = new StreamEvent("token", "world",
                    List.of(toolCall), null);

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"type\":\"token\"");
            assertThat(json).contains("\"token\":\"world\"");

            StreamEvent deserialized = mapper.readValue(json, StreamEvent.class);
            assertThat(deserialized).isEqualTo(original);
            assertThat(deserialized.toolCalls()).hasSize(1);
        }

        @Test
        void jsonRoundTripErrorEvent() throws Exception {
            StreamEvent original = new StreamEvent("error", null, null, "timeout");

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"error\":\"timeout\"");

            StreamEvent deserialized = mapper.readValue(json, StreamEvent.class);
            assertThat(deserialized).isEqualTo(original);
            assertThat(deserialized.type()).isEqualTo("error");
        }
    }

    // ──────────────────────────────────────────────
    // VisionRequest
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("VisionRequest")
    class VisionRequestTest {

        @Test
        void constructionAndAccessors() {
            VisionRequest req = new VisionRequest("https://example.com/img.png",
                    "Describe this image", 10);

            assertThat(req.url()).isEqualTo("https://example.com/img.png");
            assertThat(req.prompt()).isEqualTo("Describe this image");
            assertThat(req.waitSeconds()).isEqualTo(10);
        }

        @Test
        void nullWaitSeconds() {
            VisionRequest req = new VisionRequest("https://example.com/img.png",
                    "What is this?", null);

            assertThat(req.waitSeconds()).isNull();
        }

        @Test
        void equalsHashCodeAndToString() {
            VisionRequest a = new VisionRequest("url1", "prompt1", 5);
            VisionRequest b = new VisionRequest("url1", "prompt1", 5);
            VisionRequest c = new VisionRequest("url1", "prompt2", 5);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            assertThat(a).isNotEqualTo(c);
            assertThat(a.toString()).contains("url1").contains("prompt1");
        }

        @Test
        void jsonRoundTrip() throws Exception {
            VisionRequest original = new VisionRequest("https://img.example.com/test.png",
                    "Analyze this", 30);

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"url\":\"https://img.example.com/test.png\"");
            assertThat(json).contains("\"waitSeconds\":30");

            VisionRequest deserialized = mapper.readValue(json, VisionRequest.class);
            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        void jsonRoundTripWithNullWaitSeconds() throws Exception {
            VisionRequest original = new VisionRequest("https://img.example.com/x.png",
                    "Describe", null);

            String json = mapper.writeValueAsString(original);
            assertThat(json).contains("\"waitSeconds\":null");

            VisionRequest deserialized = mapper.readValue(json, VisionRequest.class);
            assertThat(deserialized).isEqualTo(original);
            assertThat(deserialized.waitSeconds()).isNull();
        }
    }
}