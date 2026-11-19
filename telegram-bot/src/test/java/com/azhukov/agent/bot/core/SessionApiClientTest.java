package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SessionApiClientTest {
    private static final String SESSION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String URL = "http://java-agent.test/api/v1/agent/session/" + SESSION + "/compress";
    private MockRestServiceServer server;
    private SessionApiClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://java-agent.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SessionApiClient(builder.build(), new ObjectMapper());
    }

    @Test
    void compressionSendsCurrentFocusFieldAndPreservesUnicode() {
        server.expect(requestTo(URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"focusTopic\":\"архитектура\"}", JsonCompareMode.STRICT))
            .andRespond(withSuccess("Context compressed.", MediaType.TEXT_PLAIN));

        assertThat(client.compressSession(SESSION, "архитектура")).isEqualTo("Context compressed.");
        server.verify();
    }

    @Test
    void absentAndBlankFocusSendAnEmptyCurrentBody() {
        for (String focus : new String[] {null, "", "  "}) {
            server.reset();
            server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{}", JsonCompareMode.STRICT))
                .andRespond(withSuccess("Context compressed.", MediaType.TEXT_PLAIN));
            assertThat(client.compressSession(SESSION, focus)).isEqualTo("Context compressed.");
            server.verify();
        }
    }

    @Test
    void partialCompressionSendsOnlyKeepLastN() {
        server.expect(requestTo(URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"keepLastN\":5}", JsonCompareMode.STRICT))
            .andRespond(withSuccess("Context compressed.", MediaType.TEXT_PLAIN));
        assertThat(client.compressSessionPartial(SESSION, 5))
            .isEqualTo("Context compressed (kept last 5 exchanges).");
        server.verify();
    }
}
