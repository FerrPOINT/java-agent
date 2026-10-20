package com.azhukov.agent.shared.http;

import com.azhukov.agent.shared.SharedObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * h10: shared REST client factory — pins the client contract (X-API-Key
 * header, base URL wiring, tolerant error-body parsing).
 */
class BackendRestClientFactoryTest {

    private static MockRestServiceServer serverFor(RestClient.Builder builder) {
        return MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("API key is sent as X-API-Key when configured")
    void apiKeyHeaderSent() {
        RestClient.Builder builder = BackendRestClientFactory.create("https://backend.example.invalid", "secret-key")
            .mutate();
        MockRestServiceServer server = serverFor(builder);
        server.expect(request -> {
                assertThat(request.getURI()).hasToString("https://backend.example.invalid/probe");
                assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
                assertThat(request.getHeaders().getFirst("X-API-Key")).isEqualTo("secret-key");
            })
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        assertThat(builder.build().get().uri("/probe").retrieve().body(String.class)).isEqualTo("ok");
        server.verify();
    }

    @Test
    @DisplayName("blank/null API key omits the header (no NPE, no empty header)")
    void blankKeyOmitsHeader() {
        for (String key : new String[] {null, "  "}) {
            RestClient.Builder builder = BackendRestClientFactory.create("https://backend.example.invalid", key)
                .mutate();
            MockRestServiceServer server = serverFor(builder);
            server.expect(request -> {
                    assertThat(request.getURI()).hasToString("https://backend.example.invalid/probe");
                    assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
                    assertThat(request.getHeaders().containsKey("X-API-Key")).isFalse();
                })
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
            assertThat(builder.build().get().uri("/probe").retrieve().body(String.class)).isEqualTo("ok");
            server.verify();
        }
        assertThat(BackendRestClientFactory.CONNECT_TIMEOUT)
            .isEqualTo(java.time.Duration.ofSeconds(10));
        assertThat(BackendRestClientFactory.READ_TIMEOUT)
            .isEqualTo(java.time.Duration.ofMinutes(10));
        assertThat(BackendRestClientFactory.STREAMING_READ_TIMEOUT)
            .isEqualTo(java.time.Duration.ofDays(8));
    }

    @Test
    @DisplayName("client parses a ChatResponseDto wire payload with shared conventions")
    void parsesChatResponseDto() throws Exception {
        String wire = """
            {"sessionId":"7fcdc9d2-528b-3c1a-9d2b-000000000001",
             "content":"hello",
             "toolCalls":[],
             "completed":true,
             "memoryUpdated":false,
             "modelUsed":"app-test",
             "contextTokens":120,
             "contextLength":8192}
            """;
        com.azhukov.agent.shared.dto.ChatResponseDto dto = SharedObjectMapper.get()
            .readValue(wire, com.azhukov.agent.shared.dto.ChatResponseDto.class);
        assertThat(dto.content()).isEqualTo("hello");
        assertThat(dto.modelUsed()).isEqualTo("app-test");
        assertThat(dto.completed()).isTrue();
        assertThat(dto.contextTokens()).isEqualTo(120);
    }

    @Test
    @DisplayName("mapper tolerates unknown/extra wire fields (forward compatibility)")
    void tolerantUnknownFields() throws Exception {
        String wire = "{\"content\":\"x\",\"someFutureField\":true}";
        com.azhukov.agent.shared.dto.ChatResponseDto dto = SharedObjectMapper.get()
            .readValue(wire, com.azhukov.agent.shared.dto.ChatResponseDto.class);
        assertThat(dto.content()).isEqualTo("x");
    }

    @Test
    @DisplayName("legacy 'response' field does not break parsing (old backend builds)")
    void legacyResponseFieldIgnored() throws Exception {
        String wire = "{\"response\":\"legacy text\"}";
        com.azhukov.agent.shared.dto.ChatResponseDto dto = SharedObjectMapper.get()
            .readValue(wire, com.azhukov.agent.shared.dto.ChatResponseDto.class);
        assertThat(dto.content()).isNull(); // MessageApiClient keeps its explicit legacy fallback
    }

    @Test
    void explicitJacksonConverterUsesTheSharedMapperAndAcceptsOctetStream() {
        RestClient.Builder builder = BackendRestClientFactory.create("https://backend.example.invalid", null).mutate();
        builder.messageConverters(converters -> {
            var jackson = converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .toList();
            assertThat(jackson).hasSize(1);
            assertThat(jackson.getFirst().getObjectMapper()).isSameAs(SharedObjectMapper.get());
            assertThat(jackson.getFirst().getSupportedMediaTypes()).contains(MediaType.APPLICATION_OCTET_STREAM);
        });
        builder.build();
    }

    @Test
    void realConvertersPreserveJsonDatesAndTolerantTypedResponses() {
        for (MediaType responseType : new MediaType[] {MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM}) {
            RestClient.Builder builder = BackendRestClientFactory.create("https://backend.example.invalid", null)
                .mutate();
            MockRestServiceServer server = serverFor(builder);
            Instant at = Instant.parse("2026-10-20T10:15:30Z");
            server.expect(request -> {
                    assertThat(request.getURI()).hasToString("https://backend.example.invalid/wire");
                    assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
                    assertThat(request.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
                    var sent = SharedObjectMapper.get().readTree(((MockClientHttpRequest) request).getBodyAsBytes());
                    assertThat(sent.path("at").asText()).isEqualTo(at.toString());
                })
                .andRespond(withSuccess("{\"at\":\"2026-10-20T10:15:30Z\",\"known\":\"ok\",\"future\":true}",
                                        responseType));
            WirePayload response = builder.build().post().uri("/wire").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("at", at)).retrieve().body(WirePayload.class);
            assertThat(response).isEqualTo(new WirePayload(at, "ok"));
            server.verify();
        }
    }

    record WirePayload(Instant at, String known) {
    }
}
