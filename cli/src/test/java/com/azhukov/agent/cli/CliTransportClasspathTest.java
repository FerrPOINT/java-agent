package com.azhukov.agent.cli;

import com.azhukov.agent.shared.dto.ChatResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CliTransportClasspathTest {

    @Test
    void implicitApplicationTypeRemainsNonWeb() {
        // Inspect Boot's classpath deduction only; never start an application
        // context, the interactive REPL or a backend connection in this unit.
        assertThat(new SpringApplication(CliApplication.class).getWebApplicationType())
            .isEqualTo(WebApplicationType.NONE);
    }

    @Test
    void clientRetainsHttpsUriApiKeyAndTolerantJackson2Response() {
        RestClient.Builder builder = clientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://backend.example.test/api/messages"))
            .andExpect(header("X-API-Key", "fixture-api-key"))
            .andExpect(content().json("{\"message\":\"hello\",\"clientTime\":\"2026-10-21T12:00:00Z\"}"))
            .andRespond(withSuccess("{\"content\":\"ok\",\"futureField\":true}",
                MediaType.APPLICATION_OCTET_STREAM));

        ChatResponseDto response = builder.build().post().uri("/api/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("message", "hello", "clientTime", Instant.parse("2026-10-21T12:00:00Z")))
            .retrieve().body(ChatResponseDto.class);

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("ok");
        server.verify();
    }

    @Test
    void clientRetainsMultipartResourceConversion() {
        RestClient.Builder builder = clientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://backend.example.test/api/upload"))
            .andExpect(header("X-API-Key", "fixture-api-key"))
            .andExpect(content().string(containsString("filename=\"fixture.txt\"")))
            .andExpect(content().string(containsString("fixture-payload")))
            .andExpect(content().string(containsString("name=\"origin\"")))
            .andExpect(content().string(containsString("name=\"disposition\"")))
            .andRespond(withSuccess("{\"content\":\"uploaded\"}", MediaType.APPLICATION_OCTET_STREAM));
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        multipart.part("file", new ByteArrayResource("fixture-payload".getBytes(StandardCharsets.UTF_8)))
            .filename("fixture.txt");
        multipart.part("origin", "cli");
        multipart.part("disposition", "photo");

        ChatResponseDto response = builder.build().post().uri("/api/upload")
            .contentType(MediaType.MULTIPART_FORM_DATA).body(multipart.build())
            .retrieve().body(ChatResponseDto.class);

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("uploaded");
        server.verify();
    }

    private RestClient.Builder clientBuilder() {
        CliProperties properties = new CliProperties();
        properties.setBackendUrl("https://backend.example.test");
        properties.setApiKey("fixture-api-key");
        // mutate preserves the actual configured client. The mock replaces only
        // transport; these tests do not claim a real TLS/proxy handshake.
        return new CliConfig().backendRestClient(properties).mutate();
    }
}
