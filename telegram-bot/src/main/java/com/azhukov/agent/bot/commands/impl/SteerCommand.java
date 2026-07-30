package com.azhukov.agent.bot.commands.impl;

import com.azhukov.agent.bot.commands.CommandHandler;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.session.BotSessionEntity;
import org.springframework.stereotype.Component;

/**
 * /steer &lt;prompt&gt; — Inject a mid-run note that arrives at the agent
 * after the next tool call, without interrupting the current turn.
 */
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SteerCommand implements CommandHandler {

    private final AgentBackendClient backendClient;

    

    @Override
    public String name() {
        return "steer";
    }

    @Override
    public String description() {
        return "Inject a mid-run note to the agent";
    }

    @Override
    public String handle(UpdateEvent event, BotSessionEntity session) {
        String args = event.commandArgs();
        if (args == null || args.isBlank()) {
            return "Usage: /steer <prompt>\n"
                + "The text is appended to the next tool result, giving the agent new context mid-task.";
        }
        if (session == null || session.getId() == null) {
            return "No active session to steer.";
        }
        boolean accepted = backendClient.steer(session.getId().toString(), args.trim());
        if (accepted) {
            return "Steer note injected: \"" + args.trim() + "\"\n"
                + "It will be delivered after the next tool call completes.";
        }
        return "Failed to inject steer note. No active turn or backend error.";
    }
}