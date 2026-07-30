package com.azhukov.agent.bot.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackIpValidatorTest {

    @Test
    void normalizesValidPublicIps() {
        List<String> result = FallbackIpValidator.normalize(
            List.of("149.154.167.220", "149.154.167.221"));
        assertThat(result).containsExactly("149.154.167.220", "149.154.167.221");
    }

    @Test
    void rejectsLoopbackIps() {
        List<String> result = FallbackIpValidator.normalize(List.of("127.0.0.1"));
        assertThat(result).isEmpty();
    }

    @Test
    void rejectsPrivateIps() {
        List<String> result = FallbackIpValidator.normalize(
            List.of("10.0.0.1", "192.168.1.1", "172.16.0.1"));
        assertThat(result).isEmpty();
    }

    @Test
    void rejectsLinkLocalIps() {
        List<String> result = FallbackIpValidator.normalize(List.of("169.254.1.1"));
        assertThat(result).isEmpty();
    }

    @Test
    void rejectsUnspecifiedIps() {
        List<String> result = FallbackIpValidator.normalize(List.of("0.0.0.0"));
        assertThat(result).isEmpty();
    }

    @Test
    void rejectsInvalidStrings() {
        List<String> result = FallbackIpValidator.normalize(List.of("not-an-ip", "999.999.999.999"));
        assertThat(result).isEmpty();
    }

    @Test
    void removesDuplicatesPreservingOrder() {
        List<String> result = FallbackIpValidator.normalize(
            List.of("149.154.167.220", "149.154.167.220", "149.154.167.221"));
        assertThat(result).containsExactly("149.154.167.220", "149.154.167.221");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(FallbackIpValidator.normalize(null)).isEmpty();
        assertThat(FallbackIpValidator.normalize(List.of())).isEmpty();
    }

    @Test
    void handlesBlankEntries() {
        List<String> result = FallbackIpValidator.normalize(List.of("  ", "149.154.167.220", ""));
        assertThat(result).containsExactly("149.154.167.220");
    }

    @Test
    void parseCsvWorks() {
        List<String> result = FallbackIpValidator.parseCsv("149.154.167.220, 149.154.167.221");
        assertThat(result).containsExactly("149.154.167.220", "149.154.167.221");
    }

    @Test
    void parseCsvNullReturnsEmpty() {
        assertThat(FallbackIpValidator.parseCsv(null)).isEmpty();
        assertThat(FallbackIpValidator.parseCsv("")).isEmpty();
        assertThat(FallbackIpValidator.parseCsv("  ")).isEmpty();
    }

    @Test
    void parseCsvFiltersPrivateIps() {
        List<String> result = FallbackIpValidator.parseCsv("127.0.0.1,149.154.167.220");
        assertThat(result).containsExactly("149.154.167.220");
    }
}