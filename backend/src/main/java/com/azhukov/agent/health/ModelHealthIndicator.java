package com.azhukov.agent.health;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelHealthIndicator implements HealthIndicator {

    private final ModelClient modelClient;
    private final String modelName;

    public ModelHealthIndicator(ModelClient modelClient, AgentProperties properties) {
        this.modelClient = modelClient;
        this.modelName = properties.getModel().getModelName();
    }

    @Override
    public Health health() {
        try {
            var response = modelClient.complete(List.of(Message.user("ping")), List.of());
            String content = response.content() != null ? response.content().trim() : "";
            return Health.up()
                .withDetail("model", modelName)
                .withDetail("responseLength", content.length())
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("model", modelName)
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
