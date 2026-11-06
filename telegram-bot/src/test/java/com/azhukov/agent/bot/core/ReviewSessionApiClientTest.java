package com.azhukov.agent.bot.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ReviewSessionApiClientTest {
    @Test
    void reviewPreviewAndClaimUseBackendWireContract() {
        var builder = RestClient.builder().baseUrl("http://localhost");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new SessionApiClient(builder.build(), new ObjectMapper());
        String session = UUID.randomUUID().toString();
        String delivery = UUID.randomUUID().toString();
        String endpoint = "/api/v1/agent/session/" + session + "/review/";
        server.expect(requestTo("http://localhost" + endpoint + "pending"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("{\"pending\":true,\"deliveryId\":\"" + delivery + "\",\"summary\":\"review\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost" + endpoint + "ack"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"deliveryId\":\"" + delivery + "\"}"))
            .andRespond(withSuccess("{\"acknowledged\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost" + endpoint + "ack"))
            .andRespond(withSuccess("{\"acknowledged\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost" + endpoint + "ack"))
            .andRespond(withServerError());
        assertThat(client.getPendingReview(session).path("deliveryId").asText()).isEqualTo(delivery);
        assertThat(client.acknowledgePendingReview(session, delivery)).isTrue();
        assertThat(client.acknowledgePendingReview(session, delivery)).isFalse();
        assertThat(client.acknowledgePendingReview(session, delivery)).isFalse();
        server.verify();
    }
}
