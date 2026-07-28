package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public class VersionCommand implements CommandHandler {

    private final BotProperties properties;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    public VersionCommand(BotProperties properties, ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.properties = properties;
        this.buildPropertiesProvider = buildPropertiesProvider;
    }

    @Override
    public String name() {
        return "version";
    }

    @Override
    public String description() {
        return "Show agent version";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        StringBuilder sb = new StringBuilder("Version info:\n");
        sb.append("  Agent: ").append(properties.getAgentName()).append("\n");

        BuildProperties buildProps = buildPropertiesProvider.getIfAvailable();
        if (buildProps != null) {
            sb.append("  Version: ").append(buildProps.getVersion()).append("\n");
            sb.append("  Build time: ").append(buildProps.get("build.time") != null ? buildProps.get("build.time") : "unknown").append("\n");
        } else {
            sb.append("  Version: dev\n");
            sb.append("  Build time: unknown\n");
        }
        return sb.toString();
    }
}