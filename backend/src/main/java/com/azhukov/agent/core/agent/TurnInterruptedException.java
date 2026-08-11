package com.azhukov.agent.core.agent;

/**
 * Thrown when a model stream is interrupted mid-token because the user
 * cancelled the turn.  This causes the streaming latch to release early
 * and stops the model stream.
 */
public class TurnInterruptedException extends RuntimeException {

    public TurnInterruptedException() {
        super("Turn interrupted by user cancellation");
    }

    public TurnInterruptedException(String message) {
        super(message);
    }
}