package com.azhukov.agent.bot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramClientTest {

    private MockInterceptor interceptor;
    private TelegramClient client;

    @BeforeEach
    void setUp() {
        interceptor = new MockInterceptor();
        RestClient restClient = RestClient.builder()
            .requestInterceptor(interceptor)
            .build();
        client = new TelegramClient(restClient, new ObjectMapper(), "test-token", 25);
    }

    @Test
    void sendMessage_returnsMessageId() {
        interceptor.setResponse("""
            {"ok":true,"result":{"message_id":42,"date":1700000000,"chat":{"id":123}}}
            """);
        Optional<Long> msgId = client.sendMessage(123, "Hello");
        assertThat(msgId).contains(42L);
        assertThat(interceptor.lastMethod).isEqualTo("sendMessage");
    }

    @Test
    void sendMessage_failedResponse_returnsEmpty() {
        interceptor.setResponse("""
            {"ok":false,"error_code":400,"description":"Bad Request: chat not found"}
            """);
        Optional<Long> msgId = client.sendMessage(999, "Hello");
        assertThat(msgId).isEmpty();
    }

    @Test
    void sendChatAction_typing_returnsTrue() {
        interceptor.setResponse("{\"ok\":true,\"result\":true}");
        boolean result = client.sendTyping(123);
        assertThat(result).isTrue();
        assertThat(interceptor.lastMethod).isEqualTo("sendChatAction");
    }

    @Test
    void editMessageText_returnsTrue() {
        interceptor.setResponse("{\"ok\":true,\"result\":{\"message_id\":42}}");
        boolean result = client.editMessageText(123, 42, "Updated", "MarkdownV2");
        assertThat(result).isTrue();
        assertThat(interceptor.lastMethod).isEqualTo("editMessageText");
    }

    @Test
    void deleteMessage_returnsTrue() {
        interceptor.setResponse("{\"ok\":true,\"result\":true}");
        boolean result = client.deleteMessage(123, 42);
        assertThat(result).isTrue();
    }

    @Test
    void getUpdates_returnsListOfUpdates() {
        interceptor.setResponse("""
            {"ok":true,"result":[{"update_id":100,"message":{"message_id":1,"text":"hi","chat":{"id":123},"from":{"id":456}}}]}
            """);
        Optional<List<Map<String, Object>>> updates = client.getUpdates(1, 100, 30);
        assertThat(updates).isPresent();
        assertThat(updates.get()).hasSize(1);
        assertThat(updates.get().get(0).get("update_id")).isEqualTo(100);
    }

    @Test
    void answerCallbackQuery_returnsTrue() {
        interceptor.setResponse("{\"ok\":true,\"result\":true}");
        boolean result = client.answerCallbackQuery("cq-1", "Selected", false);
        assertThat(result).isTrue();
    }

    @Test
    void emptyToken_returnsEmpty() {
        client = new TelegramClient(RestClient.create(), new ObjectMapper(), "", 25);
        Optional<Long> result = client.sendMessage(123, "test");
        assertThat(result).isEmpty();
    }

    @Test
    void getFile_returnsFileInfo() {
        interceptor.setResponse("""
            {"ok":true,"result":{"file_id":"abc","file_unique_id":"def","file_size":1024,"file_path":"photos/file_1.jpg"}}
            """);
        Optional<Map<String, Object>> file = client.getFile("abc");
        assertThat(file).isPresent();
        assertThat(file.get().get("file_path")).isEqualTo("photos/file_1.jpg");
    }

    // ─── Mock interceptor ─────────────────────────────────────────

    static class MockInterceptor implements ClientHttpRequestInterceptor {
        private String responseBody = "{\"ok\":true,\"result\":{}}";
        String lastMethod = "";

        void setResponse(String body) {
            this.responseBody = body;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) {
            String uri = request.getURI().toString();
            int slashIdx = uri.lastIndexOf('/');
            if (slashIdx > 0) {
                lastMethod = uri.substring(slashIdx + 1);
            }
            return new ClientHttpResponse() {
                @Override
                public org.springframework.http.HttpStatus getStatusCode() {
                    return org.springframework.http.HttpStatus.OK;
                }
                @Override
                public String getStatusText() {
                    return "OK";
                }
                @Override
                public org.springframework.http.HttpHeaders getHeaders() {
                    var h = new org.springframework.http.HttpHeaders();
                    h.setContentType(MediaType.APPLICATION_JSON);
                    return h;
                }
                @Override
                public java.io.InputStream getBody() {
                    return new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
                }
                @Override
                public void close() {}
            };
        }
    }
}