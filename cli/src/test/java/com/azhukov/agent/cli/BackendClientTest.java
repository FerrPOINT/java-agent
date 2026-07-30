package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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