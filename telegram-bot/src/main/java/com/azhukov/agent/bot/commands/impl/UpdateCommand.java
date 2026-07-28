package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * A3.4: /update — Show current version and update instructions.
 */
@Component
public class UpdateCommand implements CommandHandler {

    private final BotProperties properties;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    public UpdateCommand(BotProperties properties, ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.properties = properties;
        this.buildPropertiesProvider = buildPropertiesProvider;
    }

    @Override
    public String name() {
        return "update";
    }

    @Override
    public String description() {
        return "Show update instructions";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        StringBuilder sb = new StringBuilder("Update info:\n");
        sb.append("  Agent: ").append(properties.getAgentName()).append("\n");

        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        if (buildProps != null) {
            sb.append("  Current version: ").append(buildProps.getVersion()).append("\n");
        } else {
            sb.append("  Current version: dev\n");
        }

        sb.append("\nUpdate via:\n");
        sb.append("  docker-compose pull && docker-compose up -d\n");
        return sb.toString();
    }
}