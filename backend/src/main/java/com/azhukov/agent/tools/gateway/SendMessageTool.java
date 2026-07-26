package com.azhukov.agent.tools.gateway;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.tools.ToolHandler;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class SendMessageTool implements ToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setVisibility(new VisibilityChecker.Std(JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY, JsonAutoDetect.Visibility.ANY));

    private final GatewayRoutingService gateway;

    public SendMessageTool(GatewayRoutingService gateway) {
        this.gateway = gateway;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String arguments, Message lastAssistant, Session session) {
        try {
            Map<String, Object> args = MAPPER.readValue(arguments, Map.class);
            Platform platform = Platform.valueOf(String.valueOf(args.get("platform")).toUpperCase());
            String chatId = String.valueOf(args.get("chatId"));
            String text = String.valueOf(args.get("text"));
            SessionSource target = new SessionSource(platform, chatId, null, null, null);
            SendResult result = gateway.send(platform, target, text).get();
            return result.success() ? ToolResult.ok(result.messageId()) : ToolResult.fail(result.error() != null ? result.error() : "send failed");
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage());
        }
    }
}
