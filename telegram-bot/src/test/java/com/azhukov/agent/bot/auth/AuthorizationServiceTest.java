package com.azhukov.agent.bot.auth;

import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.polling.UpdateEvent;
import com.azhukov.agent.bot.polling.UpdateEvent.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthorizationServiceTest {

    private BotProperties properties;
    private AuthorizationService service;
    private PairingService pairingService;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
        pairingService = mock(PairingService.class);
        service = new AuthorizationService(properties, pairingService);
    }

    @Test
    void allowByDefault_allowsAnyone() {
        properties.getAuth().setAllowByDefault(true);
        assertThat(service.isAuthorized(123, "anyone", 456)).isTrue();
    }

    @Test
    void userIdMatch_allows() {
        properties.getAuth().getAllowedUserIds().add("123");
        assertThat(service.isAuthorized(123, null, 456)).isTrue();
    }

    @Test
    void usernameMatch_allows() {
        properties.getAuth().getAllowedUsernames().add("jdoe");
        assertThat(service.isAuthorized(123, "jdoe", 456)).isTrue();
    }

    @Test
    void chatIdMatch_allows() {
        properties.getAuth().getAllowedChatIds().add("-100123");
        assertThat(service.isAuthorized(123, null, -100123)).isTrue();
    }

    @Test
    void noMatch_denies() {
        properties.getAuth().getAllowedUserIds().add("999");
        when(pairingService.hasApprovedPairing("123")).thenReturn(false);
        assertThat(service.isAuthorized(123, "jdoe", 456)).isFalse();
    }

    @Test
    void wildcardUserId_allowsAll() {
        properties.getAuth().getAllowedUserIds().add("*");
        assertThat(service.isAuthorized(123, null, 456)).isTrue();
    }

    @Test
    void wildcardUsername_allowsAll() {
        properties.getAuth().getAllowedUsernames().add("*");
        assertThat(service.isAuthorized(123, "anyone", 456)).isTrue();
    }

    @Test
    void emptyConfig_deniesFailClosed() {
        when(pairingService.hasApprovedPairing("123")).thenReturn(false);
        assertThat(service.isAuthorized(123, "jdoe", 456)).isFalse();
    }

    @Test
    void nullUsername_deniesIfNotInList() {
        properties.getAuth().getAllowedUsernames().add("jdoe");
        when(pairingService.hasApprovedPairing("123")).thenReturn(false);
        assertThat(service.isAuthorized(123, null, 456)).isFalse();
    }

    @Test
    void isAuthorizedByEvent_delegatesCorrectly() {
        properties.getAuth().setAllowByDefault(true);
        UpdateEvent event = new UpdateEvent(1, Type.TEXT, 123, 456, "jdoe",
            "hi", null, null, null, null, null, null, false, null, null);
        assertThat(service.isAuthorized(event)).isTrue();
    }

    // ─── Pairing check tests ──────────────────────────────────────

    @Test
    void approvedPairing_allowsAccess() {
        when(pairingService.hasApprovedPairing("123")).thenReturn(true);
        assertThat(service.isAuthorized(123, "jdoe", 456)).isTrue();
    }

    @Test
    void noApprovedPairing_deniesAccess() {
        when(pairingService.hasApprovedPairing("123")).thenReturn(false);
        assertThat(service.isAuthorized(123, "jdoe", 456)).isFalse();
    }

    @Test
    void approvedPairing_checkedAfterOtherMethodsFail() {
        // User not in allowlist, but has approved pairing
        when(pairingService.hasApprovedPairing("999")).thenReturn(true);
        assertThat(service.isAuthorized(999, "unknown", -1)).isTrue();
    }
}