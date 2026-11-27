package com.azhukov.agent.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Register commands backed by profile-scoped server operations. */
final class ProfileCliCommands {
    static final Set<String> LOCAL = Set.of("help", "exit", "quit", "clear", "redraw", "verbose");

    private ProfileCliCommands() {}

    static void register(SlashCommandRegistry registry, ProfileCliClient client, CliProperties settings) {
        registry.register("profile", "Show the selected profile and CLI state directory", (args, standalone, session) ->
            "Active profile: " + settings.getProfile() + "\nCLI state: " + settings.stateDirectory());
        registry.register("version", "Show CLI version info", (args, standalone, session) ->
            "Java Agent CLI " + ProfileCliCommands.class.getPackage().getImplementationVersion()
                + "\nJava " + Runtime.version().feature());
        registry.register("new", "Create a session in the selected profile", (args, standalone, session) -> {
            if (!args.isBlank()) return "Use /resume <session-id> to open an existing session.";
            return switchSession(registry, client.createSession());
        });
        registry.register("resume", "Open a session by ID", (args, standalone, session) -> {
            if (args.isBlank()) return standalone.prettyPrint(client.sessions());
            return client.ownsSession(args) ? switchSession(registry, args) : "Session not found in this profile.";
        });
        registry.register("sessions", "List sessions in the selected profile", (args, standalone, session) ->
            standalone.prettyPrint(client.sessions()));
        registry.register("history", "Show messages in the current session", (args, standalone, session) ->
            standalone.prettyPrint(client.history(session)));
        registry.register("save", "Save the current session", (args, standalone, session) ->
            switchSession(registry, session));
        registry.register("status", "Show profile and session readiness", (args, standalone, session) -> {
            var info = client.capabilities();
            return "Profile: " + info.path("profile").asText() + "\nSession: " + session + "\nBackend: UP";
        });
        registry.register("model", "Show the model configured for this profile", (args, standalone, session) ->
            args.isBlank() ? client.capabilities().path("model").asText()
                : "Change the model in the Java Agent profile configuration.");
        registry.register("skills", "List skills visible to this profile", (args, standalone, session) ->
            standalone.prettyPrint(client.skills()));
        registry.register("background", "Start a run and return to the prompt", (args, standalone, session) ->
            args.isBlank() ? "Usage: /background <prompt>" : "Run: " + client.submit(session, args));
        registry.register("wait", "Read the result of the current run", (args, standalone, session) ->
            await(client, client.currentRun(session)));
        registry.register("retry", "Retry acceptance with the original request key", (args, standalone, session) ->
            await(client, client.retry(session)));
        registry.register("events", "Replay run events after a sequence number", (args, standalone, session) ->
            standalone.prettyPrint(client.events(client.currentRun(session), args.isBlank() ? 0 : Long.parseLong(args))));
        registry.register("stop", "Stop the current run", (args, standalone, session) ->
            standalone.prettyPrint(client.control(client.currentRun(session), "stop", Map.of())));
        registry.register("steer", "Send input to the current run", (args, standalone, session) ->
            args.isBlank() ? "Usage: /steer <message>" : standalone.prettyPrint(
                client.control(client.currentRun(session), "steer", Map.of("input", args))));
        registry.register("approvals", "Read the current run's approval request", (args, standalone, session) ->
            standalone.prettyPrint(client.status(client.currentRun(session)).path("approval")));
        for (String command : List.of("approve", "deny")) {
            registry.register(command, "Resolve an approval for the current run", (args, standalone, session) -> {
                String choice = command.equals("approve") ? "once" : "deny";
                Map<String, Object> body = switch (args) {
                    case "" -> Map.of("choice", choice);
                    case "all" -> Map.of("choice", choice, "all", true);
                    default -> Map.of("choice", choice, "request_id", args);
                };
                return standalone.prettyPrint(client.control(client.currentRun(session), "approval", body));
            });
        }
        registry.register("cron", "List jobs in the selected profile", (args, standalone, session) ->
            standalone.prettyPrint(client.cron()));
        registry.register("cron-create", "Create a job: /cron-create <name> <schedule> <prompt>",
            (args, standalone, session) -> {
                String[] parts = args.split("\\s+", 3);
                return parts.length < 3 ? "Usage: /cron-create <name> <schedule> <prompt>"
                    : standalone.prettyPrint(client.createCron(parts[0], parts[1], parts[2]));
            });
        for (String command : List.of("pause", "resume", "run")) {
            registry.register("cron-" + command, command + " a job by ID", (args, standalone, session) ->
                args.isBlank() ? "Usage: /cron-" + command + " <job-id>"
                    : standalone.prettyPrint(client.cronAction(args, command)));
        }
        registry.register("cron-delete", "Delete a job by ID", (args, standalone, session) -> {
            if (args.isBlank()) return "Usage: /cron-delete <job-id>";
            client.deleteCron(args);
            return "Cron job deleted: " + args;
        });
        registry.registerAlias("reset", "new");
    }

    private static String switchSession(SlashCommandRegistry registry, String id) {
        try {
            registry.getSessionStore().saveActiveSession(id);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save the profile session.", e);
        }
        registry.getSessionStore().recordSession(id, null);
        registry.getCliState().setCurrentSessionId(id);
        return "Session: " + id;
    }

    private static String await(ProfileCliClient client, String run) {
        StringBuilder result = new StringBuilder();
        String status = client.awaitRun(run, result::append, tool -> {});
        if (!status.isEmpty()) {
            if (!result.isEmpty()) result.append('\n');
            result.append(status);
        }
        return result.toString();
    }
}
