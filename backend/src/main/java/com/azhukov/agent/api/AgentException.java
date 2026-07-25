package com.azhukov.agent.api;

import org.springframework.http.HttpStatus;

public class AgentException extends RuntimeException {

    private final HttpStatus status;

    public AgentException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
