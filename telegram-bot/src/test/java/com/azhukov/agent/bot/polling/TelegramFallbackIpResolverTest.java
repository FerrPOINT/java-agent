package com.azhukov.agent.bot.polling;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3.8: Tests for TelegramFallbackIpResolver — fallback IP parsing and resolution.
 */
class TelegramFallbackIpResolverTest {

    // ─── parseFallbackIps static method ───────────────────────────

    @Test
    void parseFallbackIps_null_returnsEmptyList() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps(null);
        assertThat(ips).isEmpty();
    }

    @Test
    void parseFallbackIps_emptyString_returnsEmptyList() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps("");
        assertThat(ips).isEmpty();
    }

    @Test
    void parseFallbackIps_blankString_returnsEmptyList() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps("   ");
        assertThat(ips).isEmpty();
    }

    @Test
    void parseFallbackIps_singleIp_returnsListWithOneIp() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps("1.2.3.4");
        assertThat(ips).hasSize(1);
        assertThat(ips).containsExactly("1.2.3.4");
    }

    @Test
    void parseFallbackIps_multipleIps_returnsListWithAllIps() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps("1.2.3.4,5.6.7.8");
        assertThat(ips).hasSize(2);
        assertThat(ips).containsExactly("1.2.3.4", "5.6.7.8");
    }

    @Test
    void parseFallbackIps_ipsWithSpaces_trimsEntries() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps("  1.2.3.4 , 5.6.7.8  ");
        assertThat(ips).hasSize(2);
        assertThat(ips).containsExactly("1.2.3.4", "5.6.7.8");
    }

    @Test
    void parseFallbackIps_ipsWithEmptyParts_skipsEmptyEntries() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps("1.2.3.4,,5.6.7.8,  ,");
        assertThat(ips).hasSize(2);
        assertThat(ips).containsExactly("1.2.3.4", "5.6.7.8");
    }

    @Test
    void parseFallbackIps_singleIpWithSpaces_trimsAndReturns() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps("  149.154.167.50  ");
        assertThat(ips).hasSize(1);
        assertThat(ips).containsExactly("149.154.167.50");
    }

    @Test
    void parseFallbackIps_threeIps_returnsAllThree() {
        List<String> ips = TelegramFallbackIpResolver.parseFallbackIps("149.154.167.50,149.154.167.51,149.154.167.52");
        assertThat(ips).hasSize(3);
        assertThat(ips).containsExactly("149.154.167.50", "149.154.167.51", "149.154.167.52");
    }

    // ─── Instance methods (no env var set) ────────────────────────

    @Test
    void hasFallbackIps_noEnvVar_returnsFalse() {
        // New instance reads TELEGRAM_FALLBACK_IPS env var; if not set, should be false
        // Note: This test assumes the env var is NOT set in the test environment
        TelegramFallbackIpResolver resolver = new TelegramFallbackIpResolver();
        // If the env var happens to be set, this test would fail — but in CI it should be unset
        if (System.getenv("TELEGRAM_FALLBACK_IPS") == null) {
            assertThat(resolver.hasFallbackIps()).isFalse();
        }
    }

    @Test
    void getFallbackIps_noEnvVar_returnsEmptyList() {
        TelegramFallbackIpResolver resolver = new TelegramFallbackIpResolver();
        if (System.getenv("TELEGRAM_FALLBACK_IPS") == null) {
            assertThat(resolver.getFallbackIps()).isEmpty();
        }
    }

    @Test
    void getFallbackIps_returnsDefensiveCopy() {
        // The returned list should be immutable (List.copyOf)
        TelegramFallbackIpResolver resolver = new TelegramFallbackIpResolver();
        List<String> ips = resolver.getFallbackIps();
        assertThat(ips).isNotNull();
        // Verify it's a defensive copy — should be unmodifiable
        assertThat(ips.getClass().getName()).contains("Immutable");
    }
}