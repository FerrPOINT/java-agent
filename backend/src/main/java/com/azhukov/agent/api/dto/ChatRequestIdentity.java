package com.azhukov.agent.api.dto;

import com.azhukov.agent.core.security.UserContext;

/** Builds a request whose user identity is bound to the authenticated principal. */
public final class ChatRequestIdentity {

    private ChatRequestIdentity() {}

    /**
     * A non-admin API key cannot override userId supplied in the JSON body.
     * Admin gateways retain their supplied identity, required for Telegram
     * sender propagation through the backend.
     */
    public static ChatRequest bind(ChatRequest request) {
        String userId = UserContext.effectiveUserId(request.userId());
        if (java.util.Objects.equals(userId, request.userId())) return request;
        return new ChatRequest(
            request.sessionId(), request.message(), request.delegationDepth(), request.timeoutMs(),
            request.model(), request.reasoningEffort(), request.fastMode(), request.voiceMode(),
            request.personality(), request.enabledTools(), request.disabledTools(), request.queuedPrompt(),
            request.subgoal(), request.cdpUrl(), request.goal(), userId, request.username(),
            request.firstName(), request.languageCode(), request.chatType());
    }
}