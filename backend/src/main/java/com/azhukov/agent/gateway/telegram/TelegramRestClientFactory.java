package com.azhukov.agent.gateway.telegram;

import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

public class TelegramRestClientFactory {

    public static RestClient create(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        // Read timeout must exceed long-polling timeout so empty responses don't get cut off.
        factory.setReadTimeout((int) (timeout.toMillis() + 10_000));
        return RestClient.builder()
            .requestFactory(factory)
            .build();
    }
}
