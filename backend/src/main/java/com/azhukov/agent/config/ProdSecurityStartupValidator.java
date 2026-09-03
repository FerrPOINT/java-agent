package com.azhukov.agent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class ProdSecurityStartupValidator implements ApplicationRunner {

    private static final int MIN_PROD_API_KEY_LENGTH = 16;

    private final AgentProperties properties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (!isProdProfileActive()) {
            return;
        }
        String apiKey = properties.getSecurity().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "agent.security.api-key must be configured when the prod profile is active");
        }
        if (apiKey.length() < MIN_PROD_API_KEY_LENGTH) {
            throw new IllegalStateException(
                "agent.security.api-key must be at least 16 characters when the prod profile is active");
        }
    }

    private boolean isProdProfileActive() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }
}
