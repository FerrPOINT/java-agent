package com.azhukov.agent.api;

import com.azhukov.agent.service.GatewayConfigWriter;
import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.telegram.TelegramAdapter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Messaging setup and pairing settings for the installation and named profiles.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Hermes-compatible", description = "Messaging dashboard compatibility")
public class MessagingDashboardController {

    private static final String TELEGRAM = "telegram";
    private static final String WEBHOOK = "webhook";

    private final AgentProperties properties;
    private final GatewayRoutingService gatewayRoutingService;
    private final Environment environment;
    private final GatewayConfigWriter gatewayConfigWriter;
    private final org.springframework.beans.factory.ObjectProvider<com.azhukov.agent.service.GatewayHomeChannelService> gatewayHomeChannelProvider;

    private void requireAdminMessagingAccess() {
        if (UserContext.getUserId() != null && !UserContext.isAdmin()) {
            throw new SecurityException("Telegram profile configuration requires administrator access");
        }
    }

    @PostMapping("/api/messaging/telegram/onboarding/start")
    @Operation(summary = "Reject Telegram QR onboarding not supported by Java port")
    public ResponseEntity<Map<String, Object>> startTelegramOnboarding(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notImplemented("Telegram onboarding is not implemented in the Java port");
    }

    @GetMapping("/api/messaging/telegram/onboarding/{pairingId}")
    @Operation(summary = "Return missing Telegram onboarding session")
    public ResponseEntity<Map<String, Object>> telegramOnboardingStatus(@PathVariable String pairingId) {
        return notFound("Telegram setup session was not found. Start a new setup.");
    }

    @PostMapping("/api/messaging/telegram/onboarding/{pairingId}/apply")
    @Operation(summary = "Reject Telegram onboarding apply without an active setup session")
    public ResponseEntity<Map<String, Object>> applyTelegramOnboarding(
        @PathVariable String pairingId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notFound("Telegram setup session was not found. Start a new setup.");
    }

    @DeleteMapping("/api/messaging/telegram/onboarding/{pairingId}")
    @Operation(summary = "Cancel Telegram onboarding session if one exists")
    public Map<String, Object> cancelTelegramOnboarding(@PathVariable String pairingId) {
        return Map.of("ok", true);
    }

    @PostMapping("/api/messaging/whatsapp/onboarding/start")
    @Operation(summary = "Reject WhatsApp onboarding not supported by Java port")
    public ResponseEntity<Map<String, Object>> startWhatsAppOnboarding(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notImplemented("WhatsApp onboarding is not implemented in the Java port");
    }

    @GetMapping("/api/messaging/whatsapp/onboarding/{pairingId}")
    @Operation(summary = "Return missing WhatsApp onboarding session")
    public ResponseEntity<Map<String, Object>> whatsappOnboardingStatus(@PathVariable String pairingId) {
        return notFound("WhatsApp setup session was not found. Start a new setup.");
    }

    @PostMapping("/api/messaging/whatsapp/onboarding/{pairingId}/apply")
    @Operation(summary = "Reject WhatsApp onboarding apply without an active setup session")
    public ResponseEntity<Map<String, Object>> applyWhatsAppOnboarding(
        @PathVariable String pairingId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return notFound("WhatsApp setup session was not found. Start a new setup.");
    }

    @DeleteMapping("/api/messaging/whatsapp/onboarding/{pairingId}")
    @Operation(summary = "Cancel WhatsApp onboarding session if one exists")
    public Map<String, Object> cancelWhatsAppOnboarding(@PathVariable String pairingId) {
        return Map.of("ok", true);
    }

    @GetMapping("/api/messaging/platforms")
    @Operation(summary = "Return Hermes desktop messaging platform cards")
    public Map<String, Object> platforms(
        @RequestParam(name = "profile", required = false) String profile
    ) {
        if (!GatewayConfigWriter.isDefault(profile)) {
            return Map.of("platforms", List.of(telegramPlatform(profile)));
        }
        return Map.of(
            "env_path", hermesHome().resolve(".env").toString(),
            "gateway_start_command", "java -jar java-agent-backend.jar",
            "platforms", List.of(telegramPlatform(profile), webhookPlatform()));
    }

