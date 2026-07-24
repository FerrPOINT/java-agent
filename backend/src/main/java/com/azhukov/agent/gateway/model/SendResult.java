package com.azhukov.agent.gateway.model;

public record SendResult(boolean success, String messageId, String error) {}
