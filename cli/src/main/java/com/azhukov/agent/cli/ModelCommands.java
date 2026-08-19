package com.azhukov.agent.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * c8: Model and runtime-mode slash commands.
 * <p>
 * Includes: model, handoff, reasoning, fast, voice.
 */
@Component
@RequiredArgsConstructor
public class ModelCommands implements CommandGroup {

    private final CliState cliState;

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        registry.register("model", "Show or change the current model (e.g. /model gpt-4o [provider])", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return client.getCurrentModel(sessionId);
            }
            String[] parts = args.split("\\s+");
            String model = parts[0];
            String provider = parts.length > 1 ? parts[1] : null;
            return client.switchModel(sessionId, model, provider);
        });

        registry.register("handoff", "Hand off to a different model: /handoff <model> [provider]", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /handoff <model> [provider]";
            String[] parts = args.split("\\s+");
            String model = parts[0];
            String provider = parts.length > 1 ? parts[1] : null;
            return client.handoffModel(sessionId, model, provider);
        });

        registry.register("reasoning", "Manage reasoning effort: /reasoning [none|minimal|low|medium|high|xhigh]", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return "Current reasoning effort: " + cliState.getReasoningEffort() + "\n" +
                    "Levels: " + String.join(", ", cliState.getValidReasoningLevels()) + "\n" +
                    "Use /reasoning <level> to set, or /reasoning cycle to cycle.";
            }
            if ("cycle".equalsIgnoreCase(args.strip())) {
                String level = cliState.cycleReasoningEffort();
                client.setReasoningEffort(sessionId, level);
                return "Reasoning effort: " + level;
            }
            if (!cliState.setReasoningEffortIfValid(args.strip())) {
                return "Invalid level: " + args + "\nValid levels: " +
                    String.join(", ", cliState.getValidReasoningLevels());
            }
            String level = cliState.getReasoningEffort();
            client.setReasoningEffort(sessionId, level);
            return "Reasoning effort: " + level;
        });

        registry.register("fast", "Toggle fast mode", (args, client, sessionId) -> {
            boolean fast = cliState.toggleFastMode();
            client.setFastMode(sessionId, fast);
            return "Fast mode: " + (fast ? "ON ⚡" : "OFF");
        });

        registry.register("voice", "Toggle voice mode: /voice [on|off|tts|status]", (args, client, sessionId) -> {
            String arg = args.strip().toLowerCase();
            switch (arg) {
                case "on" -> {
                    cliState.setVoiceMode(true);
                    client.setVoiceMode(sessionId, true);
                    return "Voice mode: ON";
                }
                case "off" -> {
                    cliState.setVoiceMode(false);
                    client.setVoiceMode(sessionId, false);
                    return "Voice mode: OFF";
                }
                case "tts" -> {
                    boolean tts = !cliState.isTtsEnabled();
                    cliState.setTtsEnabled(tts);
                    return "TTS: " + (tts ? "ON" : "OFF");
                }
                case "status", "" -> {
                    return "Voice mode: " + (cliState.isVoiceMode() ? "ON" : "OFF") + "\n" +
                        "TTS: " + (cliState.isTtsEnabled() ? "ON" : "OFF");
                }
                default -> {
                    return "Usage: /voice [on|off|tts|status]";
                }
            }
        });
    }
}