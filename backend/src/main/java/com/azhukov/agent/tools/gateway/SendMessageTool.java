package com.azhukov.agent.tools.gateway;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.GatewayTargetResolver;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.persistence.entity.OutboundMessageReceiptEntity;
import com.azhukov.agent.service.GatewayHomeChannelService;
import com.azhukov.agent.service.OutboundReceiptService;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@AgentTool(
    name = "send_message",
    description = "Send a message to a connected messaging platform. Targets: 'platform:chat_id', 'platform:chat_id:thread_id' (forum topic), or bare 'platform' for the persisted home channel. Actions: send (default), list, react, unreact. Reactions without message_id target the most recent outbound message.",
    toolset = "gateway"
)
public class SendMessageTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setVisibility(new VisibilityChecker.Std(JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY));

    public record SendMessageArgs(
        @ToolParam(description = "Action to perform: send (default), list, react, or unreact.", required = false) String action,
        @ToolParam(description = "Delivery target: platform:chat_id, platform:chat_id:thread_id, or bare platform for the home channel.", required = false) String target,
        @ToolParam(description = "Message text to send.", required = false) @JsonAlias("text") String message,
        @ToolParam(description = "Legacy target platform: telegram, discord, or web.", required = false) String platform,
        @ToolParam(description = "Legacy platform-specific chat identifier.", required = false) @JsonProperty("chat_id") @JsonAlias("chatId") String chatId,
        @ToolParam(description = "For action='react': emoji to attach as a reaction.", required = false) String emoji,
        @ToolParam(description = "For action='react'/'unreact': platform message id. When omitted, the most recent outbound message for the target is used.", required = false) @JsonProperty("message_id") @JsonAlias("messageId") String messageId
    ) {}

    private final ObjectProvider<GatewayRoutingService> gatewayProvider;
    private final ObjectProvider<GatewayTargetResolver> targetResolverProvider;
    private final ObjectProvider<OutboundReceiptService> receiptProvider;
    private final ObjectProvider<GatewayHomeChannelService> homeChannelProvider;

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
        UUID sessionId = session == null ? null : session.id();
        return switch (action) {
            case "send" -> handleSend(args, sessionId);
            case "list" -> handleList();
            case "react" -> handleReaction(args, false, sessionId);
            case "unreact" -> handleReaction(args, true, sessionId);
            default -> jsonError("Unknown send_message action: " + action);
        };
    }

    private ToolResult handleSend(SendMessageArgs args, UUID sessionId) {
        String message = stripToNull(args.message());
        if (message == null) {
            return jsonError("Both 'target' and 'message' are required when action='send'. Legacy platform/chatId/text is still accepted.");
        }
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

        SessionSource target = new SessionSource(platform, resolved.chatId(), null, null, null, resolved.threadId());
        try {
            SendResult result = gateway.send(platform, target, message, sessionId).get();
            if (!result.success()) {
                return jsonError(result.error() != null ? result.error() : "send failed");
            }
            ObjectNode response = MAPPER.createObjectNode();
            response.put("success", true);
            response.put("platform", resolved.platform());
            response.put("chat_id", resolved.chatId());
            if (resolved.threadId() != null) {
                response.put("thread_id", resolved.threadId());
            }
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

    private ToolResult handleReaction(SendMessageArgs args, boolean remove, UUID sessionId) {
        String emoji = stripToNull(args.emoji());
        if (!remove && emoji == null) {
            return jsonError("Both 'target' and 'emoji' are required when action='react'. Legacy platform/chatId is still accepted as the target.");
        }
        ResolvedTarget resolved = resolveTarget(args);
        if (resolved.error() != null) {
            String action = remove ? "unreact" : "react";
            String required = remove ? "'target' is required" : "Both 'target' and 'emoji' are required";
            return jsonError(required + " when action='" + action + "'. Legacy platform/chatId is still accepted.");
        }
        String messageId = stripToNull(args.messageId());
        if (messageId == null) {
            // Hermes parity: fall back to the most recent outbound message for
            // the resolved target (persisted receipts, ADR-012).
            ResolvedLatest latest = resolveLatestMessage(resolved);
            if (latest.error() != null) {
                return jsonError(latest.error());
            }
            messageId = latest.messageId();
        }

        GatewayRoutingService gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            return jsonError("Gateway routing service is not available");
        }
        Platform platform = parsePlatform(resolved.platform());
        if (platform == null) {
            return jsonError("Unknown platform: " + resolved.platform());
        }

        SessionSource target = new SessionSource(platform, resolved.chatId(), null, null, null, resolved.threadId());
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
        GatewayHomeChannelService homeChannels =
            homeChannelProvider == null ? null : homeChannelProvider.getIfAvailable();
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode targets = response.putArray("targets");
        for (Platform platform : Platform.values()) {
            if (platform == Platform.UNKNOWN || gateway.adapterFor(platform).isEmpty()) {
                continue;
            }
            ObjectNode target = targets.addObject();
            String name = platform.name().toLowerCase(Locale.ROOT);
            target.put("platform", name);
            Optional<GatewayHomeChannelService.HomeTarget> home =
                homeChannels == null ? Optional.empty()
                    : homeChannels.resolve(name, GatewayHomeChannelService.DEFAULT_PROFILE);
            if (home.isPresent()) {
                target.put("target", name + ":" + home.get().chatId()
                    + (home.get().threadId() != null ? ":" + home.get().threadId() : ""));
                target.put("home", true);
                if (home.get().name() != null) {
                    target.put("name", home.get().name());
                }
            } else {
                target.put("target", name + ":<chat_id>");
                target.put("requires_explicit_chat_id", true);
            }
        }
        response.put("count", targets.size());
        if (targets.isEmpty()) {
            response.put("note", "No registered Java gateway adapters are available.");
        } else {
            response.put("note", "Targets: platform:chat_id, platform:chat_id:thread_id, or bare platform (home channel).");
        }
        return ToolResult.ok(response.toString());
    }

    private ResolvedLatest resolveLatestMessage(ResolvedTarget resolved) {
        OutboundReceiptService receipts = receiptProvider.getIfAvailable();
        if (receipts == null) {
            return ResolvedLatest.error("message_id is required for reactions: no outbound receipt store is available.");
        }
        Optional<OutboundMessageReceiptEntity> latest =
            receipts.lastMessageFor(resolved.platform(), resolved.chatId(), resolved.threadId());
        if (latest.isEmpty()) {
            return ResolvedLatest.error("No recent outbound message for " + resolved.platform()
                + ":" + resolved.chatId() + "; provide an explicit message_id.");
        }
        return new ResolvedLatest(latest.get().getMessageId(), null);
    }

    private ResolvedSend resolveSendTarget(SendMessageArgs args) {
        ResolvedTarget target = resolveTarget(args);
        if (target.error() != null) {
            return ResolvedSend.error(target.error());
        }
        String message = stripToNull(args.message());
        if (message == null) {
            return ResolvedSend.error("Both 'target' and 'message' are required when action='send'. Legacy platform/chatId/text is still accepted.");
        }
        return new ResolvedSend(target.platform(), target.chatId(), target.threadId(), message, null);
    }

    private ResolvedTarget resolveTarget(SendMessageArgs args) {
        String platform = stripToNull(args.platform());
        String chatId = stripToNull(args.chatId());
        String threadId = null;
        String target = stripToNull(args.target());

        if (target != null) {
            // Unified resolution through the gateway target resolver (WP-2):
            // platform:chat_id, platform:chat_id:thread_id, bare platform home.
            GatewayTargetResolver resolver = targetResolverProvider.getIfAvailable();
            if (resolver == null) {
                return ResolvedTarget.error("Gateway target resolver is not available");
            }
            Optional<GatewayTargetResolver.ResolvedTarget> resolved = resolver.resolve(target);
            if (resolved.isEmpty()) {
                return ResolvedTarget.error("Unknown or unresolvable target '" + target
                    + "'. Accepted: platform:chat_id, platform:chat_id:thread_id, bare platform (home channel).");
            }
            GatewayTargetResolver.ResolvedTarget rt = resolved.get();
            return new ResolvedTarget(
                rt.platform().name().toLowerCase(Locale.ROOT), rt.chatId(), rt.threadId(), null);
        }

        if (platform == null) {
            return ResolvedTarget.error("Both 'target' and 'message' are required when action='send'. Legacy platform/chatId/text is still accepted.");
        }
        if (chatId == null) {
            // Bare legacy platform: resolve the home channel.
            GatewayTargetResolver resolver = targetResolverProvider.getIfAvailable();
            if (resolver == null) {
                return ResolvedTarget.error("Gateway target resolver is not available");
            }
            Platform parsed = parsePlatform(platform);
            if (parsed == null) {
                return ResolvedTarget.error("Unknown platform: " + platform);
            }
            Optional<GatewayTargetResolver.ResolvedTarget> home = resolver.resolveHome(parsed);
            if (home.isEmpty()) {
                return ResolvedTarget.error("No home channel is persisted for " + platform
                    + "; use target='" + platform + ":chat_id' or run /set_home in the chat.");
            }
            GatewayTargetResolver.ResolvedTarget rt = home.get();
            return new ResolvedTarget(
                rt.platform().name().toLowerCase(Locale.ROOT), rt.chatId(), rt.threadId(), null);
        }
        return new ResolvedTarget(platform.toLowerCase(Locale.ROOT), chatId, threadId, null);
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

    private record ResolvedSend(String platform, String chatId, String threadId, String message, String error) {
        static ResolvedSend error(String error) {
            return new ResolvedSend(null, null, null, null, error);
        }
    }

    private record ResolvedTarget(String platform, String chatId, String threadId, String error) {
        static ResolvedTarget error(String error) {
            return new ResolvedTarget(null, null, null, error);
        }
    }

    private record ResolvedLatest(String messageId, String error) {
        static ResolvedLatest error(String error) {
            return new ResolvedLatest(null, error);
        }
    }
}
