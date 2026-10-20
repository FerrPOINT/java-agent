package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests for BackendClient — mocks RestClient to verify correct API calls and response parsing.
 * <p>
 * Key insight: RequestBodyUriSpec extends RequestBodySpec, so the same mock
 * (postUriSpec) can be returned from every chain step (uri, contentType, accept, body).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackendClientTest {

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec postUriSpec;
    @Mock
    @SuppressWarnings("rawtypes")
    RestClient.RequestHeadersUriSpec getUriSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private BackendClient backendClient;

    @BeforeEach
    void setUp() {
        backendClient = new BackendClient(restClient, objectMapper);
    }

    /**
     * Wire up the full POST chain. Since RequestBodyUriSpec extends RequestBodySpec,
     * every method (uri, contentType, accept, body, retrieve) can return postUriSpec itself.
     */
    private void mockPostChain(String responseBody) {
        doReturn(postUriSpec).when(restClient).post();
        doReturn(postUriSpec).when(postUriSpec).uri(anyString());
        doReturn(postUriSpec).when(postUriSpec).uri(anyString(), any(java.util.Map.class));
        doReturn(postUriSpec).when(postUriSpec).uri(anyString(), any(Object[].class));
        // RequestBodySpec methods — postUriSpec IS a RequestBodySpec
        doReturn(postUriSpec).when(postUriSpec).contentType(any(MediaType.class));
        doReturn(postUriSpec).when(postUriSpec).accept(any(MediaType.class));
        doReturn(postUriSpec).when(postUriSpec).body((Object) any());
        doReturn(responseSpec).when(postUriSpec).retrieve();
        doReturn(responseBody).when(responseSpec).body(String.class);
        doReturn(null).when(responseSpec).body(InputStream.class);
        doReturn(null).when(responseSpec).toBodilessEntity();
    }

    /**
     * Wire up POST → body(InputStream.class) for SSE streaming.
     */
    private void mockPostStream(InputStream sseStream) {
        doReturn(postUriSpec).when(restClient).post();
        doReturn(postUriSpec).when(postUriSpec).uri(anyString());
        doReturn(postUriSpec).when(postUriSpec).accept(any(MediaType.class));
        doReturn(postUriSpec).when(postUriSpec).contentType(any(MediaType.class));
        doReturn(postUriSpec).when(postUriSpec).body((Object) any());
        doReturn(responseSpec).when(postUriSpec).retrieve();
        doReturn(sseStream).when(responseSpec).body(InputStream.class);
    }

    /**
     * Wire up GET → body(String.class).
     */
    private void mockGetChain(String responseBody) {
        doReturn(getUriSpec).when(restClient).get();
        doReturn(getUriSpec).when(getUriSpec).uri(anyString());
        doReturn(getUriSpec).when(getUriSpec).uri(anyString(), any(java.util.Map.class));
        doReturn(getUriSpec).when(getUriSpec).uri(anyString(), any(Object[].class));
        doReturn(responseSpec).when(getUriSpec).retrieve();
        doReturn(responseBody).when(responseSpec).body(String.class);
    }

    // ── Chat ──

    @Test
    void chatParsesResponseField() {
        mockPostChain("{\"response\":\"Hello from backend\",\"sessionId\":\"abc-123\"}");
        assertThat(backendClient.chat("hi", "session-123"))
            .isEqualTo("Hello from backend");
    }

    @Test
    void chatParsesContentFieldWhenResponseAbsent() {
        mockPostChain("{\"content\":\"Fallback content\"}");
        assertThat(backendClient.chat("test", null))
            .isEqualTo("Fallback content");
    }

    @Test
    void chatReturnsErrorOnEmptyResponse() {
        mockPostChain("");
        assertThat(backendClient.chat("msg", "sid"))
            .startsWith("Error:");
    }

    @Test
    void chatReturnsErrorOnException() {
        doReturn(postUriSpec).when(restClient).post();
        doReturn(postUriSpec).when(postUriSpec).uri(anyString());
        doReturn(postUriSpec).when(postUriSpec).contentType(any(MediaType.class));
        doReturn(postUriSpec).when(postUriSpec).body((Object) any());
        doReturn(responseSpec).when(postUriSpec).retrieve();
        doThrow(new RuntimeException("connection refused")).when(responseSpec).body(String.class);

        String result = backendClient.chat("msg", "sid");
        assertThat(result).startsWith("Error:");
        assertThat(result).contains("connection refused");
    }

    // ── Chat streaming ──

    @Test
    void chatStreamParsesTokenEvents() {
        String sse = "data:{\"type\":\"token\",\"token\":\"Hello\"}\n" +
            "data:{\"type\":\"token\",\"token\":\" world\"}\n" +
            "data:[DONE]\n";
        mockPostStream(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));

        StringBuilder tokens = new StringBuilder();
        boolean[] done = {false};

        backendClient.chatStream("hi", "sid", tokens::append, t -> {}, () -> done[0] = true);

        assertThat(tokens.toString()).isEqualTo("Hello world");
        assertThat(done[0]).isTrue();
    }

    @Test
    void jsonPostUsesTheActualSharedClientConvertersAndApiKey() {
        RestClient.Builder builder = com.azhukov.agent.shared.http.BackendRestClientFactory
            .create("https://backend.example.invalid", "cli-test-key").mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://backend.example.invalid/wire"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-Key", "cli-test-key"))
            .andExpect(request -> {
                assertThat(request.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
                var body = SharedObjectMapper.get().readTree(((MockClientHttpRequest) request).getBodyAsBytes());
                assertThat(body.path("message").asText()).isEqualTo("hello");
            })
            .andRespond(withSuccess("{\"ok\":true,\"future\":\"retained\"}", MediaType.APPLICATION_OCTET_STREAM));
        BackendClient wireClient = new BackendClient(builder.build(), SharedObjectMapper.get());
        var result = wireClient.executePost("/wire", Map.of("message", "hello"));
        assertThat(result).isNotNull();
        assertThat(result.path("ok").asBoolean()).isTrue();
        server.verify();
    }

    @Test
    void sseUsesTheActualSharedStreamingClientWithoutAWebServer() {
        RestClient.Builder builder = com.azhukov.agent.shared.http.BackendRestClientFactory
            .createStreaming("https://backend.example.invalid", "cli-test-key").mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://backend.example.invalid/api/v1/agent/chat/stream"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-Key", "cli-test-key"))
            .andExpect(request -> {
                assertThat(request.getHeaders().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
                var body = SharedObjectMapper.get().readTree(((MockClientHttpRequest) request).getBodyAsBytes());
                assertThat(body.path("sessionId").asText()).isEqualTo("wire-session");
                assertThat(body.path("message").asText()).isEqualTo("hello");
            })
            .andRespond(withSuccess("data:{\"type\":\"token\",\"token\":\"hello\"}\n\n"
                + "data:{\"type\":\"tool_start\",\"toolName\":\"search\"}\n\n"
                + "data:[DONE]\n\n", MediaType.TEXT_EVENT_STREAM));
        BackendClient wireClient = new BackendClient(builder.build(), SharedObjectMapper.get());
        StringBuilder tokens = new StringBuilder();
        StringBuilder tools = new StringBuilder();
        int[] completed = {0};
        wireClient.chatStream("hello", "wire-session", tokens::append, tools::append, () -> completed[0]++);
        assertThat(tokens.toString()).isEqualTo("hello");
        assertThat(tools.toString()).contains("search");
        assertThat(completed[0]).isEqualTo(1);
        server.verify();
    }

    @Test
    void multipartUploadKeepsFileBytesFilenameAndDisposition(@TempDir Path directory) throws Exception {
        byte[] payload = {0, 1, 2, 13, 10, (byte) 255};
        Path file = directory.resolve("report name.bin");
        Files.write(file, payload);
        RestClient.Builder builder = com.azhukov.agent.shared.http.BackendRestClientFactory
            .create("https://backend.example.invalid", "cli-test-key").mutate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://backend.example.invalid/api/v1/attachments"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-API-Key", "cli-test-key"))
            .andExpect(request -> {
                MediaType type = request.getHeaders().getContentType();
                assertThat(type).isNotNull();
                assertThat(type.isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
                assertThat(type.getParameter("boundary")).isNotBlank();
                byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
                assertThat(body).containsSequence(payload);
                String parts = new String(body, StandardCharsets.ISO_8859_1);
                assertThat(parts).contains("name=\"file\"; filename=\"report_name.bin\"",
                    "name=\"origin\"", "\r\n\r\ncli\r\n", "name=\"disposition\"", "\r\n\r\nfile\r\n");
            })
            .andRespond(withSuccess("{\"id\":\"artifact-1\"}", MediaType.APPLICATION_JSON));
        BackendClient wireClient = new BackendClient(builder.build(), SharedObjectMapper.get());
        assertThat(wireClient.uploadAttachment(file, "file")).isEqualTo("artifact-1");
        server.verify();
    }

    @Test
    void chatStreamHandlesToolStartEvent() {
        String sse = "data:{\"type\":\"tool_start\",\"toolName\":\"web_search\"}\n" +
            "data:[DONE]\n";
        mockPostStream(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));

        StringBuilder tools = new StringBuilder();
        backendClient.chatStream("search", "sid", t -> {}, tools::append, () -> {});

        assertThat(tools.toString()).contains("web_search");
    }

    @Test
    void chatStreamHandlesErrorEvent() {
        String sse = "data:{\"type\":\"error\",\"error\":\"model overloaded\"}\n" +
            "data:[DONE]\n";
        mockPostStream(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));

        StringBuilder tools = new StringBuilder();
        backendClient.chatStream("msg", "sid", t -> {}, tools::append, () -> {});

        assertThat(tools.toString()).contains("ERROR: model overloaded");
    }

    // ── Health ──

    @Test
    void healthReturnsTrueWhenUp() {
        mockGetChain("{\"status\":\"UP\"}");
        assertThat(backendClient.health()).isTrue();
    }

    @Test
    void healthReturnsFalseWhenDown() {
        mockGetChain("{\"status\":\"DOWN\"}");
        assertThat(backendClient.health()).isFalse();
    }

    @Test
    void healthReturnsFalseOnException() {
        doReturn(getUriSpec).when(restClient).get();
        doReturn(getUriSpec).when(getUriSpec).uri(anyString());
        doReturn(responseSpec).when(getUriSpec).retrieve();
        doThrow(new RuntimeException("conn refused")).when(responseSpec).body(String.class);

        assertThat(backendClient.health()).isFalse();
    }

    // ── Session management ──

    @Test
    void resetSessionCallsCorrectEndpoint() {
        doReturn(postUriSpec).when(restClient).post();
        doReturn(postUriSpec).when(postUriSpec).uri(anyString(), any(java.util.Map.class));
        doReturn(postUriSpec).when(postUriSpec).uri(anyString(), any(Object[].class));
        doReturn(responseSpec).when(postUriSpec).retrieve();
        doReturn(null).when(responseSpec).toBodilessEntity();

        String result = backendClient.resetSession("session-123");
        assertThat(result).contains("Session reset").contains("session-123");
    }

    // ── Approve / deny ──

    @Test
    void approveCallsEndpoint() {
        mockPostChain("Approved all");
        assertThat(backendClient.approve(true, null)).isEqualTo("Approved all");
    }

    @Test
    void denyCallsEndpoint() {
        mockPostChain("Denied all");
        assertThat(backendClient.deny(true)).isEqualTo("Denied all");
    }

    // ── Admin ──

    @Test
    void restartCallsEndpoint() {
        doReturn(postUriSpec).when(restClient).post();
        doReturn(postUriSpec).when(postUriSpec).uri(anyString());
        doReturn(responseSpec).when(postUriSpec).retrieve();
        doReturn(null).when(responseSpec).toBodilessEntity();

        assertThat(backendClient.restart()).contains("restarting");
    }

    @Test
    void prettyPrintHandlesNullNode() {
        assertThat(backendClient.prettyPrint(null)).isEqualTo("null");
    }
}
