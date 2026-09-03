package com.azhukov.agent.api;

import com.azhukov.agent.gateway.BasePlatformAdapter;
import com.azhukov.agent.gateway.GatewayRoutingService;
import com.azhukov.agent.gateway.HttpEventPlatformAdapter;
import com.azhukov.agent.gateway.model.MessageEvent;
import com.azhukov.agent.gateway.model.Platform;
import com.azhukov.agent.gateway.model.PlatformConfig;
import com.azhukov.agent.gateway.model.SendResult;
import com.azhukov.agent.gateway.model.SessionSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformEventsControllerTest {

    private GatewayRoutingService gatewayRoutingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        gatewayRoutingService = mock(GatewayRoutingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new PlatformEventsController(gatewayRoutingService, new ObjectMapper()))
            .build();
    }

    @Test
    void invalidPlatformNameReturnsHermesError() throws Exception {
        mockMvc.perform(post("/api/platforms/{platform}/events", "bad.platform"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Invalid platform name"))
            .andExpect(jsonPath("$.error.code").value("invalid_platform"));
    }

    @Test
    void unavailablePlatformReturnsHermesError() throws Exception {
        mockMvc.perform(post("/api/platforms/{platform}/events", "slack"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.message").value("Platform adapter is not connected"))
            .andExpect(jsonPath("$.error.code").value("platform_unavailable"));
    }

    @Test
    void connectedPlatformWithoutHttpEventsReturnsHermesError() throws Exception {
        BasePlatformAdapter adapter = mock(BasePlatformAdapter.class);
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(post("/api/platforms/{platform}/events", "telegram"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.message").value("Platform adapter does not support HTTP events"))
            .andExpect(jsonPath("$.error.code").value("platform_http_events_unsupported"));
    }

    @Test
    void supportedPlatformDispatchesHttpEventLikeHermes() throws Exception {
        FakeHttpEventAdapter adapter = new FakeHttpEventAdapter("Bearer platform-secret", Map.of("ok", true));
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(post("/api/platforms/{platform}/events", "telegram")
                .header("Authorization", "Bearer platform-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"ping\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));

        org.assertj.core.api.Assertions.assertThat(adapter.authHeader).isEqualTo("Bearer platform-secret");
        org.assertj.core.api.Assertions.assertThat(adapter.payload).containsEntry("event", "ping");
    }

    @Test
    void profilePrefixedPlatformEventRouteMirrorsHermesMultiplexAlias() throws Exception {
        FakeHttpEventAdapter adapter = new FakeHttpEventAdapter("Bearer platform-secret", Map.of("ok", true));
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(post("/p/work/api/platforms/{platform}/events", "telegram")
                .header("Authorization", "Bearer platform-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"ping\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void invalidPlatformAuthorizationReturnsHermesError() throws Exception {
        FakeHttpEventAdapter adapter = new FakeHttpEventAdapter("Bearer platform-secret", Map.of("ok", true));
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(post("/api/platforms/{platform}/events", "telegram")
                .header("Authorization", "Bearer wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"ping\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.message").value("Invalid platform event authorization"))
            .andExpect(jsonPath("$.error.code").value("invalid_platform_event_authorization"));
    }

    @Test
    void verifierExceptionFailsClosedLikeHermes() throws Exception {
        FakeHttpEventAdapter adapter = new FakeHttpEventAdapter("Bearer platform-secret", Map.of("ok", true));
        adapter.throwOnVerify = true;
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(post("/api/platforms/{platform}/events", "telegram")
                .header("Authorization", "Bearer platform-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"ping\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.message").value("Invalid platform event authorization"))
            .andExpect(jsonPath("$.error.code").value("platform_event_verifier_error"));
    }

    @Test
    void malformedJsonReturnsPlatformEventMessageAfterAuth() throws Exception {
        FakeHttpEventAdapter adapter = new FakeHttpEventAdapter("Bearer platform-secret", Map.of("ok", true));
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(post("/api/platforms/{platform}/events", "telegram")
                .header("Authorization", "Bearer platform-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Invalid JSON in platform event"))
            .andExpect(jsonPath("$.error.code").value("invalid_json"));
    }

    @Test
    void nonObjectPayloadReturnsHermesError() throws Exception {
        FakeHttpEventAdapter adapter = new FakeHttpEventAdapter("Bearer platform-secret", Map.of("ok", true));
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(post("/api/platforms/{platform}/events", "telegram")
                .header("Authorization", "Bearer platform-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.message").value("Platform event must be a JSON object"))
            .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void dispatchExceptionReturnsHermesServerError() throws Exception {
        FakeHttpEventAdapter adapter = new FakeHttpEventAdapter("Bearer platform-secret", Map.of("ok", true));
        adapter.throwOnDispatch = true;
        when(gatewayRoutingService.adapterFor(Platform.TELEGRAM)).thenReturn(Optional.of(adapter));

        mockMvc.perform(post("/api/platforms/{platform}/events", "telegram")
                .header("Authorization", "Bearer platform-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event\":\"ping\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.message").value("Platform event dispatch failed"))
            .andExpect(jsonPath("$.error.type").value("server_error"))
            .andExpect(jsonPath("$.error.code").value("platform_event_dispatch_failed"));
    }

    private static class FakeHttpEventAdapter implements HttpEventPlatformAdapter {
        private final String expectedAuthHeader;
        private final Object result;
        private boolean throwOnVerify;
        private boolean throwOnDispatch;
        private String authHeader;
        private Map<String, Object> payload;

        FakeHttpEventAdapter(String expectedAuthHeader, Object result) {
            this.expectedAuthHeader = expectedAuthHeader;
            this.result = result;
        }

        @Override
        public VerificationResult verifyHttpEventRequest(String authorizationHeader) {
            if (throwOnVerify) {
                throw new IllegalStateException("verifier failed");
            }
            this.authHeader = authorizationHeader;
            if (expectedAuthHeader.equals(authorizationHeader)) {
                return VerificationResult.accepted();
            }
            return VerificationResult.denied("invalid_platform_event_authorization");
        }

        @Override
        public Object dispatchHttpEvent(Map<String, Object> payload) {
            if (throwOnDispatch) {
                throw new IllegalStateException("dispatch failed");
            }
            this.payload = payload;
            return result;
        }

        @Override
        public Platform platform() {
            return Platform.TELEGRAM;
        }

        @Override
        public CompletableFuture<Boolean> connect(PlatformConfig config) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> disconnect() {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<SendResult> send(SessionSource target, String text) {
            return CompletableFuture.completedFuture(new SendResult(true, null, null));
        }

        @Override
        public CompletableFuture<SendResult> sendImage(SessionSource target, byte[] image, String caption) {
            return CompletableFuture.completedFuture(new SendResult(true, null, null));
        }

        @Override
        public CompletableFuture<SendResult> sendDocument(
            SessionSource target,
            byte[] document,
            String fileName,
            String caption
        ) {
            return CompletableFuture.completedFuture(new SendResult(true, null, null));
        }

        @Override
        public CompletableFuture<SendResult> sendTyping(SessionSource target) {
            return CompletableFuture.completedFuture(new SendResult(true, null, null));
        }

        @Override
        public void setMessageHandler(Consumer<MessageEvent> handler) {
        }

        @Override
        public Optional<Map<String, Object>> buildSource(Map<String, Object> raw) {
            return Optional.empty();
        }
    }
}
