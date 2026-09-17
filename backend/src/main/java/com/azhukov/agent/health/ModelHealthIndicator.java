package com.azhukov.agent.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelHealthIndicator implements HealthIndicator {

    private final ModelClient modelClient;
    private final AgentProperties properties;

    @Override
    public Health health() {
        String provider = properties.getModel().getProvider();
        if ("noop".equalsIgnoreCase(provider)) {
            return Health.up()
                .withDetail("model", properties.getModel().getModelName())
                .withDetail("provider", "noop")
                .withDetail("status", "not_configured")
                .build();
        }
        String modelName = properties.getModel().getModelName();
        int timeoutSeconds = Math.max(1, properties.getModel().getHealthTimeoutSeconds());
        CompletableFuture<com.azhukov.agent.core.model.ChatResponse> probe = CompletableFuture.supplyAsync(
            () -> modelClient.complete(List.of(Message.user("ping")), List.of()));
        try {
            var response = probe.get(timeoutSeconds, TimeUnit.SECONDS);
            String content = response.content() != null ? response.content().trim() : "";
            return Health.up()
                .withDetail("model", modelName)
                .withDetail("responseLength", content.length())
                .build();
        } catch (TimeoutException e) {
            probe.cancel(true);
            log.warn("Model health check timed out after {}s", timeoutSeconds);
            return Health.down()
                .withDetail("model", modelName != null ? modelName : "unknown")
                .withDetail("error", "model health probe timed out after " + timeoutSeconds + "s")
                .build();
        } catch (Exception e) {
            log.warn("Model health check failed: {}", e.getMessage());
            return Health.down()
                .withDetail("model", modelName != null ? modelName : "unknown")
                .withDetail("error", errorMessage(e))
                .build();
        }
    }

    private String errorMessage(Exception error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
    }
}