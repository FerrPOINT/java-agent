package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hermes dashboard compatibility for messaging-related pages.
 *
 * <p>The Java port has a Telegram adapter, but it does not yet have Hermes'
 * profile-scoped gateway config store, pairing store, or webhook subscription
 * registry. Read routes expose the closest safe runtime/configured state;
 * writes fail explicitly so the desktop does not fall through to generic 404s.</p>
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
        return Map.of(
            "env_path", hermesHome().resolve(".env").toString(),
            "gateway_start_command", "java -jar java-agent-backend.jar",
            "platforms", List.of(telegramPlatform(), webhookPlatform()));
    }

    @PutMapping("/api/messaging/platforms/{platformId}")
    @Operation(summary = "Reject dashboard messaging platform config writes")
    public ResponseEntity<Map<String, Object>> updatePlatform(
        @PathVariable String platformId,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        if (!isKnownPlatform(platformId)) {
            return unknownPlatform(platformId);
        }
        return notImplemented("messaging platform config writes are not implemented in the Java port");
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
        Map<String, Object> payload = TELEGRAM.equals(normalized) ? telegramPlatform() : webhookPlatform();
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
            "approved", approvedPairingUsers());
    }

    @PostMapping("/api/pairing/approve")
    @Operation(summary = "Reject dashboard pairing approvals without a Java pairing store")
    public ResponseEntity<Map<String, Object>> approvePairing(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        String platform = bodyString(body, "platform");
        String requestId = bodyString(body, "request_id");
        String code = bodyString(body, "code");
        if (!hasText(platform) || !hasText(requestId) && !hasText(code)) {
            return badRequest("platform and request_id or code are required");
        }
        return notImplemented("dashboard pairing approvals are not implemented in the Java port");
    }

    @PostMapping("/api/pairing/revoke")
    @Operation(summary = "Reject dashboard pairing revokes without a Java pairing store")
    public ResponseEntity<Map<String, Object>> revokePairing(
        @RequestBody(required = false) Map<String, Object> body
    ) {
        if (!hasText(bodyString(body, "platform")) || !hasText(bodyString(body, "user_id"))) {
            return badRequest("platform and user_id are required");
        }
        return notImplemented("dashboard pairing revokes are not implemented in the Java port");
    }

    @PostMapping("/api/pairing/clear-pending")
    @Operation(summary = "Return empty pending-pairing cleanup result")
    public Map<String, Object> clearPendingPairing(
        @RequestParam(name = "profile", required = false) String profile
    ) {
        return Map.of("ok", true, "cleared", 0);
    }

    @GetMapping("/api/webhooks")
    @Operation(summary = "Return empty webhook subscription catalog in Hermes shape")
    public Map<String, Object> webhooks() {
        return Map.of(
            "enabled", false,
            "base_url", "",
            "subscriptions", List.of());
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

    private Map<String, Object> telegramPlatform() {
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
        boolean isSet = hasText(raw);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("advanced", false);
        row.put("description", description);
        row.put("help", description);
        row.put("is_password", password);
        row.put("is_set", isSet);
        row.put("key", key);
        row.put("prompt", prompt);
        row.put("redacted_value", isSet ? redact(raw) : null);
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

    private List<Map<String, Object>> approvedPairingUsers() {
        List<Map<String, Object>> approved = new ArrayList<>();
        for (String id : properties.getGateway().getTelegram().getAllowedUserIds()) {
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
        for (String username : properties.getGateway().getTelegram().getAllowedUsernames()) {
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

    private static String bodyString(Map<String, Object> body, String key) {
        Object value = body != null ? body.get(key) : null;
        return value instanceof String text ? text.trim() : "";
    }

    private static String redact(String value) {
        if (!hasText(value)) {
            return null;
        }
        String clean = value.trim();
        if (clean.length() <= 8) {
            return "***";
        }
        return clean.substring(0, 4) + "..." + clean.substring(clean.length() - 4);
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
