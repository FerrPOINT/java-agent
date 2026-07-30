package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * A2.5: /reload_skills — Call backend POST /api/v1/agent/reload-skills. Show result.
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReloadSkillsCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "reload_skills";
    }

    @Override
    public String description() {
        return "Reload skills";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        return backendClient.reloadSkills();
    }
}