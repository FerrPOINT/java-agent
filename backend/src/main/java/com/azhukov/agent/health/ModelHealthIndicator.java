package com.azhukov.agent.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

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
        try {
            var response = modelClient.complete(List.of(Message.user("ping")), List.of());
            String content = response.content() != null ? response.content().trim() : "";
            return Health.up()
                .withDetail("model", modelName)
                .withDetail("responseLength", content.length())
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("model", modelName != null ? modelName : "unknown")
                .withDetail("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName())
                .build();
        }
    }
}