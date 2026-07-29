package com.azhukov.agent.api;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AgentException extends RuntimeException {

    private final HttpStatus status;

    public AgentException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
