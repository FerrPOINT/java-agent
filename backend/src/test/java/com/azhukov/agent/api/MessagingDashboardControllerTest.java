package com.azhukov.agent.api;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.telegram.TelegramAdapter;
import com.azhukov.agent.gateway.telegram.TelegramBotApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessagingDashboardControllerTest {

    private AgentProperties properties;
    private GatewayRoutingService gatewayRoutingService;
    private MockEnvironment environment;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        gatewayRoutingService = mock(GatewayRoutingService.class);
        environment = new MockEnvironment();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new MessagingDashboardController(properties, gatewayRoutingService, environment))
            .build();
    }

    @Test
    void platformsExposeTelegramAndWebhookCardsWithoutSecrets() throws Exception {
        properties.getGateway().getTelegram().setBotToken("123456:abcdefghijklmnopqrstuvwxyzABCDE");
        properties.getGateway().getTelegram().getAllowedUserIds().add("42");
        environment.setProperty("agent.gateway.telegram.long-polling.enabled", "true");
        TelegramAdapter adapter = new TelegramAdapter(properties, mock(TelegramBotApiClient.class));
        adapter.connect(new PlatformConfig(Platform.TELEGRAM, true, Map.of(), Map.of())).join();
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(get("/api/messaging/platforms"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.env_path").exists())
            .andExpect(jsonPath("$.gateway_start_command").value("java -jar java-agent-backend.jar"))
            .andExpect(jsonPath("$.platforms[0].id").value("telegram"))
            .andExpect(jsonPath("$.platforms[0].enabled").value(true))
            .andExpect(jsonPath("$.platforms[0].configured").value(true))
            .andExpect(jsonPath("$.platforms[0].gateway_running").value(true))
            .andExpect(jsonPath("$.platforms[0].state").value("connected"))
            .andExpect(jsonPath("$.platforms[0].env_vars[0].key").value("TELEGRAM_BOT_TOKEN"))
            .andExpect(jsonPath("$.platforms[0].env_vars[0].required").value(true))
            .andExpect(jsonPath("$.platforms[0].env_vars[0].is_set").value(true))
            .andExpect(jsonPath("$.platforms[0].env_vars[0].help").value("Telegram bot token"))
            .andExpect(jsonPath("$.platforms[1].id").value("webhook"))
            .andExpect(jsonPath("$.platforms[1].state").value("disabled"))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("abcdefghijklmnopqrstuvwxyzABCDE"))));
    }

    @Test
    void testTelegramReportsMissingRequiredTokenInHermesShape() throws Exception {
        environment.setProperty("agent.gateway.telegram.webhook.enabled", "true");

        mockMvc.perform(post("/api/messaging/platforms/telegram/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.state").value("not_configured"))
            .andExpect(jsonPath("$.message").value("Missing required setup: TELEGRAM_BOT_TOKEN"));
    }

    @Test
    void testTelegramReportsPendingRestartWhenConfiguredButAdapterHasNotConnected() throws Exception {
        properties.getGateway().getTelegram().setBotToken("123456:abcdefghijklmnopqrstuvwxyzABCDE");
        environment.setProperty("agent.gateway.telegram.long-polling.enabled", "true");
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/messaging/platforms/telegram/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.state").value("pending_restart"))
            .andExpect(jsonPath("$.message").value(
                "Setup looks complete, but the gateway has not reported a connection yet. Restart the gateway."));
    }

    @Test
    void messagingWritesReturnExplicitErrorsAndUnknownPlatformsStill404() throws Exception {
        mockMvc.perform(put("/api/messaging/platforms/telegram")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value(
                "messaging platform config writes are not implemented in the Java port"));

        mockMvc.perform(post("/api/messaging/platforms/slack/test"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Unknown messaging platform: slack"));
    }

    @Test
    void onboardingRoutesReturnExplicitFallbacksWithoutGeneric404s() throws Exception {
        mockMvc.perform(post("/api/messaging/telegram/onboarding/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bot_name\":\"Java Agent\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("Telegram onboarding is not implemented in the Java port"));

        mockMvc.perform(get("/api/messaging/telegram/onboarding/pairing-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Telegram setup session was not found. Start a new setup."));

        mockMvc.perform(post("/api/messaging/telegram/onboarding/pairing-1/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowed_user_ids\":[\"42\"]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Telegram setup session was not found. Start a new setup."));

        mockMvc.perform(delete("/api/messaging/telegram/onboarding/pairing-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(post("/api/messaging/whatsapp/onboarding/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"bot\",\"allowed_users\":\"42\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value("WhatsApp onboarding is not implemented in the Java port"));

        mockMvc.perform(get("/api/messaging/whatsapp/onboarding/pairing-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("WhatsApp setup session was not found. Start a new setup."));

        mockMvc.perform(post("/api/messaging/whatsapp/onboarding/pairing-1/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"self-chat\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("WhatsApp setup session was not found. Start a new setup."));

        mockMvc.perform(delete("/api/messaging/whatsapp/onboarding/pairing-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void pairingListsStaticTelegramAllowlistAndRejectsMutations() throws Exception {
        properties.getGateway().getTelegram().getAllowedUserIds().add("42");
        properties.getGateway().getTelegram().getAllowedUsernames().add("alice");

        mockMvc.perform(get("/api/pairing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pending").isArray())
            .andExpect(jsonPath("$.pending.length()").value(0))
            .andExpect(jsonPath("$.approved[0].platform").value("telegram"))
            .andExpect(jsonPath("$.approved[0].user_id").value("42"))
            .andExpect(jsonPath("$.approved[1].user_id").value("@alice"))
            .andExpect(jsonPath("$.approved[1].user_name").value("alice"));

        mockMvc.perform(post("/api/pairing/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"telegram\",\"request_id\":\"req_1\"}"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value(
                "dashboard pairing approvals are not implemented in the Java port"));

        mockMvc.perform(post("/api/pairing/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"telegram\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("platform and request_id or code are required"));

        mockMvc.perform(post("/api/pairing/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"telegram\",\"user_id\":\"42\"}"))
            .andExpect(status().isNotImplemented());

        mockMvc.perform(post("/api/pairing/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"platform\":\"telegram\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("platform and user_id are required"));

        mockMvc.perform(post("/api/pairing/clear-pending"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.cleared").value(0));
    }

    @Test
    void webhooksExposeEmptyCatalogAndRejectSubscriptionWrites() throws Exception {
        mockMvc.perform(get("/api/webhooks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.base_url").value(""))
            .andExpect(jsonPath("$.subscriptions").isArray())
            .andExpect(jsonPath("$.subscriptions.length()").value(0));

        mockMvc.perform(post("/api/webhooks/enable"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.detail").value(
                "webhook subscription management is not implemented in the Java port"));

        mockMvc.perform(post("/api/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(
                "Webhook platform is not enabled. Enable it from the Webhooks page first."));

        mockMvc.perform(post("/api/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("name is required"));

        mockMvc.perform(delete("/api/webhooks/ci"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("No subscription named 'ci'"));

        mockMvc.perform(put("/api/webhooks/ci/enabled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("No subscription named 'ci'"));
    }
}
