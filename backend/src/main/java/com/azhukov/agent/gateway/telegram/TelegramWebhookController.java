package com.azhukov.agent.gateway.telegram;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.config.AgentProperties.TelegramProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.MessageType;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.SessionSource;
import com.azhukov.agent.service.GatewayConfigWriter;
import com.azhukov.agent.service.ProfileRuntimeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/gateway/telegram")
@ConditionalOnProperty(name = "agent.gateway.telegram.webhook.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TelegramWebhookController {
    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";
    private static final Pattern NAMED_CALLBACK = Pattern.compile("/gateway/telegram/(?!default$)[a-z0-9][a-z0-9_-]{0,63}");

    private final GatewayRoutingService routingService;
    private final AgentProperties properties;
    private final GatewayConfigWriter gateway;
    private final ProfileRuntimeRegistry runtime;

    /** Only the exact signed callback uses its own authentication boundary. */
    public static boolean isNamedCallback(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (path != null && context != null && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path != null && NAMED_CALLBACK.matcher(path).matches();
    }

    @PostMapping
    public ResponseEntity<String> receive(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody Map<String, Object> update) {
        return dispatch("default", properties.getGateway().getTelegram(), secret, update);
    }

    @PostMapping("/{profile}")
    public ResponseEntity<String> receiveNamed(
            @PathVariable String profile,
            @RequestHeader(value = SECRET_HEADER, required = false) String secret,
            @RequestBody Map<String, Object> update) {
        if (!NAMED_CALLBACK.matcher("/gateway/telegram/" + profile).matches()) {
            return forbidden();
        }
        TelegramProperties settings;
        try {
            settings = gateway.profileTelegram(profile);
        } catch (IOException | IllegalArgumentException e) {
            return forbidden();
        }
        if (settings.getBotToken().isBlank()) {
            return forbidden();
        }
        return dispatch(profile, settings, secret, update);
    }

    private ResponseEntity<String> dispatch(String profile, TelegramProperties settings, String secret, Map<String, Object> update) {
        if (!validSecret(settings.getWebhookSecret(), secret)) {
            return forbidden();
        }
        if (!"default".equals(profile) && !runtime.acceptsRuns(profile)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Profile is stopped");
        }
        if (!(update.get("message") instanceof Map<?, ?> message)) {
            return ResponseEntity.ok("OK");
        }
        long chatId = fieldId(message.get("chat"));
        long userId = fieldId(message.get("from"));
        String username = message.get("from") instanceof Map<?, ?> user && user.get("username") instanceof String name
            ? name : "";
        if (chatId == 0 || userId <= 0 || !GatewayConfigWriter.isAllowed(settings, Long.toString(userId), username)) {
            return forbidden();
        }
        if (!(message.get("text") instanceof String content) || content.isBlank()) {
            return ResponseEntity.ok("OK");
        }
        String updateId = update.get("update_id") instanceof Number id ? String.valueOf(id.longValue()) : null;
        if (updateId == null && !"default".equals(profile)) {
            return ResponseEntity.badRequest().body("Missing Telegram update ID");
        }
        String thread = message.get("message_thread_id") instanceof Number id ? String.valueOf(id.longValue()) : null;
        var source = new SessionSource(Platform.TELEGRAM, String.valueOf(chatId), String.valueOf(userId),
            username, username, thread, profile);
        routingService.dispatchInbound(new MessageEvent(updateId, source, MessageType.TEXT, content,
            List.of(), Map.of(), Instant.now()));
        return ResponseEntity.ok("OK");
    }

    private static long fieldId(Object value) {
        return value instanceof Map<?, ?> fields && fields.get("id") instanceof Number id ? id.longValue() : 0;
    }

    private static boolean validSecret(String expected, String supplied) {
        return expected != null && !expected.isBlank() && supplied != null
            && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<String> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("FORBIDDEN");
    }
}