    @PutMapping("/api/messaging/platforms/{platformId}")
    @Operation(summary = "Write Telegram messaging config (token/allowlist); secrets never round-trip")
    public ResponseEntity<Map<String, Object>> updatePlatform(
        @PathVariable String platformId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        requireAdminMessagingAccess();
        if (!isKnownPlatform(platformId)) {
            return unknownPlatform(platformId);
        }
        if (!TELEGRAM.equals(normalizePlatform(platformId))) {
            return notImplemented("config writes are only implemented for telegram in the Java port");
        }
        try {
            var telegram = gatewayConfigWriter.updateTelegramConfig(
                requestedProfile(body),
                bodyString(body, "bot_token"),
                stringList(body == null ? null : body.get("allowed_user_ids")),
                stringList(body == null ? null : body.get("allowed_usernames")),
                optionalBoolean(body, "allow_by_default"));
            return ResponseEntity.ok(Map.of("ok", true, "platform", TELEGRAM,
                "bot_token_set", telegram.botTokenSet(), "allowed_user_ids", telegram.userIds(),
                "allowed_usernames", telegram.usernames(), "allow_by_default", telegram.allowByDefault()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (java.io.FileNotFoundException e) {
            return notFound("Unknown profile");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", "Telegram profile configuration could not be saved"));
        }
    }

    @PostMapping("/api/messaging/platforms/{platformId}/test")
    @Operation(summary = "Return a Hermes-shaped messaging platform readiness check")
    public ResponseEntity<Map<String, Object>> testPlatform(
        @PathVariable String platformId,
        @RequestParam(name = "profile", required = false) String profile
    ) {
        String normalized = normalizePlatform(platformId);
        if (!isKnownPlatform(normalized)) {
            return unknownPlatform(platformId);
        }
        if (!GatewayConfigWriter.isDefault(profile) && !TELEGRAM.equals(normalized)) {
            return notFound("Messaging platform is unavailable for this profile");
        }
        Map<String, Object> payload = TELEGRAM.equals(normalized) ? telegramPlatform(profile) : webhookPlatform();
        String state = stringValue(payload.get("state"));
        String name = stringValue(payload.get("name"));
        if (!Boolean.TRUE.equals(payload.get("enabled"))) {
            return ResponseEntity.ok(testResponse(false, state,
                name + " is disabled. Enable it, then restart the gateway."));
        }
        if (!Boolean.TRUE.equals(payload.get("configured"))) {
            List<String> missing = missingRequiredEnv(payload);
            String message = missing.isEmpty()
                ? "Platform setup is incomplete."
                : "Missing required setup: " + String.join(", ", missing);
            return ResponseEntity.ok(testResponse(false, state, message));
        }
        if (!Boolean.TRUE.equals(payload.get("gateway_running"))) {
            return ResponseEntity.ok(testResponse(false, state,
                "Gateway is not running. Restart the gateway to connect this platform."));
        }
        if ("connected".equals(state)) {
            return ResponseEntity.ok(testResponse(true, state, name + " is connected."));
        }
        Object errorMessage = payload.get("error_message");
        if (errorMessage instanceof String error && !error.isBlank()) {
            return ResponseEntity.ok(testResponse(false, state, error));
        }
        return ResponseEntity.ok(testResponse(false, state,
            "Setup looks complete, but the gateway has not reported a connection yet. Restart the gateway."));
    }

    @GetMapping("/api/pairing")
    @Operation(summary = "Return static Telegram allowlist in Hermes pairing shape")
    public Map<String, Object> pairing(
        @RequestParam(name = "profile", required = false) String profile
    ) {
        return Map.of(
            "pending", List.of(),
            "approved", approvedPairingUsers(telegramAccess(profile)));
    }

    @PostMapping("/api/pairing/approve")
    @Operation(summary = "Approve a Telegram user into the persisted allowlist")
    public ResponseEntity<Map<String, Object>> approvePairing(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        requireAdminMessagingAccess();
        String platform = bodyString(body, "platform");
        String requestId = bodyString(body, "request_id");
        String code = bodyString(body, "code");
        String userId = bodyString(body, "user_id");
        String userName = bodyString(body, "user_name");
        if (!hasText(platform)) {
            return badRequest("platform is required");
        }
        String normalized = normalizePlatform(platform);
        if (!TELEGRAM.equals(normalized)) {
            return badRequest("Pairing approvals are only supported for telegram in the Java port");
        }
        // Value may arrive as user_id, code (pairing code = the id itself in
        // the Telegram allowlist model), or user_name (@handle).
        String value = hasText(userId) ? userId : hasText(code) ? code : userName;
        if (!hasText(value) && !hasText(requestId)) {
            return badRequest("user_id, code or request_id is required");
        }
        try {
            String target = hasText(value) ? value : requestId;
            var result = gatewayConfigWriter.approveTelegramUser(requestedProfile(body), target);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("platform", TELEGRAM);
            payload.put("user_id", result.user());
            payload.put("added", result.changed());
            payload.put("approved", approvedPairingUsers(result.access()));
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (java.io.FileNotFoundException e) {
            return notFound("Unknown profile");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", "Telegram profile configuration could not be saved"));
        }
    }

    @PostMapping("/api/pairing/revoke")
    @Operation(summary = "Revoke a Telegram user from the persisted allowlist")
    public ResponseEntity<Map<String, Object>> revokePairing(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        requireAdminMessagingAccess();
        if (!hasText(bodyString(body, "platform")) || !hasText(bodyString(body, "user_id"))) {
            return badRequest("platform and user_id are required");
        }
        if (!TELEGRAM.equals(normalizePlatform(bodyString(body, "platform")))) {
            return badRequest("Pairing revokes are only supported for telegram in the Java port");
        }
        try {
            var result = gatewayConfigWriter.revokeTelegramUser(requestedProfile(body), bodyString(body, "user_id"));
            boolean removed = result.changed();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", removed);
            payload.put("platform", TELEGRAM);
            payload.put("approved", approvedPairingUsers(result.access()));
            if (!removed) {
                payload.put("detail", "User was not in the allowlist");
            }
            return ResponseEntity.ok(payload);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (java.io.FileNotFoundException e) {
            return notFound("Unknown profile");
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("detail", "Telegram profile configuration could not be saved"));
        }
    }

    @PostMapping("/api/pairing/clear-pending")
    @Operation(summary = "Return empty pending-pairing cleanup result")
    public Map<String, Object> clearPendingPairing(
        @RequestParam(name = "profile", required = false) String profile
    ) {
        return Map.of("ok", true, "cleared", 0);
    }

    @GetMapping("/api/webhooks")
    @Operation(summary = "Return the Telegram webhook subscription state (signed-callback contract)")
    public Map<String, Object> webhooks() {
        boolean enabled = hasText(properties.getGateway().getTelegram().getWebhookUrl());
        String baseUrl = properties.getGateway().getTelegram().getWebhookUrl();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", enabled);
        payload.put("base_url", baseUrl == null ? "" : baseUrl);
        List<Map<String, Object>> subscriptions = new ArrayList<>();
        if (enabled) {
            Map<String, Object> subscription = new LinkedHashMap<>();
            subscription.put("name", "telegram");
            subscription.put("platform", TELEGRAM);
            subscription.put("url", baseUrl);
            subscription.put("secret_set", hasText(properties.getGateway().getTelegram().getWebhookSecret()));
            subscription.put("enabled", true);
            subscriptions.add(subscription);
        }
        payload.put("subscriptions", subscriptions);
        return payload;
    }

    @PostMapping("/api/webhooks/enable")
    @Operation(summary = "Reject dashboard webhook enablement")
    public ResponseEntity<Map<String, Object>> enableWebhooks() {
        return notImplemented("webhook subscription management is not implemented in the Java port");
    }

    @PostMapping("/api/webhooks")
    @Operation(summary = "Reject dashboard webhook subscription creation")
    public ResponseEntity<Map<String, Object>> createWebhook(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String name = body != null && body.get("name") instanceof String rawName ? rawName : "";
        if (!hasText(name)) {
            return badRequest("name is required");
        }
        return badRequest("Webhook platform is not enabled. Enable it from the Webhooks page first.");
    }

    @DeleteMapping("/api/webhooks/{name}")
    @Operation(summary = "Reject dashboard webhook subscription deletion")
    public ResponseEntity<Map<String, Object>> deleteWebhook(@PathVariable String name) {
        return webhookSubscriptionNotFound(name);
    }

    @PutMapping("/api/webhooks/{name}/enabled")
    @Operation(summary = "Reject dashboard webhook subscription enablement changes")
    public ResponseEntity<Map<String, Object>> setWebhookEnabled(
        @PathVariable String name,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return webhookSubscriptionNotFound(name);
    }

    private Map<String, Object> telegramPlatform(String profile) {
        if (!GatewayConfigWriter.isDefault(profile)) {
            var telegram = telegramAccess(profile);
            Map<String, Object> platform = platformBase(TELEGRAM, "Telegram", "Use Java agent from Telegram chats.",
                "https://core.telegram.org/bots/features", false, telegram.botTokenSet(), false, "not_connected");
            platform.put("env_vars", List.of(
                envVarStatus("TELEGRAM_BOT_TOKEN", "Telegram bot token", "Bot token", true, true, telegram.botTokenSet()),
                envVarStatus("TELEGRAM_ALLOWED_USERS", "Allowed Telegram user IDs", "Allowed users",
                    false, false, !telegram.userIds().isEmpty())));
            return platform;
        }
        boolean enabled = telegramEnabled();
        boolean configured = hasText(properties.getGateway().getTelegram().getBotToken())
            || hasText(environment.getProperty("TELEGRAM_BOT_TOKEN", ""));
        boolean connected = telegramAdapterConnected();
        String state;
        if (!enabled) {
            state = "disabled";
        } else if (!configured) {
            state = "not_configured";
        } else if (connected) {
            state = "connected";
        } else {
            state = "pending_restart";
        }

        Map<String, Object> platform = platformBase(
            TELEGRAM,
            "Telegram",
            "Use Java agent from Telegram chats.",
            "https://core.telegram.org/bots/features",
            enabled,
            configured,
            true,
            state);
        platform.put("env_vars", List.of(
            envVar(
                "TELEGRAM_BOT_TOKEN",
                "Telegram bot token",
                "Bot token",
                true,
                true,
                properties.getGateway().getTelegram().getBotToken()),
            envVar(
                "TELEGRAM_ALLOWED_USERS",
                "Allowed Telegram user IDs",
                "Allowed users",
                false,
                false,
                String.join(",", properties.getGateway().getTelegram().getAllowedUserIds())),
            envVar(
                "TELEGRAM_PROXY",
                "Optional Telegram proxy URL",
                "Proxy URL",
                false,
                false,
                environment.getProperty("TELEGRAM_PROXY", ""))));
        return platform;
    }

    private Map<String, Object> webhookPlatform() {
        Map<String, Object> platform = platformBase(
            WEBHOOK,
            "Webhooks",
            "Receive events from GitHub, GitLab, and other webhook sources.",
            "https://hermes-agent.nousresearch.com/docs/user-guide/messaging/webhooks/",
            false,
            false,
            true,
            "disabled");
        platform.put("env_vars", List.of(
            envVar("WEBHOOK_ENABLED", "Webhook receiver enabled", "Enabled", false, false, ""),
            envVar("WEBHOOK_PORT", "Webhook receiver port", "Port", false, false, ""),
            envVar("WEBHOOK_SECRET", "Default webhook secret", "Secret", false, true, "")));
        return platform;
    }

    private Map<String, Object> platformBase(
        String id,
        String name,
        String description,
        String docsUrl,
        boolean enabled,
        boolean configured,
        boolean gatewayRunning,
        String state
    ) {
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("id", id);
        platform.put("name", name);
        platform.put("description", description);
        platform.put("docs_url", docsUrl);
        platform.put("enabled", enabled);
        platform.put("configured", configured);
        platform.put("gateway_running", gatewayRunning);
        platform.put("home_channel", null);
        platform.put("state", state);
        platform.put("error_code", null);
        platform.put("error_message", null);
        platform.put("updated_at", null);
        return platform;
    }

    private Map<String, Object> envVar(
        String key,
        String description,
        String prompt,
        boolean required,
        boolean password,
        String configuredValue
    ) {
        String raw = defaultIfBlank(configuredValue, environment.getProperty(key, ""));
        return envVarStatus(key, description, prompt, required, password, hasText(raw));
    }

    private Map<String, Object> envVarStatus(
        String key, String description, String prompt, boolean required, boolean password, boolean isSet
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("advanced", false);
        row.put("description", description);
        row.put("help", description);
        row.put("is_password", password);
        row.put("is_set", isSet);
        row.put("key", key);
        row.put("prompt", prompt);
        row.put("redacted_value", isSet ? "********" : null);
        row.put("required", required);
        row.put("url", null);
        return row;
    }

    private boolean telegramEnabled() {
        return environment.getProperty("agent.gateway.telegram.webhook.enabled", Boolean.class, false)
            || environment.getProperty("agent.gateway.telegram.long-polling.enabled", Boolean.class, false);
    }

    private boolean telegramAdapterConnected() {
        var adapter = gatewayRoutingService.adapterFor(Platform.TELEGRAM);
        if (adapter == null) {
            return false;
        }
        return adapter
            .filter(TelegramAdapter.class::isInstance)
            .map(TelegramAdapter.class::cast)
            .map(TelegramAdapter::isConnected)
            .orElse(false);
    }

    private GatewayConfigWriter.TelegramAccess telegramAccess(String profile) {
        if (!GatewayConfigWriter.isDefault(profile)) {
            requireAdminMessagingAccess();
        }
        try {
            return gatewayConfigWriter.telegramAccess(profile);
        } catch (java.io.FileNotFoundException e) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown profile");
        } catch (IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Telegram profile configuration is unavailable");
        }
    }

    private List<Map<String, Object>> approvedPairingUsers(GatewayConfigWriter.TelegramAccess telegram) {
        List<Map<String, Object>> approved = new ArrayList<>();
        for (String id : telegram.userIds()) {
            String clean = clean(id);
            if (clean == null) {
                continue;
            }
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("platform", TELEGRAM);
            user.put("user_id", clean);
            user.put("user_name", "");
            approved.add(user);
        }
        for (String username : telegram.usernames()) {
            String clean = clean(username);
            if (clean == null) {
                continue;
            }
            String normalized = clean.startsWith("@") ? clean.substring(1) : clean;
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("platform", TELEGRAM);
            user.put("user_id", "@" + normalized);
            user.put("user_name", normalized);
            approved.add(user);
        }
        return approved;
    }

    private static List<String> missingRequiredEnv(Map<String, Object> platform) {
        Object envVars = platform.get("env_vars");
        if (!(envVars instanceof List<?> rows)) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> raw)) {
                continue;
            }
            Object required = raw.get("required");
            Object isSet = raw.get("is_set");
            Object key = raw.get("key");
            if (Boolean.TRUE.equals(required) && !Boolean.TRUE.equals(isSet) && key != null) {
                missing.add(String.valueOf(key));
            }
        }
        return missing;
    }

