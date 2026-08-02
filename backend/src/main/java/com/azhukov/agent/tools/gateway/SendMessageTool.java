package com.azhukov.agent.tools.gateway;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.AgentTool;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.tools.ToolParam;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@AgentTool(
    name = "send_message",
    description = "Send a message to a user via an external platform (e.g. telegram). Requires platform, chatId, and text.",
    toolset = "gateway"
)
public class SendMessageTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setVisibility(new VisibilityChecker.Std(JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY));

    public record SendMessageArgs(
        @ToolParam(description = "Target platform: telegram, whatsapp, slack, etc.") String platform,
        @ToolParam(description = "Platform-specific chat identifier") String chatId,
        @ToolParam(description = "Message text to send") String text
    ) {}

    private final ObjectProvider<GatewayRoutingService> gatewayProvider;

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            SendMessageArgs args = ToolHandler.parseJson(arguments, SendMessageArgs.class);
            if (args.platform == null || args.platform.isBlank() || args.chatId == null || args.chatId.isBlank() || args.text == null || args.text.isBlank()) {
                return ToolResult.fail("Missing required argument: platform, chatId, and text are required");
            }
            GatewayRoutingService gateway = gatewayProvider.getIfAvailable();
            if (gateway == null) {
                return ToolResult.fail("Gateway routing service is not available");
            }
            Platform platform = Platform.valueOf(args.platform.toUpperCase());
            SessionSource target = new SessionSource(platform, args.chatId, null, null, null);
            SendResult result = gateway.send(platform, target, args.text).get();
            return result.success() ? ToolResult.ok(result.messageId()) : ToolResult.fail(result.error() != null ? result.error() : "send failed");
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }
}
