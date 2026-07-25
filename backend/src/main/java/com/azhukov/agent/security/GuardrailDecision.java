package com.azhukov.agent.security;

public record GuardrailDecision(
    GuardrailAction action,
    String code,
    String message,
    String toolName
) {
    public static GuardrailDecision allow(String toolName) {
        return new GuardrailDecision(GuardrailAction.ALLOW, null, null, toolName);
    }

    public static GuardrailDecision warn(String toolName, String code, String message) {
        return new GuardrailDecision(GuardrailAction.WARN, code, message, toolName);
    }

    public static GuardrailDecision block(String toolName, String code, String message) {
        return new GuardrailDecision(GuardrailAction.BLOCK, code, message, toolName);
    }

    public static GuardrailDecision halt(String toolName, String code, String message) {
        return new GuardrailDecision(GuardrailAction.HALT, code, message, toolName);
    }

    public boolean isAllow() {
        return action == GuardrailAction.ALLOW;
    }

    public boolean isBlockOrHalt() {
        return action == GuardrailAction.BLOCK || action == GuardrailAction.HALT;
    }
}