    private static Map<String, Object> testResponse(boolean ok, String state, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", ok);
        response.put("state", state);
        response.put("message", message);
        return response;
    }

    private static ResponseEntity<Map<String, Object>> unknownPlatform(String platformId) {
        String detail = "Unknown messaging platform: " + platformId;
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String detail) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("detail", detail, "error", detail));
    }

    private static ResponseEntity<Map<String, Object>> webhookSubscriptionNotFound(String name) {
        String key = normalizeWebhookName(name);
        return notFound("No subscription named '" + key + "'");
    }

    private static ResponseEntity<Map<String, Object>> notImplemented(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("detail", detail, "error", detail));
    }

    private static boolean isKnownPlatform(String platformId) {
        String normalized = normalizePlatform(platformId);
        return TELEGRAM.equals(normalized) || WEBHOOK.equals(normalized);
    }

    private static String normalizePlatform(String platformId) {
        return platformId == null ? "" : platformId.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }

    private static String normalizeWebhookName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

        @SuppressWarnings("unchecked")
    private static String requestedProfile(Map<String, Object> body) {
        if (body == null || !body.containsKey("profile")) {
            return "default";
        }
        Object value = body.get("profile");
        if (value instanceof String profile && !profile.isBlank()) {
            return profile;
        }
        throw new IllegalArgumentException("A valid Telegram profile is required");
    }

    private static Boolean optionalBoolean(Map<String, Object> body, String key) {
        if (body == null || !body.containsKey(key)) {
            return null;
        }
        if (body.get(key) instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(key + " must be a boolean");
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (raw instanceof String text && text.contains(",")) {
            List<String> result = new ArrayList<>();
            for (String part : text.split(",")) {
                if (!part.isBlank()) {
                    result.add(part.trim());
                }
            }
            return result;
        }
        List<String> single = new ArrayList<>();
        single.add(String.valueOf(raw));
        return single;
    }

private static String bodyString(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        return value instanceof String text ? text.trim() : "";
    }

    private static String clean(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static Path hermesHome() {
        String env = System.getenv("HERMES_HOME");
        if (hasText(env)) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".hermes").toAbsolutePath().normalize();
    }
}
