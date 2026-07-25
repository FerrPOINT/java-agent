package com.azhukov.agent.gateway.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramRestClientFactoryTest {

    private static final String BOT_TOKEN = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11";
    private static final String ENCODED_BOT_TOKEN = "123456%3AABC-DEF1234ghIkl-zyx57W2v1u123ew11";

    @Test
    void generalClient_sendsPostToConfiguredBaseUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo("https://api.telegram.org/bot" + ENCODED_BOT_TOKEN + "/sendMessage"))
            .andExpect(method(POST))
            .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":1}}",
                org.springframework.http.MediaType.APPLICATION_JSON));

        restClient.post()
            .uri("https://api.telegram.org/bot{token}/{method}", BOT_TOKEN, "sendMessage")
            .body(Map.of("chat_id", 1L, "text", "hello"))
            .retrieve()
            .toBodilessEntity();

        server.verify();
    }

    @Test
    void pollingClient_readTimeoutExceeds40Seconds() {
        Duration pollingTimeout = Duration.ofSeconds(30);
        RestClient pollingClient = TelegramRestClientFactory.create(pollingTimeout);

        SimpleClientHttpRequestFactory factory = extractRequestFactory(pollingClient);
        assertThat(factory).extracting("readTimeout")
            .isEqualTo((int) pollingTimeout.toMillis() + 10_000);
    }

    @Test
    void client_propagatesBotTokenInUriTemplate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        server.expect(requestTo("https://api.telegram.org/bot" + ENCODED_BOT_TOKEN + "/getMe"))
            .andExpect(method(POST))
            .andRespond(withSuccess("{\"ok\":true,\"result\":{}}",
                org.springframework.http.MediaType.APPLICATION_JSON));

        restClient.post()
            .uri("https://api.telegram.org/bot{token}/{method}", BOT_TOKEN, "getMe")
            .retrieve()
            .toBodilessEntity();

        server.verify();
    }

    @Test
    void client_usesConfiguredConnectTimeout() {
        Duration connectTimeout = Duration.ofSeconds(5);
        RestClient client = TelegramRestClientFactory.create(connectTimeout);

        SimpleClientHttpRequestFactory factory = extractRequestFactory(client);
        assertThat(factory).extracting("connectTimeout")
            .isEqualTo((int) connectTimeout.toMillis());
    }

    private SimpleClientHttpRequestFactory extractRequestFactory(RestClient restClient) {
        try {
            Field requestFactoryField = restClient.getClass().getDeclaredField("clientRequestFactory");
            requestFactoryField.setAccessible(true);
            return (SimpleClientHttpRequestFactory) requestFactoryField.get(restClient);
        } catch (NoSuchFieldException e) {
            // Fallback: search superclass hierarchy for the request factory field.
            Class<?> clazz = restClient.getClass().getSuperclass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField("clientRequestFactory");
                    field.setAccessible(true);
                    return (SimpleClientHttpRequestFactory) field.get(restClient);
                } catch (NoSuchFieldException ex) {
                    clazz = clazz.getSuperclass();
                } catch (IllegalAccessException ex) {
                    throw new RuntimeException(ex);
                }
            }
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
