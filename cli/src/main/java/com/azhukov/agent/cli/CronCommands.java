package com.azhukov.agent.cli;

import org.springframework.stereotype.Component;

/**
 * c8: Cron job slash commands.
 * <p>
 * Includes: cron, cron-pause, cron-resume, cron-delete, cron-create.
 */
@Component
public class CronCommands implements CommandGroup {

    @Override
    public void registerAll(SlashCommandRegistry registry) {
        registry.register("cron", "List cron jobs", (args, client, sessionId) ->
            client.listCronJobs());

        registry.register("cron-pause", "Pause a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-pause <job-id>";
            return client.pauseCronJob(args.strip());
        });

        registry.register("cron-resume", "Resume a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-resume <job-id>";
            return client.resumeCronJob(args.strip());
        });

        registry.register("cron-delete", "Delete a cron job by ID", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-delete <job-id>";
            return client.deleteCronJob(args.strip());
        });

        registry.register("cron-create", "Create a cron job: /cron-create <name> <schedule> <prompt>", (args, client, sessionId) -> {
            if (args.isBlank()) return "Usage: /cron-create <name> <schedule> <prompt>";
            String[] parts = args.split("\\s+", 3);
            if (parts.length < 3) return "Usage: /cron-create <name> <schedule> <prompt>";
            return client.createCronJob(parts[0], parts[1], parts[2], null);
        });

        // /blueprint (Hermes hermes_cli/blueprint_cmd.py parity): no args = list
        // the catalog; "<key> slot=value …" = fill typed slots and create the
        // cron job directly. Missing slots use their defaults; enum/time slots
        // are validated server-side (BlueprintFillException surfaces as text).
        registry.register("blueprint", "Set up an automation from a blueprint: /blueprint [key] [slot=value ...]", (args, client, sessionId) -> {
            if (args.isBlank()) {
                return client.prettyPrint(client.listBlueprints());
            }
            String[] parts = args.split("\\s+");
            String key = parts[0];
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq <= 0) {
                    return "Invalid slot spec '" + parts[i] + "'. Usage: /blueprint <key> [slot=value ...]";
                }
                values.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
            return client.createFromBlueprint(key, values);
        });
    }
}