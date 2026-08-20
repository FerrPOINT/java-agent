package com.azhukov.agent.cli;

/**
 * A slash command that can be executed in the CLI REPL.
 * <p>
 * Each command receives its raw args string, the backend client,
 * and the current session ID.
 */
@FunctionalInterface
public interface SlashCommand {

    /**
     * Execute the command.
     *
     * @param args      raw argument string (may be empty)
     * @param client    the backend REST client
     * @param sessionId the current session ID
     * @return output text to display to the user
     */
    String execute(String args, BackendClient client, String sessionId);
}