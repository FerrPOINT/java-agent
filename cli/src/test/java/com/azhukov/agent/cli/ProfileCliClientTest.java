package com.azhukov.agent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProfileCliClientTest {

    @TempDir
    Path temp;

    @Test
    void lostAcceptanceUsesOriginalProfileAndRequestKeyWithoutAnotherSubmit() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var alpha = client("alpha", builder);
        String session = UUID.randomUUID().toString();
        String run = "run_" + "1".repeat(60);
        AtomicReference<String> originalKey = new AtomicReference<>();
        String input = "{\"session_id\":\"" + session + "\",\"input\":\"own prompt\"}";

        server.expect(requestTo("/p/alpha/v1/runs")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json(input))
            .andExpect(request -> {
                String key = request.getHeaders().getFirst("Idempotency-Key");
                assertThat(key).isNotBlank();
                originalKey.set(key);
            })
            .andRespond(withException(new SocketTimeoutException("discarded response")));
        server.expect(requestTo("/p/alpha/v1/runs/lookup")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json(input))
            .andExpect(request -> header("Idempotency-Key", originalKey.get()).match(request))
            .andRespond(withSuccess("{\"run_id\":\"" + run + "\"}", MediaType.APPLICATION_JSON));

        assertThat(alpha.submit(session, "own prompt")).isEqualTo(run);
        assertThat(client("alpha", builder).currentRun(session)).isEqualTo(run);
        assertThatThrownBy(() -> client("beta", builder).currentRun(session))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no run recorded");
        server.verify();
    }

    @Test
    void knownSessionAndCronIdsAreAddressedOnlyUnderSelectedProfile() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var beta = client("beta", builder);
        String alphaSession = UUID.randomUUID().toString();
        String betaSession = UUID.randomUUID().toString();
        String job = UUID.randomUUID().toString();

        server.expect(requestTo("/api/sessions/" + alphaSession + "?profile=beta"))
            .andRespond(withResourceNotFound());
        server.expect(requestTo("/api/sessions/" + betaSession + "?profile=beta"))
            .andRespond(withSuccess("{\"id\":\"" + betaSession + "\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("/api/v1/agent/cron/" + job + "/run?profile=beta"))
            .andExpect(method(HttpMethod.POST)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(beta.ownsSession(alphaSession)).isFalse();
        assertThat(beta.ownsSession(betaSession)).isTrue();
        assertThatThrownBy(() -> beta.cronAction(job, "run"))
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
        server.verify();
    }

    private ProfileCliClient client(String name, RestClient.Builder builder) {
        CliProperties properties = new CliProperties() {
            @Override
            Path stateDirectory() {
                return temp.resolve(getProfile());
            }
        };
        properties.setProfile(name);
        return new ProfileCliClient(builder.build(), new ObjectMapper(), properties);
    }
}
