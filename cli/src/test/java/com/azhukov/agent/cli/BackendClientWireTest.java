package com.azhukov.agent.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Exercises the real shared converters through an in-memory HTTP transport. */
class BackendClientWireTest {

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

}
