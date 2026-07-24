package com.azhukov.agent.core.security;

public interface Redactor {

    String redact(String output);

    String redactEnvVars(String output);
}
