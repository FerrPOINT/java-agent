package com.azhukov.agent.tools.gateway;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
@AgentTool(
    name = "send_message",
    description = "Send a message to a connected messaging platform. Provide the platform name (e.g. 'telegram') and the platform-specific chat identifier, plus the message text.",
    toolset = "gateway"
)
public class SendMessageTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setVisibility(new VisibilityChecker.Std(JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY));

    public record SendMessageArgs(
        @ToolParam(description = "Action to perform: send (default), list, react, or unreact.", required = false) String action,
        @ToolParam(description = "Hermes delivery target: platform:chat_id. Bare platform home-channel delivery is not implemented in the Java gateway yet.", required = false) String target,
        @ToolParam(description = "Message text to send.", required = false) @JsonAlias("text") String message,
        @ToolParam(description = "Legacy target platform: telegram, discord, or web.", required = false) String platform,
        @ToolParam(description = "Legacy platform-specific chat identifier.", required = false) @JsonProperty("chat_id") @JsonAlias("chatId") String chatId,
        @ToolParam(description = "For action='react': emoji to attach as a reaction.", required = false) String emoji,
        @ToolParam(description = "For action='react'/'unreact': platform message id to update. Java requires this explicitly because it has no live recent-message resolver yet.", required = false) @JsonProperty("message_id") @JsonAlias("messageId") String messageId
    ) {}

    private final ObjectProvider<GatewayRoutingService> gatewayProvider;

    @Override
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        SendMessageArgs args;
        try {
            args = ToolHandler.parseJson(arguments, SendMessageArgs.class);
        } catch (IllegalArgumentException e) {
            return jsonError(e.getMessage());
        }

        String action = args.action() == null || args.action().isBlank()
            ? "send"
            : args.action().trim().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "send" -> handleSend(args);
            case "list" -> handleList();
            case "react" -> handleReaction(args, false);
            case "unreact" -> handleReaction(args, true);
            default -> jsonError("Unknown send_message action: " + action);
        };
    }

    private ToolResult handleSend(SendMessageArgs args) {
        ResolvedSend resolved = resolveSendTarget(args);
        if (resolved.error() != null) {
            return jsonError(resolved.error());
        }

        GatewayRoutingService gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return jsonError("Gateway routing service is not available");
        }

        Platform platform = parsePlatform(resolved.platform());
        if (platform == null) {
            return jsonError("Unknown platform: " + resolved.platform());
        }

        SessionSource target = new SessionSource(platform, resolved.chatId(), null, null, null);
        try {
            SendResult result = gateway.send(platform, target, resolved.message()).get();
            if (!result.success()) {
                return jsonError(result.error() != null ? result.error() : "send failed");
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("success", true);
            response.put("platform", resolved.platform());
            response.put("chat_id", resolved.chatId());
            if (result.messageId() != null && !result.messageId().isBlank()) {
                response.put("message_id", result.messageId());
            }
            return ToolResult.ok(response.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonError("Interrupted");
        } catch (Exception e) {
            return jsonError(e.getMessage());
        }
    }

    private ToolResult handleReaction(SendMessageArgs args, boolean remove) {
        String emoji = stripToNull(args.emoji());
        if (!remove && emoji == null) {
            return jsonError("Both 'target' and 'emoji' are required when action='react'. Legacy platform/chatId is still accepted as the target.");
        }
        ResolvedTarget resolved = resolveTarget(args);
        if (resolved.error() != null) {
            if (!resolved.error().startsWith("Both 'target'")) {
                return jsonError(resolved.error());
            }
            String action = remove ? "unreact" : "react";
            String required = remove ? "'target' is required" : "Both 'target' and 'emoji' are required";
            return jsonError(required + " when action='" + action + "'. Legacy platform/chatId is still accepted.");
        }
        String messageId = stripToNull(args.messageId());
        if (messageId == null) {
            return jsonError("message_id is required for Java gateway reactions; Hermes can target the most recent message only when a live gateway adapter tracks it.");
        }

        GatewayRoutingService gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return jsonError("Gateway routing service is not available");
        }
        Platform platform = parsePlatform(resolved.platform());
        if (platform == null) {
            return jsonError("Unknown platform: " + resolved.platform());
        }

        SessionSource target = new SessionSource(platform, resolved.chatId(), null, null, null);
        try {
            SendResult result = remove
                ? gateway.removeReaction(platform, target, messageId).get()
                : gateway.addReaction(platform, target, emoji, messageId).get();
            if (!result.success()) {
                return jsonError(result.error() != null ? result.error() : "reaction failed");
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("success", true);
            response.put("platform", resolved.platform());
            response.put("chat_id", resolved.chatId());
            response.put("message_id", messageId);
            response.put("action", remove ? "unreact" : "react");
            return ToolResult.ok(response.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonError("Interrupted");
        } catch (Exception e) {
            return jsonError(e.getMessage());
        }
    }

    private ToolResult handleList() {
        GatewayRoutingService gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return jsonError("Gateway routing service is not available");
        }
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode targets = response.putArray("targets");
        for (Platform platform : Platform.values()) {
            if (platform == Platform.UNKNOWN || gateway.adapterFor(platform).isEmpty()) {
                continue;
            }
            ObjectNode target = targets.addObject();
            String name = platform.name().toLowerCase(Locale.ROOT);
            target.put("platform", name);
            target.put("target", name + ":<chat_id>");
            target.put("requires_explicit_chat_id", true);
        }
        response.put("count", targets.size());
        if (targets.isEmpty()) {
            response.put("note", "No registered Java gateway adapters are available.");
        } else {
            response.put("note", "Java gateway listing exposes registered platforms only; channel directory and home-channel resolution are not implemented yet.");
        }
        return ToolResult.ok(response.toString());
    }

    private ResolvedSend resolveSendTarget(SendMessageArgs args) {
        String message = stripToNull(args.message());
        ResolvedTarget target = resolveTarget(args);
        if (target.error() != null) {
            return ResolvedSend.error(target.error());
        }
        if (message == null) {
            return ResolvedSend.error("Both 'target' and 'message' are required when action='send'. Legacy platform/chatId/text is still accepted.");
        }
        return new ResolvedSend(target.platform(), target.chatId(), message, null);
    }

    private ResolvedTarget resolveTarget(SendMessageArgs args) {
        String platform = stripToNull(args.platform());
        String chatId = stripToNull(args.chatId());
        String target = stripToNull(args.target());

        if (target != null) {
            String[] parts = target.split(":", 2);
            platform = stripToNull(parts[0]);
            if (parts.length > 1) {
                String targetRef = stripToNull(parts[1]);
                if (targetRef != null && targetRef.contains(":")) {
                    return ResolvedTarget.error("Thread/topic targets are not implemented in the Java gateway yet; provide a plain platform:chat_id target.");
                }
                chatId = targetRef;
            }
        }

        if (platform == null) {
            return ResolvedTarget.error("Both 'target' and 'message' are required when action='send'. Legacy platform/chatId/text is still accepted.");
        }
        if (chatId == null) {
            return ResolvedTarget.error("No chat specified for " + platform + ". Java gateway requires target='" + platform + ":chat_id' or legacy chatId.");
        }
        return new ResolvedTarget(platform.toLowerCase(Locale.ROOT), chatId, null);
    }

    private static Platform parsePlatform(String platform) {
        try {
            Platform parsed = Platform.valueOf(platform.toUpperCase(Locale.ROOT));
            return parsed == Platform.UNKNOWN ? null : parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.trim();
        return stripped.isEmpty() ? null : stripped;
    }

    private static ToolResult jsonError(String error) {
        String message = error == null || error.isBlank() ? "send failed" : error;
        ObjectNode response = MAPPER.createObjectNode();
        response.put("success", false);
        response.put("error", message);
        return new ToolResult(false, response.toString(), message);
    }

    private record ResolvedSend(String platform, String chatId, String message, String error) {
        static ResolvedSend error(String error) {
            return new ResolvedSend(null, null, null, error);
        }
    }

    private record ResolvedTarget(String platform, String chatId, String error) {
        static ResolvedTarget error(String error) {
            return new ResolvedTarget(null, null, error);
        }
    }
}
