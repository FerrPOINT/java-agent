package com.azhukov.agent.cli;

/**
 * m27: internal REPL-exit signal thrown by /exit and /quit instead of
 * System.exit(0) — lets CliReplRunner break the loop gracefully (C4
 * session-save-on-exit and other shutdown hooks still run).
 */
public class ExitCliException extends RuntimeException {
    public ExitCliException(String message) {
        super(message);
    }
}
